package com.atakmap.android.weather.data.remote;

import com.atakmap.android.weather.domain.model.DailyForecastModel;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.model.WindProfileModel;
import com.atakmap.android.weather.util.CoordFormatter;
import com.atakmap.android.weather.util.DateUtils;
import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * IWeatherRemoteSource implementation backed by https://api.open-meteo.com
 *
 * Responsible for:
 *  - building the correct API URLs
 *  - HTTP fetch (delegated to HttpClient)
 *  - parsing JSON → domain models
 *
 * Not responsible for: UI, state, caching, geocoding.
 */
public class OpenMeteoSource implements IWeatherRemoteSource {

    private static final String TAG       = "OpenMeteoSource";
    public  static final String SOURCE_ID = "open-meteo";

    // ── Base URL & parameter fragments ──────────────────────────────────────
    private static final String BASE_URL  = "https://api.open-meteo.com/v1/forecast?";

    private static final String PARAM_HOURLY =
            "&hourly=temperature_2m,relativehumidity_2m,apparent_temperature," +
                    "precipitation_probability,weathercode,surface_pressure," +
                    "visibility,windspeed_10m,winddirection_10m";

    private static final String PARAM_DAILY =
            "&daily=weathercode,temperature_2m_max,temperature_2m_min," +
                    "precipitation_sum,precipitation_hours,precipitation_probability_max";

    private static final String PARAM_WIND_PROFILE =
            "&hourly=temperature_2m,windspeed_10m,windspeed_80m," +
                    "windspeed_120m,windspeed_180m,winddirection_10m,winddirection_80m," +
                    "winddirection_120m,winddirection_180m,windgusts_10m," +
                    "temperature_80m,temperature_120m,temperature_180m";

    private static final String PARAM_WIND_UNIT = "&windspeed_unit=ms";
    private static final String PARAM_TIMEZONE  = "&timezone=auto";

    // ────────────────────────────────────────────────────────────────────────

    @Override
    public String getSourceId() { return SOURCE_ID; }

    // ── Interface methods ────────────────────────────────────────────────────

    @Override
    public void fetchCurrentWeather(double lat, double lon,
                                    FetchCallback<WeatherModel> callback) {
        // Current weather is derived from the first entry of daily + hourly
        String url = buildBaseUrl(lat, lon) + PARAM_HOURLY + PARAM_DAILY
                + PARAM_WIND_UNIT + PARAM_TIMEZONE;

        HttpClient.get(url, new HttpClient.Callback() {
            @Override
            public void onSuccess(String body) {
                try {
                    JSONObject root     = new JSONObject(body);
                    JSONObject daily    = root.getJSONObject("daily");
                    JSONObject hourly   = root.getJSONObject("hourly");

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

                    callback.onResult(model);
                } catch (Exception e) {
                    Log.e(TAG, "fetchCurrentWeather parse error", e);
                    callback.onError("Parse error: " + e.getMessage());
                }
            }
            @Override
            public void onFailure(String error) { callback.onError(error); }
        });
    }

    @Override
    public void fetchDailyForecast(double lat, double lon, int days,
                                   FetchCallback<List<DailyForecastModel>> callback) {
        String url = buildBaseUrl(lat, lon) + PARAM_DAILY + PARAM_TIMEZONE;

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
                                // BUG FIX: original always read index [0] for precip
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
            @Override
            public void onFailure(String error) { callback.onError(error); }
        });
    }

    @Override
    public void fetchHourlyForecast(double lat, double lon, int hours,
                                    FetchCallback<List<HourlyEntryModel>> callback) {
        String url = buildBaseUrl(lat, lon) + PARAM_HOURLY + PARAM_WIND_UNIT + PARAM_TIMEZONE;

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

                    int count = Math.min(times.length(), hours);
                    List<HourlyEntryModel> result = new ArrayList<>(count);

                    for (int i = 0; i < count; i++) {
                        String iso = times.getString(i);
                        result.add(new HourlyEntryModel.Builder()
                                .isoTime(iso)
                                .hour(DateUtils.hourFromIso(iso))
                                .temperature(temp.getDouble(i))
                                .apparentTemperature(feel.getDouble(i))
                                .humidity(humid.getDouble(i))
                                .pressure(press.getDouble(i))
                                .visibility(vis.getDouble(i))
                                .windSpeed(wspd.getDouble(i))
                                .windDirection(wdir.getDouble(i))
                                .precipitationProbability(precPct.getDouble(i))
                                .build());
                    }
                    callback.onResult(result);
                } catch (Exception e) {
                    Log.e(TAG, "fetchHourlyForecast parse error", e);
                    callback.onError("Parse error: " + e.getMessage());
                }
            }
            @Override
            public void onFailure(String error) { callback.onError(error); }
        });
    }

    @Override
    public void fetchWindProfile(double lat, double lon,
                                 FetchCallback<List<WindProfileModel>> callback) {
        String url = buildBaseUrl(lat, lon) + PARAM_WIND_PROFILE
                + PARAM_WIND_UNIT + PARAM_TIMEZONE;

        HttpClient.get(url, new HttpClient.Callback() {
            @Override
            public void onSuccess(String body) {
                try {
                    JSONObject root   = new JSONObject(body);
                    JSONObject hourly = root.getJSONObject("hourly");

                    JSONArray times  = hourly.getJSONArray("time");
                    int count = times.length();
                    List<WindProfileModel> result = new ArrayList<>(count);

                    // Altitude columns
                    int[] altitudes = {10, 80, 120, 180};
                    String[] speedKeys = {
                            "windspeed_10m","windspeed_80m","windspeed_120m","windspeed_180m"
                    };
                    String[] dirKeys = {
                            "winddirection_10m","winddirection_80m",
                            "winddirection_120m","winddirection_180m"
                    };
                    String[] tempKeys = {
                            "temperature_2m","temperature_80m","temperature_120m","temperature_180m"
                    };

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
            @Override
            public void onFailure(String error) { callback.onError(error); }
        });
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private static String buildBaseUrl(double lat, double lon) {
        return BASE_URL
                + "latitude="  + CoordFormatter.format(lat)
                + "&longitude=" + CoordFormatter.format(lon);
    }
}
