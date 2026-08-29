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
import com.atakmap.coremap.log.Log;
import com.atakmap.android.weather.domain.repository.FetchCallback;
import com.atakmap.android.weather.domain.repository.IForecastSource;
import com.atakmap.android.weather.domain.repository.IWindProfileSource;

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
    private static final String TAG = "WeatherRepositoryImpl";
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
                new FetchCallback<WeatherModel>() {
                    @Override public void onResult(WeatherModel data) { callback.onSuccess(data); }
                    @Override public void onError(String msg)          { callback.onError(msg); }
                });
    }

    @Override
    public void getDailyForecast(double latitude, double longitude,
                                 Callback<List<DailyForecastModel>> callback) {
        IForecastSource src = forecastProvider();
        if (src == null) {
            callback.onError("No forecast provider is available");
            return;
        }
        src.fetchDailyForecast(latitude, longitude, DEFAULT_DAYS,
                new FetchCallback<List<DailyForecastModel>>() {
                    @Override public void onResult(List<DailyForecastModel> data) { callback.onSuccess(data); }
                    @Override public void onError(String msg)                      { callback.onError(msg); }
                });
    }

    @Override
    public void getHourlyForecast(double latitude, double longitude,
                                  Callback<List<HourlyEntryModel>> callback) {
        IForecastSource src = forecastProvider();
        if (src == null) {
            callback.onError("No forecast provider is available");
            return;
        }
        src.fetchHourlyForecast(latitude, longitude, DEFAULT_HOURS,
                new FetchCallback<List<HourlyEntryModel>>() {
                    @Override public void onResult(List<HourlyEntryModel> data) { callback.onSuccess(data); }
                    @Override public void onError(String msg)                    { callback.onError(msg); }
                });
    }

    @Override
    public void getWindProfile(double latitude, double longitude,
                               Callback<List<WindProfileModel>> callback) {
        IWindProfileSource src = capabilityOrSubstitute(IWindProfileSource.class);
        if (src == null) {
            callback.onError("No wind profile provider is available");
            return;
        }
        src.fetchWindProfile(latitude, longitude,
                new FetchCallback<List<WindProfileModel>>() {
                    @Override public void onResult(List<WindProfileModel> data) { callback.onSuccess(data); }
                    @Override public void onError(String msg)                    { callback.onError(msg); }
                });
    }

    // ── Capability routing (finding F30) ─────────────────────────────────────
    //
    // Not every source can serve every request. The FAA Aviation Weather Center
    // publishes station observations and winds aloft but no gridded forecast, so
    // AviationWeatherSource does not implement IForecastSource.
    //
    // Substitution used to be hidden inside that source: it held a private
    // Open-Meteo instance and delegated the forecast methods to it while the UI
    // went on saying AWC (finding F21). Now the source simply has no forecast
    // method, and the choice of stand-in is made here — once, in the open, and
    // logged. The answering provider is stamped on every model, so the header
    // shows which one produced the numbers.

    /** The active source if it can forecast; otherwise an explicit stand-in. */
    private IForecastSource forecastProvider() {
        return capabilityOrSubstitute(IForecastSource.class);
    }

    /**
     * Return the active source if it has the requested capability, otherwise the
     * first registered source that does.
     *
     * @return null when nothing registered can serve the request, which callers
     *         must surface as an error rather than silently doing nothing
     */
    private <T> T capabilityOrSubstitute(Class<T> capability) {
        IWeatherRemoteSource src = active();
        if (capability.isInstance(src)) return capability.cast(src);

        for (IWeatherRemoteSource candidate : sources.values()) {
            if (capability.isInstance(candidate)) {
                Log.d(TAG, "Source '" + src.getSourceId() + "' cannot serve "
                        + capability.getSimpleName() + "; using '"
                        + candidate.getSourceId() + "'. The reading will name it.");
                return capability.cast(candidate);
            }
        }
        Log.w(TAG, "No registered source provides " + capability.getSimpleName());
        return null;
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private IWeatherRemoteSource active() {
        IWeatherRemoteSource src = sources.get(activeSourceId);
        if (src == null) src = sources.values().iterator().next();
        return src;
    }
}
