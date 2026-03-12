package com.atakmap.android.weather.data.remote;

import com.atakmap.android.weather.domain.model.DailyForecastModel;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.model.WeatherParameter;
import com.atakmap.android.weather.domain.model.WindProfileModel;
import com.atakmap.android.weather.infrastructure.preferences.WeatherParameterPreferences;
import com.atakmap.android.weather.util.CoordFormatter;
import com.atakmap.android.weather.util.DateUtils;
import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * IWeatherRemoteSource implementation backed by https://api.open-meteo.com
 *
 * ── Sprint 2 changes ──────────────────────────────────────────────────────────
 *
 * 1. getSupportedParameters()
 *    Returns the full WeatherParameter enum (all Open-Meteo variables).
 *    ParametersView will use this list instead of iterating the entire enum,
 *    so a future source can declare a smaller subset.
 *
 * 2. setParameterPreferences(WeatherParameterPreferences)
 *    Injects user prefs. When set, fetchCurrentWeather / fetchDailyForecast /
 *    fetchHourlyForecast build their URLs from prefs.buildXxxQueryParam()
 *    instead of hardcoded constants.
 *    Registers as a ChangeListener so stale flag is set on every tap in Tab 4.
 *
 * 3. Hardcoded PARAM_HOURLY / PARAM_DAILY removed from weather fetches.
 *    PARAM_WIND_PROFILE stays hardcoded — wind variables are not user-selectable
 *    (they are always required for the multi-altitude table).
 *
 * 4. Stale flag
 *    When the user changes parameters, isStale = true. WeatherRepositoryImpl
 *    reads this before a cached fetch to decide whether to bypass the cache
 *    (Sprint 3). For now it is exposed so the Receiver can re-trigger a load.
 */
public class OpenMeteoSource implements IWeatherRemoteSource,
        WeatherParameterPreferences.ChangeListener {

    private static final String TAG       = "OpenMeteoSource";
    public  static final String SOURCE_ID = "open-meteo";

    private static final String BASE_URL = "https://api.open-meteo.com/v1/forecast?";

    // Wind profile params are fixed — not user-selectable
    private static final String PARAM_WIND_PROFILE =
            "&hourly=temperature_2m,windspeed_10m,windspeed_80m," +
                    "windspeed_120m,windspeed_180m,winddirection_10m,winddirection_80m," +
                    "winddirection_120m,winddirection_180m,windgusts_10m," +
                    "temperature_80m,temperature_120m,temperature_180m";

    private static final String PARAM_WIND_UNIT = "&windspeed_unit=ms";
    private static final String PARAM_TIMEZONE  = "&timezone=auto";

    // Fallback param strings used when no prefs are injected
    private static final String DEFAULT_HOURLY =
            "&hourly=temperature_2m,relativehumidity_2m,apparent_temperature," +
                    "precipitation_probability,weathercode,surface_pressure," +
                    "visibility,windspeed_10m,winddirection_10m";

    private static final String DEFAULT_DAILY =
            "&daily=weathercode,temperature_2m_max,temperature_2m_min," +
                    "precipitation_sum,precipitation_hours,precipitation_probability_max";

    // ── State ─────────────────────────────────────────────────────────────────

    private WeatherParameterPreferences paramPrefs = null;
    private volatile boolean isStale = false;

    // ── IWeatherRemoteSource ──────────────────────────────────────────────────

    @Override
    public String getSourceId()   { return SOURCE_ID; }
    @Override
    public String getDisplayName() { return "Open-Meteo (free, no key)"; }

    @Override
    public List<WeatherParameter> getSupportedParameters() {
        // Open-Meteo supports the full enum
        return Collections.unmodifiableList(Arrays.asList(WeatherParameter.values()));
    }

    @Override
    public void setParameterPreferences(WeatherParameterPreferences prefs) {
        if (this.paramPrefs != null) {
            this.paramPrefs.unregisterChangeListener(this);
        }
        this.paramPrefs = prefs;
        if (prefs != null) {
            prefs.registerChangeListener(this);
        }
        isStale = false;
    }

    /** True when the user has changed parameter selections since the last fetch. */
    public boolean isStale() { return isStale; }

    /** Called by WeatherRepositoryImpl (or Receiver) after a successful fetch. */
    public void clearStale() { isStale = false; }

    // ── WeatherParameterPreferences.ChangeListener ────────────────────────────

    @Override
    public void onParameterSelectionChanged() {
        isStale = true;
        Log.d(TAG, "Parameter selection changed — source marked stale");
    }

    // ── Fetch methods ─────────────────────────────────────────────────────────

    @Override
    public void fetchCurrentWeather(double lat, double lon,
                                    FetchCallback<WeatherModel> callback) {
        String hourlyParam = paramPrefs != null
                ? paramPrefs.buildHourlyQueryParam() : DEFAULT_HOURLY;
        String dailyParam  = paramPrefs != null
                ? paramPrefs.buildDailyQueryParam()  : DEFAULT_DAILY;

        String url = buildBaseUrl(lat, lon)
                + hourlyParam + dailyParam
                + PARAM_WIND_UNIT + PARAM_TIMEZONE;

        HttpClient.get(url, new HttpClient.Callback() {
            @Override
            public void onSuccess(String body) {
                try {
                    JSONObject root   = new JSONObject(body);
                    JSONObject daily  = root.getJSONObject("daily");
                    JSONObject hourly = root.getJSONObject("hourly");

                    WeatherModel model = new WeatherModel.Builder(
                            root.getDouble("latitude"),
                            root.getDouble("longitude"))
                            .weatherCode(daily.getJSONArray("weathercode").getInt(0))
                            .temperatureMax(daily.getJSONArray("temperature_2m_max").getDouble(0))
                            .temperatureMin(daily.getJSONArray("temperature_2m_min").getDouble(0))
                            .precipitationSum(daily.getJSONArray("precipitation_sum").getDouble(0))
                            .precipitationHours(daily.getJSONArray("precipitation_hours").getDouble(0))
                            .apparentTemperature(hourly.getJSONArray("apparent_temperature").getDouble(0))
                            .humidity(hourly.getJSONArray("relativehumidity_2m").getDouble(0))
                            .pressure(hourly.getJSONArray("surface_pressure").getDouble(0))
                            .visibility(hourly.getJSONArray("visibility").getDouble(0))
                            .windSpeed(hourly.getJSONArray("windspeed_10m").getDouble(0))
                            .windDirection(hourly.getJSONArray("winddirection_10m").getDouble(0))
                            .requestTimestamp(DateUtils.nowFormatted())
                            .build();

                    isStale = false;
                    callback.onResult(model);
                } catch (Exception e) {
                    Log.e(TAG, "fetchCurrentWeather parse error", e);
                    callback.onError("Parse error: " + e.getMessage());
                }
            }
            @Override public void onFailure(String error) { callback.onError(error); }
        });
    }

    @Override
    public void fetchDailyForecast(double lat, double lon, int days,
                                   FetchCallback<List<DailyForecastModel>> callback) {
        String dailyParam = paramPrefs != null
                ? paramPrefs.buildDailyQueryParam() : DEFAULT_DAILY;

        String url = buildBaseUrl(lat, lon) + dailyParam + PARAM_TIMEZONE;

        HttpClient.get(url, new HttpClient.Callback() {
            @Override
            public void onSuccess(String body) {
                try {
                    JSONObject root  = new JSONObject(body);
                    JSONObject daily = root.getJSONObject("daily");

                    JSONArray times   = daily.getJSONArray("time");
                    JSONArray wCodes  = daily.getJSONArray("weathercode");
                    JSONArray maxT    = daily.getJSONArray("temperature_2m_max");
                    JSONArray minT    = daily.getJSONArray("temperature_2m_min");
                    JSONArray precSum = daily.getJSONArray("precipitation_sum");
                    JSONArray precHrs = daily.getJSONArray("precipitation_hours");
                    JSONArray precPct = daily.getJSONArray("precipitation_probability_max");

                    int count = Math.min(times.length(), days);
                    List<DailyForecastModel> result = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        String dateStr = times.getString(i);
                        result.add(new DailyForecastModel.Builder()
                                .date(dateStr)
                                .dayLabel(DateUtils.dayLabel(dateStr, i == 0))
                                .weatherCode(wCodes.getInt(i))
                                .temperatureMax(maxT.getDouble(i))
                                .temperatureMin(minT.getDouble(i))
                                .precipitationSum(precSum.getDouble(i))
                                .precipitationHours(precHrs.getDouble(i))
                                .precipitationProbabilityMax(precPct.getDouble(i))
                                .build());
                    }
                    callback.onResult(result);
                } catch (Exception e) {
                    Log.e(TAG, "fetchDailyForecast parse error", e);
                    callback.onError("Parse error: " + e.getMessage());
                }
            }
            @Override public void onFailure(String error) { callback.onError(error); }
        });
    }

    @Override
    public void fetchHourlyForecast(double lat, double lon, int hours,
                                    FetchCallback<List<HourlyEntryModel>> callback) {
        String hourlyParam = paramPrefs != null
                ? paramPrefs.buildHourlyQueryParam() : DEFAULT_HOURLY;

        String url = buildBaseUrl(lat, lon)
                + hourlyParam + PARAM_WIND_UNIT + PARAM_TIMEZONE;

        HttpClient.get(url, new HttpClient.Callback() {
            @Override
            public void onSuccess(String body) {
                try {
                    JSONObject root   = new JSONObject(body);
                    JSONObject hourly = root.getJSONObject("hourly");

                    JSONArray times   = hourly.getJSONArray("time");
                    JSONArray temp    = hourly.getJSONArray("temperature_2m");
                    JSONArray feel    = hourly.getJSONArray("apparent_temperature");
                    JSONArray humid   = hourly.getJSONArray("relativehumidity_2m");
                    JSONArray press   = hourly.getJSONArray("surface_pressure");
                    JSONArray vis     = hourly.getJSONArray("visibility");
                    JSONArray wspd    = hourly.getJSONArray("windspeed_10m");
                    JSONArray wdir    = hourly.getJSONArray("winddirection_10m");
                    JSONArray precPct = hourly.getJSONArray("precipitation_probability");

                    // Optional field — present only if user selected it
                    JSONArray wcode   = hourly.optJSONArray("weathercode");

                    int count = Math.min(times.length(), hours);
                    List<HourlyEntryModel> result = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        String iso = times.getString(i);
                        HourlyEntryModel.Builder b = new HourlyEntryModel.Builder()
                                .isoTime(iso)
                                .hour(DateUtils.hourFromIso(iso))
                                .temperature(temp.getDouble(i))
                                .apparentTemperature(feel.getDouble(i))
                                .humidity(humid.getDouble(i))
                                .pressure(press.getDouble(i))
                                .visibility(vis.getDouble(i))
                                .windSpeed(wspd.getDouble(i))
                                .windDirection(wdir.getDouble(i))
                                .precipitationProbability(precPct.getDouble(i));
                        if (wcode != null) b.weatherCode(wcode.getInt(i));
                        result.add(b.build());
                    }
                    callback.onResult(result);
                } catch (Exception e) {
                    Log.e(TAG, "fetchHourlyForecast parse error", e);
                    callback.onError("Parse error: " + e.getMessage());
                }
            }
            @Override public void onFailure(String error) { callback.onError(error); }
        });
    }

    @Override
    public void fetchWindProfile(double lat, double lon,
                                 FetchCallback<List<WindProfileModel>> callback) {
        // Wind profile always uses fixed params — not affected by user prefs
        String url = buildBaseUrl(lat, lon)
                + PARAM_WIND_PROFILE + PARAM_WIND_UNIT + PARAM_TIMEZONE;

        HttpClient.get(url, new HttpClient.Callback() {
            @Override
            public void onSuccess(String body) {
                try {
                    JSONObject root   = new JSONObject(body);
                    JSONObject hourly = root.getJSONObject("hourly");
                    JSONArray  times  = hourly.getJSONArray("time");
                    int count = times.length();
                    List<WindProfileModel> result = new ArrayList<>(count);

                    int[]    altitudes = {10, 80, 120, 180};
                    String[] speedKeys = {"windspeed_10m","windspeed_80m","windspeed_120m","windspeed_180m"};
                    String[] dirKeys   = {"winddirection_10m","winddirection_80m","winddirection_120m","winddirection_180m"};
                    String[] tempKeys  = {"temperature_2m","temperature_80m","temperature_120m","temperature_180m"};

                    for (int i = 0; i < count; i++) {
                        List<WindProfileModel.AltitudeEntry> entries = new ArrayList<>();
                        for (int a = 0; a < altitudes.length; a++) {
                            double gusts = a == 0
                                    ? hourly.getJSONArray("windgusts_10m").getDouble(i) : 0;
                            entries.add(new WindProfileModel.AltitudeEntry(
                                    altitudes[a],
                                    hourly.getJSONArray(speedKeys[a]).getDouble(i),
                                    hourly.getJSONArray(dirKeys[a]).getDouble(i),
                                    hourly.getJSONArray(tempKeys[a]).getDouble(i),
                                    gusts));
                        }
                        result.add(new WindProfileModel(times.getString(i), entries));
                    }
                    callback.onResult(result);
                } catch (Exception e) {
                    Log.e(TAG, "fetchWindProfile parse error", e);
                    callback.onError("Parse error: " + e.getMessage());
                }
            }
            @Override public void onFailure(String error) { callback.onError(error); }
        });
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private static String buildBaseUrl(double lat, double lon) {
        return BASE_URL
                + "latitude="  + CoordFormatter.format(lat)
                + "&longitude=" + CoordFormatter.format(lon);
    }
}
