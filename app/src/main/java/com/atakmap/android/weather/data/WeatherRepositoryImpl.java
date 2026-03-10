package com.atakmap.android.weather.data;

import com.atakmap.android.weather.data.remote.IWeatherRemoteSource;
import com.atakmap.android.weather.domain.model.DailyForecastModel;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.model.WindProfileModel;
import com.atakmap.android.weather.domain.repository.IWeatherRepository;

import java.util.List;
import java.util.Map;

/**
 * Concrete IWeatherRepository.
 *
 * Orchestrates:
 *  - active remote source selection (swappable via preferences)
 *  - (future) local cache read-through logic
 *
 * To add a new API provider: implement IWeatherRemoteSource and register
 * it in WeatherModule — no changes needed here.
 */
public class WeatherRepositoryImpl implements IWeatherRepository {

    private static final int DEFAULT_DAYS  = 7;
    private static final int DEFAULT_HOURS = 168; // 7 days × 24h

    private final Map<String, IWeatherRemoteSource> sources;
    private       String                            activeSourceId;

    /**
     * @param sources       map of sourceId → implementation
     * @param defaultSource id of the source to use initially
     */
    public WeatherRepositoryImpl(Map<String, IWeatherRemoteSource> sources,
                                 String defaultSource) {
        this.sources        = sources;
        this.activeSourceId = defaultSource;
    }

    // ── Source selection ─────────────────────────────────────────────────────

    /** Switch the active API provider at runtime (e.g. from Preferences). */
    public void setActiveSource(String sourceId) {
        if (sources.containsKey(sourceId)) {
            activeSourceId = sourceId;
        }
    }

    public String getActiveSourceId() { return activeSourceId; }

    private IWeatherRemoteSource active() {
        IWeatherRemoteSource src = sources.get(activeSourceId);
        if (src == null) {
            // Fallback to first available
            src = sources.values().iterator().next();
        }
        return src;
    }

    // ── IWeatherRepository ───────────────────────────────────────────────────

    @Override
    public void getCurrentWeather(double latitude, double longitude,
                                  Callback<WeatherModel> callback) {
        active().fetchCurrentWeather(latitude, longitude,
                new IWeatherRemoteSource.FetchCallback<WeatherModel>() {
                    @Override public void onResult(WeatherModel data) { callback.onSuccess(data); }
                    @Override public void onError(String msg)          { callback.onError(msg); }
                });
    }

    @Override
    public void getDailyForecast(double latitude, double longitude,
                                 Callback<List<DailyForecastModel>> callback) {
        active().fetchDailyForecast(latitude, longitude, DEFAULT_DAYS,
                new IWeatherRemoteSource.FetchCallback<List<DailyForecastModel>>() {
                    @Override public void onResult(List<DailyForecastModel> data) { callback.onSuccess(data); }
                    @Override public void onError(String msg)                      { callback.onError(msg); }
                });
    }

    @Override
    public void getHourlyForecast(double latitude, double longitude,
                                  Callback<List<HourlyEntryModel>> callback) {
        active().fetchHourlyForecast(latitude, longitude, DEFAULT_HOURS,
                new IWeatherRemoteSource.FetchCallback<List<HourlyEntryModel>>() {
                    @Override public void onResult(List<HourlyEntryModel> data) { callback.onSuccess(data); }
                    @Override public void onError(String msg)                    { callback.onError(msg); }
                });
    }

    @Override
    public void getWindProfile(double latitude, double longitude,
                               Callback<List<WindProfileModel>> callback) {
        active().fetchWindProfile(latitude, longitude,
                new IWeatherRemoteSource.FetchCallback<List<WindProfileModel>>() {
                    @Override public void onResult(List<WindProfileModel> data) { callback.onSuccess(data); }
                    @Override public void onError(String msg)                    { callback.onError(msg); }
                });
    }
}
