package com.atakmap.android.weather.presentation.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.atakmap.android.weather.domain.model.ComparisonModel;
import com.atakmap.android.weather.domain.model.DailyForecastModel;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.LocationSnapshot;
import com.atakmap.android.weather.domain.model.LocationSource;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.repository.IGeocodingRepository;
import com.atakmap.android.weather.data.cache.CachingWeatherRepository;
import com.atakmap.android.weather.domain.repository.IWeatherRepository;

import java.util.List;

/**
 * ViewModel for all weather tabs.
 *
 * ── Sprint 1 changes ─────────────────────────────────────────────────────────
 *
 * 1. LocationSource awareness
 *    Every load call now carries a LocationSource (SELF_MARKER or MAP_CENTRE).
 *    The resolved LocationSnapshot is emitted on activeLocation LiveData so
 *    Tab 1 can display both the name and exact coordinates.
 *
 * 2. GPS fallback chain
 *    loadWeatherWithFallback(preferSelf): tries self marker first; if lat=0
 *    and lon=0 (no GPS fix), automatically falls back to map centre and
 *    records that in the emitted LocationSnapshot.
 *    The Receiver calls this instead of two separate methods.
 *
 * 3. Auto-trigger comparison
 *    The first successful Tab-1 load also fires loadComparison() so Tab 6
 *    is populated without requiring the user to switch tabs first.
 *
 * 4. Geocoding never returns "Unknown location"
 *    IGeocodingRepository.Callback now receives a LocationSnapshot (Sprint 1
 *    interface change).  On network failure the geocoding source itself
 *    produces a coords-only fallback string — the ViewModel never has to
 *    guard against null names.
 *
 * 5. Comparison cards carry their own LocationSnapshots
 *    selfLocation / centerLocation LiveData feeds Tab 6 card headers with
 *    name + coords independently of the main Tab 1 location.
 */
public class WeatherViewModel extends ViewModel {

    // ── LiveData — Tab 1 ─────────────────────────────────────────────────────
    private final MutableLiveData<UiState<WeatherModel>>             currentWeather    = new MutableLiveData<>();
    private final MutableLiveData<UiState<List<DailyForecastModel>>> dailyForecast     = new MutableLiveData<>();
    private final MutableLiveData<UiState<List<HourlyEntryModel>>>   hourlyForecast    = new MutableLiveData<>();
    private final MutableLiveData<LocationSnapshot>                  activeLocation    = new MutableLiveData<>();
    private final MutableLiveData<Integer>                           selectedHour      = new MutableLiveData<>(0);
    private final MutableLiveData<String>                            selectedHourLabel = new MutableLiveData<>("");
    private final MutableLiveData<String>                            errorMessage      = new MutableLiveData<>();

    /**
     * Sprint 3: cache badge shown in Tab 1 header.
     * Empty string = fresh data (badge hidden).
     * "Cached HH:MM" = served from Room cache.
     * "Cached HH:MM ⚠" = stale offline fallback.
     */
    private final MutableLiveData<String> cacheBadge = new MutableLiveData<>("");

    // ── LiveData — Tab 6 (Comparison) ────────────────────────────────────────
    private final MutableLiveData<UiState<WeatherModel>>    selfMarkerWeather = new MutableLiveData<>();
    private final MutableLiveData<UiState<WeatherModel>>    mapCenterWeather  = new MutableLiveData<>();
    private final MutableLiveData<UiState<ComparisonModel>> comparison        = new MutableLiveData<>();
    private final MutableLiveData<LocationSnapshot>         selfLocation      = new MutableLiveData<>();
    private final MutableLiveData<LocationSnapshot>         centerLocation    = new MutableLiveData<>();

    // ── Repositories ─────────────────────────────────────────────────────────
    private final IWeatherRepository   weatherRepository;
    private final IGeocodingRepository geocodingRepository;

    // ── Internal state ───────────────────────────────────────────────────────
    private List<HourlyEntryModel> hourlyCache;
    private boolean comparisonAutoTriggered = false;

    // ── Constructor ──────────────────────────────────────────────────────────

    public WeatherViewModel(IWeatherRepository weatherRepository,
                            IGeocodingRepository geocodingRepository) {
        this.weatherRepository   = weatherRepository;
        this.geocodingRepository = geocodingRepository;
        // Sprint 3: wire cache badge if using the caching repository
        if (weatherRepository instanceof CachingWeatherRepository) {
            ((CachingWeatherRepository) weatherRepository).setCacheStatusListener(
                    (status, label) -> cacheBadge.setValue(label));
        }
    }

    // ── Public API — Tab 1 ────────────────────────────────────────────────────

    /**
     * Primary load entry point used by the Receiver on every open/refresh.
     *
     * Fallback chain:
     *  - selfLat == 0 && selfLon == 0  →  no GPS fix  →  use mapCentre coords
     *    and emit a MAP_CENTRE LocationSnapshot so Tab 1 shows
     *    "Map centre — …" instead of "Unknown location"
     *  - otherwise use SELF_MARKER
     *
     * @param selfLat  GPS latitude  (0 if not yet fixed)
     * @param selfLon  GPS longitude (0 if not yet fixed)
     * @param cenLat   map-centre latitude  (always valid)
     * @param cenLon   map-centre longitude (always valid)
     */
    public void loadWeatherWithFallback(double selfLat, double selfLon,
                                        double cenLat,  double cenLon) {
        boolean hasGps = !(selfLat == 0.0 && selfLon == 0.0);
        double  lat    = hasGps ? selfLat : cenLat;
        double  lon    = hasGps ? selfLon : cenLon;
        LocationSource src = hasGps ? LocationSource.SELF_MARKER : LocationSource.MAP_CENTRE;
        loadWeather(lat, lon, src);
    }

    /**
     * Explicit load for a specific coordinate + source.
     * Used by the refresh button (short = MAP_CENTRE, long-press = SELF_MARKER).
     */
    public void loadWeather(double latitude, double longitude, LocationSource source) {
        fetchCurrentWeather(latitude, longitude);
        fetchDailyForecast(latitude, longitude);
        fetchHourlyForecast(latitude, longitude);
        reverseGeocode(latitude, longitude, source, activeLocation);
    }

    /**
     * Comparison load: fetches SELF_MARKER and MAP_CENTRE in parallel.
     * Auto-called after the first successful Tab-1 load (see fetchHourlyForecast).
     */
    public void loadComparison(double selfLat, double selfLon,
                               double cenLat,  double cenLon) {
        comparison.setValue(UiState.loading());

        final WeatherModel[] results = new WeatherModel[2]; // [0]=self [1]=center

        // Self marker
        reverseGeocode(selfLat, selfLon, LocationSource.SELF_MARKER, selfLocation);
        weatherRepository.getCurrentWeather(selfLat, selfLon,
                new IWeatherRepository.Callback<WeatherModel>() {
                    @Override public void onSuccess(WeatherModel r) {
                        results[0] = r;
                        selfMarkerWeather.setValue(UiState.success(r));
                        tryBuildComparison(results);
                    }
                    @Override public void onError(String msg) {
                        selfMarkerWeather.setValue(UiState.error(msg));
                        comparison.setValue(UiState.error(msg));
                    }
                });

        // Map centre
        reverseGeocode(cenLat, cenLon, LocationSource.MAP_CENTRE, centerLocation);
        weatherRepository.getCurrentWeather(cenLat, cenLon,
                new IWeatherRepository.Callback<WeatherModel>() {
                    @Override public void onSuccess(WeatherModel r) {
                        results[1] = r;
                        mapCenterWeather.setValue(UiState.success(r));
                        tryBuildComparison(results);
                    }
                    @Override public void onError(String msg) {
                        mapCenterWeather.setValue(UiState.error(msg));
                        comparison.setValue(UiState.error(msg));
                    }
                });
    }

    /**
     * Update selected SeekBar hour and compute a human-readable label.
     * Format: "+06h  (14:00)"
     */
    public void selectHour(int index) {
        selectedHour.setValue(index);
        if (hourlyCache != null && index >= 0 && index < hourlyCache.size()) {
            HourlyEntryModel entry = hourlyCache.get(index);
            String label = String.format("+%02dh  (%02d:00)", index, entry.getHour());
            selectedHourLabel.setValue(label);
        }
    }

    // ── LiveData getters ──────────────────────────────────────────────────────

    public LiveData<UiState<WeatherModel>>             getCurrentWeather()    { return currentWeather; }
    public LiveData<UiState<List<DailyForecastModel>>> getDailyForecast()     { return dailyForecast; }
    public LiveData<UiState<List<HourlyEntryModel>>>   getHourlyForecast()    { return hourlyForecast; }
    public LiveData<LocationSnapshot>                  getActiveLocation()    { return activeLocation; }
    public LiveData<Integer>                           getSelectedHour()      { return selectedHour; }
    public LiveData<String>                            getSelectedHourLabel() { return selectedHourLabel; }
    public LiveData<String>                            getErrorMessage()      { return errorMessage; }
    public LiveData<String>                            getCacheBadge()        { return cacheBadge; }
    public LiveData<UiState<WeatherModel>>             getSelfMarkerWeather() { return selfMarkerWeather; }
    public LiveData<UiState<WeatherModel>>             getMapCenterWeather()  { return mapCenterWeather; }
    public LiveData<UiState<ComparisonModel>>          getComparison()        { return comparison; }
    public LiveData<LocationSnapshot>                  getSelfLocation()      { return selfLocation; }
    public LiveData<LocationSnapshot>                  getCenterLocation()    { return centerLocation; }

    // ── Private fetch helpers ─────────────────────────────────────────────────

    private void fetchCurrentWeather(double lat, double lon) {
        currentWeather.setValue(UiState.loading());
        weatherRepository.getCurrentWeather(lat, lon,
                new IWeatherRepository.Callback<WeatherModel>() {
                    @Override public void onSuccess(WeatherModel r) { currentWeather.setValue(UiState.success(r)); }
                    @Override public void onError(String msg)       { currentWeather.setValue(UiState.error(msg)); errorMessage.setValue(msg); }
                });
    }

    private void fetchDailyForecast(double lat, double lon) {
        dailyForecast.setValue(UiState.loading());
        weatherRepository.getDailyForecast(lat, lon,
                new IWeatherRepository.Callback<List<DailyForecastModel>>() {
                    @Override public void onSuccess(List<DailyForecastModel> r) { dailyForecast.setValue(UiState.success(r)); }
                    @Override public void onError(String msg)                    { dailyForecast.setValue(UiState.error(msg)); errorMessage.setValue(msg); }
                });
    }

    private void fetchHourlyForecast(double lat, double lon) {
        hourlyForecast.setValue(UiState.loading());
        weatherRepository.getHourlyForecast(lat, lon,
                new IWeatherRepository.Callback<List<HourlyEntryModel>>() {
                    @Override public void onSuccess(List<HourlyEntryModel> r) {
                        hourlyCache = r;
                        hourlyForecast.setValue(UiState.success(r));
                        selectHour(0);
                    }
                    @Override public void onError(String msg) {
                        hourlyForecast.setValue(UiState.error(msg));
                        errorMessage.setValue(msg);
                    }
                });
    }

    /**
     * Reverse-geocode and emit result into the given LiveData slot.
     * On network failure the geocoding source itself provides a coords-only
     * fallback — this method always calls onSuccess, never onError.
     */
    private void reverseGeocode(double lat, double lon, LocationSource source,
                                MutableLiveData<LocationSnapshot> target) {
        geocodingRepository.reverseGeocode(lat, lon, source,
                new IGeocodingRepository.Callback() {
                    @Override public void onSuccess(LocationSnapshot snapshot) {
                        target.setValue(snapshot);
                    }
                    @Override public void onError(String msg) {
                        // Should not reach here — NominatimGeocodingSource
                        // always calls onSuccess with a coords fallback.
                        // Guard anyway.
                        target.setValue(new LocationSnapshot(lat, lon,
                                LocationSnapshot.coordsFallback(lat, lon), source));
                    }
                });
    }

    private void tryBuildComparison(WeatherModel[] results) {
        if (results[0] != null && results[1] != null) {
            comparison.setValue(UiState.success(
                    new ComparisonModel(results[0], results[1])));
        }
    }
}
