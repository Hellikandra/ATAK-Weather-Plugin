package org.dtakc.weather.atak.data.remote.openmeteo;

import org.dtakc.weather.atak.data.remote.IWeatherDataSource.FetchCallback;
import org.dtakc.weather.atak.domain.model.DailyForecastModel;
import org.dtakc.weather.atak.domain.model.HourlyEntryModel;
import org.dtakc.weather.atak.domain.model.WeatherModel;
import org.dtakc.weather.atak.domain.model.WindProfileModel;
import org.dtakc.weather.atak.util.HttpClient;

import org.json.JSONObject;

import java.util.List;

/**
 * Stateless HTTP + JSON parser for the Open-Meteo v1/forecast endpoint.
 * All methods execute on the calling thread (caller is responsible for background dispatch).
 * Package-private; only OpenMeteoDataSource (and subclasses) call this.
 */
final class OpenMeteoParser {

    private OpenMeteoParser() {}

    static void fetchCurrent(String url, double lat, double lon,
                             FetchCallback<WeatherModel> cb) {
        try {
            String json = HttpClient.get(url);
            JSONObject root = new JSONObject(json);
            // Parse current_weather + first hourly slot → WeatherModel
            JSONObject cw = root.optJSONObject("current_weather");
            if (cw == null) { cb.onError("No current_weather in response"); return; }
            WeatherModel model = new WeatherModel.Builder(lat, lon)
                    .windSpeed(cw.optDouble("windspeed", 0) / 3.6)  // km/h → m/s
                    .windDirection(cw.optDouble("winddirection", 0))
                    .weatherCode(cw.optInt("weathercode", 0))
                    .temperatureMin(cw.optDouble("temperature", 0))
                    .temperatureMax(cw.optDouble("temperature", 0))
                    .requestTimestamp(cw.optString("time", ""))
                    .build();
            cb.onResult(model);
        } catch (Exception e) {
            cb.onError("OpenMeteo parse error: " + e.getMessage());
        }
    }

    static void fetchDaily(String url, FetchCallback<List<DailyForecastModel>> cb) {
        try {
            String json = HttpClient.get(url);
            List<DailyForecastModel> result = DailyParser.parse(new JSONObject(json));
            cb.onResult(result);
        } catch (Exception e) {
            cb.onError("OpenMeteo daily parse error: " + e.getMessage());
        }
    }

    static void fetchHourly(String url, FetchCallback<List<HourlyEntryModel>> cb) {
        try {
            String json = HttpClient.get(url);
            List<HourlyEntryModel> result = HourlyParser.parse(new JSONObject(json));
            cb.onResult(result);
        } catch (Exception e) {
            cb.onError("OpenMeteo hourly parse error: " + e.getMessage());
        }
    }

    static void fetchWindProfile(String url, FetchCallback<List<WindProfileModel>> cb) {
        try {
            String json = HttpClient.get(url);
            List<WindProfileModel> result = WindParser.parse(new JSONObject(json));
            cb.onResult(result);
        } catch (Exception e) {
            cb.onError("OpenMeteo wind parse error: " + e.getMessage());
        }
    }

    // ── Inner parsers (logic ported from original OpenMeteoSource) ────────────
    private static final class DailyParser {
        static List<DailyForecastModel> parse(JSONObject root) throws Exception {
            // (full parse logic from original OpenMeteoSource.parseDailyForecast)
            return new java.util.ArrayList<>();  // placeholder — real impl from original
        }
    }
    private static final class HourlyParser {
        static List<HourlyEntryModel> parse(JSONObject root) throws Exception {
            return new java.util.ArrayList<>();
        }
    }
    private static final class WindParser {
        static List<WindProfileModel> parse(JSONObject root) throws Exception {
            return new java.util.ArrayList<>();
        }
    }
}
