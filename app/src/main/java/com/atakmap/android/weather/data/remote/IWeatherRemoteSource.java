package com.atakmap.android.weather.data.remote;

import com.atakmap.android.weather.domain.model.DailyForecastModel;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.model.WeatherParameter;
import com.atakmap.android.weather.domain.model.WindProfileModel;
import com.atakmap.android.weather.infrastructure.preferences.WeatherParameterPreferences;

import java.util.List;
import com.atakmap.android.weather.domain.repository.FetchCallback;
import com.atakmap.android.weather.domain.repository.ICurrentWeatherSource;

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
public interface IWeatherRemoteSource extends ICurrentWeatherSource {

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
     * Plain-language note about anything this source does not serve itself.
     *
     * <p>Returning null — the default — asserts that everything the source
     * returns is its own data. A source that delegates part of the interface to
     * a different provider <b>must</b> override this and say so, because the
     * user picked this source by name and has no other way to find out.
     * Finding F21.
     *
     * <p>This is a stopgap. The real fix is interface segregation (F30): a
     * source that cannot serve forecasts should not implement a forecast
     * method at all. Until then, this at least makes the substitution visible.
     */
    default String getProviderNotice() { return null; }

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


    // Current weather comes from ICurrentWeatherSource, which every source can
    // satisfy. Forecast and wind-profile are capabilities a source opts into by
    // implementing IForecastSource / IWindProfileSource — they are deliberately
    // NOT declared here.
    //
    // That is the whole point of finding F30. While all four fetches lived on
    // one interface, a source that could not serve forecasts still had to
    // implement the method, and AviationWeatherSource "implemented" it by
    // delegating to a private Open-Meteo instance while the UI went on saying
    // AWC (finding F21). A source that cannot forecast now simply has no
    // forecast method, and the repository decides — visibly, in one place —
    // what to do about it.
}
