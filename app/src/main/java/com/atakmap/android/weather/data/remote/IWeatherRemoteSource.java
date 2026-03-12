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
 * ── Sprint 2 additions ────────────────────────────────────────────────────────
 *
 * getSupportedParameters()
 *   Returns the parameters this source is capable of providing. Used by
 *   ParametersView to populate only the variables the active source supports,
 *   instead of the hardcoded WeatherParameter enum which is Open-Meteo specific.
 *
 * setParameterPreferences(WeatherParameterPreferences)
 *   Injects the user's selection set so the source builds API URLs from prefs
 *   instead of hardcoded constants. Called once from WeatherRepositoryImpl
 *   after construction and again whenever the active source changes.
 */
public interface IWeatherRemoteSource {

    /** Unique identifier used in preferences, e.g. "open-meteo". */
    String getSourceId();

    /**
     * Human-readable name shown in the CONF tab Spinner.
     * Default implementation title-cases the source ID.
     * Override in concrete sources for a friendlier label.
     */
    default String getDisplayName() {
        String id = getSourceId();
        if (id == null || id.isEmpty()) return "Unknown";
        return id.substring(0,1).toUpperCase() + id.substring(1).replace("-"," ");
    }

    /**
     * Returns all WeatherParameters this source can provide.
     * ParametersView calls this to build its section lists, so a different
     * source (e.g. OpenWeatherMap) can declare a different parameter set.
     */
    List<WeatherParameter> getSupportedParameters();

    /**
     * Inject user parameter preferences so URL building reads from prefs
     * rather than hardcoded constants. Must be called before any fetch.
     * A null value resets to source-internal defaults.
     */
    void setParameterPreferences(WeatherParameterPreferences prefs);

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
