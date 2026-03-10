package com.atakmap.android.weather.presentation.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.atakmap.android.weather.domain.model.ComparisonModel;
import com.atakmap.android.weather.domain.model.DailyForecastModel;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.repository.IGeocodingRepository;
import com.atakmap.android.weather.domain.repository.IWeatherRepository;

import java.util.List;

/**
 * ViewModel for Tab 1 (Current Weather + Daily Forecast + Hourly SeekBar)
 * and Tab 5 (Self Marker vs Map Center comparison).
 *
 * New in this version:
 *  - selectedHourLabel  : human-readable time string for the selected SeekBar index
 *  - selfMarkerWeather  : weather at the device GPS position
 *  - mapCenterWeather   : weather at the map centre (may differ from self)
 *  - comparison         : ComparisonModel combining both for the diff table
 */
public class WeatherViewModel extends ViewModel {

    // ── LiveData exposed to the View ─────────────────────────────────────────

    // Tab 1 — primary location (self marker on first load, map centre on refresh)
    private final MutableLiveData<UiState<WeatherModel>>             currentWeather  = new MutableLiveData<>();
    private final MutableLiveData<UiState<List<DailyForecastModel>>> dailyForecast   = new MutableLiveData<>();
    private final MutableLiveData<UiState<List<HourlyEntryModel>>>   hourlyForecast  = new MutableLiveData<>();
    private final MutableLiveData<String>                            locationName    = new MutableLiveData<>();
    private final MutableLiveData<Integer>                           selectedHour    = new MutableLiveData<>(0);

    /**
     * Human-readable label for the currently selected SeekBar hour.
     * e.g. "+06h  (14:00)" shown below the SeekBar.
     */
    private final MutableLiveData<String>                            selectedHourLabel = new MutableLiveData<>("");
    private final MutableLiveData<String>                            errorMessage    = new MutableLiveData<>();

    // Tab 5 — comparison
    private final MutableLiveData<UiState<WeatherModel>>             selfMarkerWeather = new MutableLiveData<>();
    private final MutableLiveData<UiState<WeatherModel>>             mapCenterWeather  = new MutableLiveData<>();
    private final MutableLiveData<UiState<ComparisonModel>>          comparison        = new MutableLiveData<>();

    // ── Repositories ─────────────────────────────────────────────────────────

    private final IWeatherRepository    weatherRepository;
    private final IGeocodingRepository  geocodingRepository;

    // Cached hourly list for hour-label computation without View access
    private List<HourlyEntryModel> hourlyCache;

    // ── Constructor ──────────────────────────────────────────────────────────

    public WeatherViewModel(IWeatherRepository weatherRepository,
                            IGeocodingRepository geocodingRepository) {
        this.weatherRepository   = weatherRepository;
        this.geocodingRepository = geocodingRepository;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Full refresh for a given coordinate (used on open + refresh button). */
    public void loadWeather(double latitude, double longitude) {
        fetchCurrentWeather(latitude, longitude);
        fetchDailyForecast(latitude, longitude);
        fetchHourlyForecast(latitude, longitude);
        reverseGeocode(latitude, longitude);
    }

    /**
     * Load both positions for the comparison tab.
     * Call this when the Comparison tab becomes active.
     */
    public void loadComparison(double selfLat, double selfLon,
                               double centerLat, double centerLon) {
        comparison.setValue(UiState.loading());

        // Inner holder to coordinate the two parallel fetches
        final WeatherModel[] results = new WeatherModel[2]; // [0]=self, [1]=center

        IWeatherRepository.Callback<WeatherModel> selfCallback =
                new IWeatherRepository.Callback<WeatherModel>() {
                    @Override public void onSuccess(WeatherModel result) {
                        results[0] = result;
                        selfMarkerWeather.setValue(UiState.success(result));
                        tryBuildComparison(results);
                    }
                    @Override public void onError(String message) {
                        selfMarkerWeather.setValue(UiState.error(message));
                        comparison.setValue(UiState.error(message));
                    }
                };

        IWeatherRepository.Callback<WeatherModel> centerCallback =
                new IWeatherRepository.Callback<WeatherModel>() {
                    @Override public void onSuccess(WeatherModel result) {
                        results[1] = result;
                        mapCenterWeather.setValue(UiState.success(result));
                        tryBuildComparison(results);
                    }
                    @Override public void onError(String message) {
                        mapCenterWeather.setValue(UiState.error(message));
                        comparison.setValue(UiState.error(message));
                    }
                };

        weatherRepository.getCurrentWeather(selfLat, selfLon, selfCallback);
        weatherRepository.getCurrentWeather(centerLat, centerLon, centerCallback);
    }

    /**
     * Update selected SeekBar hour, computing a human-readable label.
     * Label format: "+NNh  (HH:00)"  e.g. "+06h  (14:00)"
     */
    public void selectHour(int index) {
        selectedHour.setValue(index);
        if (hourlyCache != null && index >= 0 && index < hourlyCache.size()) {
            HourlyEntryModel entry = hourlyCache.get(index);
            String label = String.format("+%02dh  (%02d:00)", index, entry.getHour());
            selectedHourLabel.setValue(label);
        }
    }

    /** Called by the View once hourly data is available, so label can be computed. */
    public void setHourlyCache(List<HourlyEntryModel> entries) {
        this.hourlyCache = entries;
        // Reset to hour 0
        selectHour(0);
    }

    // ── LiveData getters ──────────────────────────────────────────────────────

    public LiveData<UiState<WeatherModel>>             getCurrentWeather()    { return currentWeather; }
    public LiveData<UiState<List<DailyForecastModel>>> getDailyForecast()     { return dailyForecast; }
    public LiveData<UiState<List<HourlyEntryModel>>>   getHourlyForecast()    { return hourlyForecast; }
    public LiveData<String>                            getLocationName()      { return locationName; }
    public LiveData<Integer>                           getSelectedHour()      { return selectedHour; }
    public LiveData<String>                            getSelectedHourLabel() { return selectedHourLabel; }
    public LiveData<String>                            getErrorMessage()      { return errorMessage; }
    public LiveData<UiState<WeatherModel>>             getSelfMarkerWeather() { return selfMarkerWeather; }
    public LiveData<UiState<WeatherModel>>             getMapCenterWeather()  { return mapCenterWeather; }
    public LiveData<UiState<ComparisonModel>>          getComparison()        { return comparison; }

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
                    @Override public void onError(String msg) { hourlyForecast.setValue(UiState.error(msg)); errorMessage.setValue(msg); }
                });
    }

    private void reverseGeocode(double lat, double lon) {
        geocodingRepository.reverseGeocode(lat, lon,
                new IGeocodingRepository.Callback() {
                    @Override public void onSuccess(String name) { locationName.setValue(name); }
                    @Override public void onError(String msg)    { locationName.setValue("Unknown location"); }
                });
    }

    private void tryBuildComparison(WeatherModel[] results) {
        if (results[0] != null && results[1] != null) {
            comparison.setValue(UiState.success(new ComparisonModel(results[0], results[1])));
        }
    }
}
