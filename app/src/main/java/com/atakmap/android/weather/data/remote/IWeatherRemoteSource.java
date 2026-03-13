package com.atakmap.android.weather.data.remote;

import com.atakmap.android.weather.domain.model.DailyForecastModel;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.model.WeatherParameter;
import com.atakmap.android.weather.domain.model.WindProfileModel;
import com.atakmap.android.weather.infrastructure.preferences.WeatherParameterPreferences;

import java.util.List;

/**
 * Pluggable API source contract.
 *
 * REFACTORING CHANGE: Added {@code isStale()} default method.
 *
 * Previously, WeatherRepositoryImpl.isStaleForCurrentSource() contained an
 * {@code instanceof OpenMeteoSource} check to reach the stale flag — tying the
 * repository layer to a concrete implementation detail.
 *
 * The new default method returns {@code false} for all sources that don't
 * declare themselves stale. OpenMeteoSource overrides it to return its
 * internal volatile boolean. WeatherRepositoryImpl calls {@code active().isStale()}
 * without any instanceof cast.
 */
public interface IWeatherRemoteSource {

    /** Unique identifier used in preferences, e.g. "open-meteo". */
    String getSourceId();

    /**
     * Human-readable name shown in the CONF tab Spinner.
     * Default implementation title-cases the source ID.
     */
    default String getDisplayName() {
        String id = getSourceId();
        if (id == null || id.isEmpty()) return "Unknown";
        return id.substring(0,1).toUpperCase() + id.substring(1).replace("-"," ");
    }

    /**
     * Returns all WeatherParameters this source can provide.
     * ParametersView calls this to build its section lists.
     */
    List<WeatherParameter> getSupportedParameters();

    /**
     * Inject user parameter preferences so URL building reads from prefs
     * rather than hardcoded constants. Must be called before any fetch.
     * A null value resets to source-internal defaults.
     */
    void setParameterPreferences(WeatherParameterPreferences prefs);

    /**
     * Returns true when the user has changed parameter selections since the
     * last successful fetch and the cache should be bypassed.
     *
     * Default: false (most sources are not parameter-selectable).
     * OpenMeteoSource overrides this.
     *
     * REFACTORING: Replaces the {@code instanceof OpenMeteoSource} check in
     * WeatherRepositoryImpl.isStaleForCurrentSource(). The repository can now
     * call {@code active().isStale()} without knowing the concrete type.
     */
    default boolean isStale() {
        return false;
    }

    // ── Fetch callbacks ───────────────────────────────────────────────────────

    interface FetchCallback<T> {
        void onResult(T data);
        void onError(String message);
    }

    void fetchCurrentWeather(double lat, double lon,
                             FetchCallback<WeatherModel> callback);

    void fetchDailyForecast(double lat, double lon, int days,
                            FetchCallback<List<DailyForecastModel>> callback);

    void fetchHourlyForecast(double lat, double lon, int hours,
                             FetchCallback<List<HourlyEntryModel>> callback);

    void fetchWindProfile(double lat, double lon,
                          FetchCallback<List<WindProfileModel>> callback);
}
