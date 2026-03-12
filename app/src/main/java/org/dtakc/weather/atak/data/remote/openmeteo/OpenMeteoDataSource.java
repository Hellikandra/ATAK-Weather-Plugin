package org.dtakc.weather.atak.data.remote.openmeteo;

import org.dtakc.weather.atak.data.preferences.WeatherParameterPreferences;
import org.dtakc.weather.atak.data.remote.IWeatherDataSource;
import org.dtakc.weather.atak.domain.model.DailyForecastModel;
import org.dtakc.weather.atak.domain.model.HourlyEntryModel;
import org.dtakc.weather.atak.domain.model.WeatherModel;
import org.dtakc.weather.atak.domain.model.WeatherParameter;
import org.dtakc.weather.atak.domain.model.WindProfileModel;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean; // ISS-08 fix

/**
 * IWeatherDataSource backed by Open-Meteo (GFS global model).
 * ISS-08: isStale is AtomicBoolean — thread-safe reads/writes from any thread.
 */
public class OpenMeteoDataSource implements IWeatherDataSource,
        WeatherParameterPreferences.ChangeListener {

    public static final String SOURCE_ID = "open-meteo";
    private static final String BASE_URL = "https://api.open-meteo.com/v1/forecast?";

    private static final String PARAM_WIND_PROFILE =
            "&hourly=temperature_2m,windspeed_10m,windspeed_80m," +
            "windspeed_120m,windspeed_180m,winddirection_10m,winddirection_80m," +
            "winddirection_120m,winddirection_180m,windgusts_10m," +
            "temperature_80m,temperature_120m,temperature_180m";
    private static final String PARAM_WIND_UNIT = "&windspeed_unit=ms";
    private static final String PARAM_TIMEZONE  = "&timezone=auto";
    private static final String DEFAULT_HOURLY  =
            "&hourly=temperature_2m,relativehumidity_2m,apparent_temperature," +
            "precipitation_probability,weathercode,surface_pressure," +
            "visibility,windspeed_10m,winddirection_10m";
    private static final String DEFAULT_DAILY   =
            "&daily=weathercode,temperature_2m_max,temperature_2m_min," +
            "precipitation_sum,precipitation_hours,precipitation_probability_max";

    // ISS-08 fix: AtomicBoolean replaces volatile boolean
    private final AtomicBoolean isStale = new AtomicBoolean(false);
    private WeatherParameterPreferences paramPrefs;

    @Override public String getSourceId()    { return SOURCE_ID; }
    @Override public String getDisplayName() { return "Open-Meteo (free, no key)"; }

    @Override
    public List<WeatherParameter> getSupportedParameters() {
        return Collections.unmodifiableList(Arrays.asList(WeatherParameter.values()));
    }

    @Override
    public void setParameterPreferences(WeatherParameterPreferences prefs) {
        if (this.paramPrefs != null) this.paramPrefs.unregisterChangeListener(this);
        this.paramPrefs = prefs;
        if (prefs != null) prefs.registerChangeListener(this);
        isStale.set(false);
    }

    /** Thread-safe stale check (ISS-08). */
    public boolean isStale() { return isStale.get(); }

    /** Reset stale flag after a successful fetch. */
    public void clearStale() { isStale.set(false); }

    @Override public void onParameterSelectionChanged() { isStale.set(true); }

    // ── Fetch methods (delegate to shared HTTP + JSON parse helpers) ──────────

    @Override
    public void fetchCurrentWeather(double lat, double lon, FetchCallback<WeatherModel> cb) {
        String url = BASE_URL + "latitude=" + lat + "&longitude=" + lon
                + currentParams() + PARAM_WIND_UNIT + PARAM_TIMEZONE;
        OpenMeteoParser.fetchCurrent(url, lat, lon, cb);
    }

    @Override
    public void fetchDailyForecast(double lat, double lon, int days,
                                   FetchCallback<List<DailyForecastModel>> cb) {
        String url = BASE_URL + "latitude=" + lat + "&longitude=" + lon
                + dailyParams() + PARAM_TIMEZONE;
        OpenMeteoParser.fetchDaily(url, cb);
    }

    @Override
    public void fetchHourlyForecast(double lat, double lon, int hours,
                                    FetchCallback<List<HourlyEntryModel>> cb) {
        String url = BASE_URL + "latitude=" + lat + "&longitude=" + lon
                + hourlyParams() + PARAM_WIND_UNIT + PARAM_TIMEZONE + "&forecast_hours=" + hours;
        OpenMeteoParser.fetchHourly(url, cb);
    }

    @Override
    public void fetchWindProfile(double lat, double lon, FetchCallback<List<WindProfileModel>> cb) {
        String url = BASE_URL + "latitude=" + lat + "&longitude=" + lon
                + PARAM_WIND_PROFILE + PARAM_WIND_UNIT + PARAM_TIMEZONE;
        OpenMeteoParser.fetchWindProfile(url, cb);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    protected String model() { return ""; }  // subclasses override for ECMWF/DWD

    private String currentParams() {
        if (paramPrefs != null) {
            String h = paramPrefs.buildHourlyQueryParam();
            return h.isEmpty() ? DEFAULT_HOURLY : h;
        }
        return DEFAULT_HOURLY;
    }

    private String dailyParams() {
        if (paramPrefs != null) {
            String d = paramPrefs.buildDailyQueryParam();
            return d.isEmpty() ? DEFAULT_DAILY : d;
        }
        return DEFAULT_DAILY;
    }

    private String hourlyParams() { return currentParams(); }
}
