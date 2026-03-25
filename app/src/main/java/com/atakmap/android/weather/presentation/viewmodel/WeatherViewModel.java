package com.atakmap.android.weather.presentation.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.atakmap.android.weather.data.cache.CacheStatusProvider;
import com.atakmap.android.weather.data.cache.CachingWeatherRepository;
import com.atakmap.android.weather.domain.model.ComparisonModel;
import com.atakmap.android.weather.domain.model.DailyForecastModel;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.LocationSnapshot;
import com.atakmap.android.weather.domain.model.LocationSource;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.repository.IGeocodingRepository;
import com.atakmap.android.weather.domain.repository.IWeatherRepository;

import java.util.List;

/**
 * ViewModel for all weather tabs.
 *
 * <h3>Refactoring changes (vs original)</h3>
 * <ul>
 *   <li>{@code instanceof CachingWeatherRepository} replaced with
 *       {@code instanceof CacheStatusProvider} — WeatherViewModel no longer
 *       imports the concrete caching class, keeping it decoupled from the
 *       data layer.</li>
 *   <li>{@code comparisonAutoTriggered} dead field removed.</li>
 *   <li>{@code hourlyCache} is now only held here (removed duplicate in DDR).</li>
 * </ul>
 */
public class WeatherViewModel extends ViewModel {

    // ── LiveData — Tab 1 ──────────────────────────────────────────────────────
    private final MutableLiveData<UiState<WeatherModel>>             currentWeather    = new MutableLiveData<>();
    private final MutableLiveData<UiState<List<DailyForecastModel>>> dailyForecast     = new MutableLiveData<>();
    private final MutableLiveData<UiState<List<HourlyEntryModel>>>   hourlyForecast    = new MutableLiveData<>();
    private final MutableLiveData<LocationSnapshot>                  activeLocation    = new MutableLiveData<>();
    private final MutableLiveData<Integer>                           selectedHour      = new MutableLiveData<>(0);
    private final MutableLiveData<String>                            selectedHourLabel = new MutableLiveData<>("");
    private final MutableLiveData<String>                            errorMessage      = new MutableLiveData<>();

    /**
     * Cache badge shown in Tab 1 header.
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

    // ── Repositories ──────────────────────────────────────────────────────────
    private final IWeatherRepository   weatherRepository;
    private final IGeocodingRepository geocodingRepository;

    // ── Internal state ────────────────────────────────────────────────────────
    /** Authoritative hourly cache — DDR reads from here via getHourlyForecast(). */
    private List<HourlyEntryModel> hourlyCache;

    // ── Constructor ───────────────────────────────────────────────────────────

    public WeatherViewModel(IWeatherRepository weatherRepository,
                            IGeocodingRepository geocodingRepository) {
        this.weatherRepository   = weatherRepository;
        this.geocodingRepository = geocodingRepository;

        // Wire cache badge via the CacheStatusProvider interface — no concrete import needed.
        if (weatherRepository instanceof CacheStatusProvider) {
            ((CacheStatusProvider) weatherRepository).setCacheStatusListener(
                    (status, label) -> cacheBadge.setValue(label));
        }
    }

    // ── Public API — Tab 1 ────────────────────────────────────────────────────

    /**
     * Primary load entry point used by the Receiver on every open/refresh.
     *
     * <p>Fallback chain: if {@code selfLat == 0 && selfLon == 0} (no GPS fix)
     * the map-centre coordinates are used and a {@link LocationSource#MAP_CENTRE}
     * snapshot is emitted.</p>
     *
     * @param selfLat GPS latitude  (0 if not yet fixed)
     * @param selfLon GPS longitude (0 if not yet fixed)
     * @param cenLat  map-centre latitude  (always valid)
     * @param cenLon  map-centre longitude (always valid)
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
     * Explicit load for a specific coordinate and source.
     * Used by the refresh button (short = MAP_CENTRE, long-press = SELF_MARKER).
     */
    public void loadWeather(double latitude, double longitude, LocationSource source) {
        // Tell the caching layer which source we are loading for, so cache keys
        // are keyed correctly (MAP_CENTRE vs SELF_MARKER).
        if (weatherRepository instanceof CachingWeatherRepository) {
            ((CachingWeatherRepository) weatherRepository).setCurrentSource(source);
        }
        fetchCurrentWeather(latitude, longitude);
        fetchDailyForecast(latitude, longitude);
        fetchHourlyForecast(latitude, longitude);
        reverseGeocode(latitude, longitude, source, activeLocation);
    }

    /**
     * Callback for single-point weather fetch (used by route weather).
     */
    public interface PointWeatherCallback {
        void onResult(WeatherModel weather);
    }

    /**
     * Fetch current weather for a single point with a direct callback.
     * Does NOT update LiveData — used for route weather batch fetches.
     */
    public void loadWeatherForPoint(double lat, double lon,
                                     PointWeatherCallback callback) {
        weatherRepository.getCurrentWeather(lat, lon,
                new IWeatherRepository.Callback<WeatherModel>() {
                    @Override public void onSuccess(WeatherModel r) {
                        callback.onResult(r);
                    }
                    @Override public void onError(String msg) {
                        callback.onResult(null);
                    }
                });
    }

    /**
     * Comparison load: fetches SELF_MARKER and MAP_CENTRE in parallel.
     * Auto-called after the first successful Tab-1 load (see fetchHourlyForecast).
     */
    public void loadComparison(double selfLat, double selfLon,
                               double cenLat,  double cenLon) {
        comparison.setValue(UiState.loading());

        final WeatherModel[] results = new WeatherModel[2]; // [0]=self [1]=center

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
     * Format: {@code "+06h  (14:00)"}
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
                    @Override public void onSuccess(WeatherModel r) {
                        currentWeather.setValue(UiState.success(r));
                    }
                    @Override public void onError(String msg) {
                        currentWeather.setValue(UiState.error(msg));
                        errorMessage.setValue(msg);
                    }
                });
    }

    private void fetchDailyForecast(double lat, double lon) {
        dailyForecast.setValue(UiState.loading());
        weatherRepository.getDailyForecast(lat, lon,
                new IWeatherRepository.Callback<List<DailyForecastModel>>() {
                    @Override public void onSuccess(List<DailyForecastModel> r) {
                        dailyForecast.setValue(UiState.success(r));
                    }
                    @Override public void onError(String msg) {
                        dailyForecast.setValue(UiState.error(msg));
                        errorMessage.setValue(msg);
                    }
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
     * On network failure the geocoding source provides a coords-only fallback —
     * this method always calls onSuccess, never onError.
     */
    private void reverseGeocode(double lat, double lon, LocationSource source,
                                MutableLiveData<LocationSnapshot> target) {
        geocodingRepository.reverseGeocode(lat, lon, source,
                new IGeocodingRepository.Callback() {
                    @Override public void onSuccess(LocationSnapshot snapshot) {
                        target.setValue(snapshot);
                    }
                    @Override public void onError(String msg) {
                        // NominatimGeocodingSource always calls onSuccess with a coords fallback.
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
