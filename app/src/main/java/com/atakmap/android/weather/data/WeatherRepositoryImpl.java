package com.atakmap.android.weather.data;

import com.atakmap.android.weather.data.remote.IWeatherRemoteSource;
import com.atakmap.android.weather.domain.model.DailyForecastModel;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.model.WindProfileModel;
import com.atakmap.android.weather.domain.repository.IWeatherRepository;
import com.atakmap.android.weather.infrastructure.preferences.WeatherParameterPreferences;

import java.util.List;
import java.util.Map;

/**
 * Concrete IWeatherRepository.
 *
 * ── Sprint 2 changes ──────────────────────────────────────────────────────────
 *
 * setParameterPreferences(WeatherParameterPreferences)
 *   Passes the user's selection prefs down to the active source so it builds
 *   URLs from preferences. Should be called once during plugin init and again
 *   if the active source is changed at runtime.
 *
 * Sprint 3 will add the Room cache read-through layer here. The interface
 * is unchanged so callers (WeatherViewModel) need no updates.
 */
public class WeatherRepositoryImpl implements IWeatherRepository {

    private static final int DEFAULT_DAYS  = 7;
    private static final int DEFAULT_HOURS = 168;

    private final Map<String, IWeatherRemoteSource> sources;
    private       String                            activeSourceId;

    public WeatherRepositoryImpl(Map<String, IWeatherRemoteSource> sources,
                                 String defaultSource) {
        this.sources        = sources;
        this.activeSourceId = defaultSource;
    }

    // ── Source selection ──────────────────────────────────────────────────────

    public void setActiveSource(String sourceId) {
        if (sources.containsKey(sourceId)) activeSourceId = sourceId;
    }

    public String getActiveSourceId() { return activeSourceId; }

    /**
     * True when the active source has been marked stale by a parameter change.
     * CachingWeatherRepository calls this to decide whether to bypass the cache.
     *
     * Uses the interface's {@code isStale()} default method — no instanceof check
     * required. Works for OpenMeteoSource, ECMWF, DWD, and any future source.
     */
    public boolean isStaleForCurrentSource() {
        IWeatherRemoteSource src = sources.get(activeSourceId);
        return src != null && src.isStale();
    }

    /**
     * Inject user parameter preferences into ALL registered sources.
     * Each source registers as a ChangeListener internally so Tab 4 taps
     * flow through without further plumbing.
     *
     * Previously this only injected into the active source, so switching to
     * ECMWF or DWD would leave prefs un-injected (falling back to hardcoded
     * DEFAULT_HOURLY/DEFAULT_DAILY).
     */
    public void setParameterPreferences(WeatherParameterPreferences prefs) {
        for (IWeatherRemoteSource src : sources.values()) {
            src.setParameterPreferences(prefs);
        }
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

    // ── Private ──────────────────────────────────────────────────────────────

    private IWeatherRemoteSource active() {
        IWeatherRemoteSource src = sources.get(activeSourceId);
        if (src == null) src = sources.values().iterator().next();
        return src;
    }
}
