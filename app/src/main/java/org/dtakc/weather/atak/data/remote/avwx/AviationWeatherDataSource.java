package org.dtakc.weather.atak.data.remote.avwx;

import org.dtakc.weather.atak.data.preferences.WeatherParameterPreferences;
import org.dtakc.weather.atak.data.remote.IWeatherDataSource;
import org.dtakc.weather.atak.data.remote.openmeteo.OpenMeteoDataSource;
import org.dtakc.weather.atak.domain.model.DailyForecastModel;
import org.dtakc.weather.atak.domain.model.HourlyEntryModel;
import org.dtakc.weather.atak.domain.model.WeatherModel;
import org.dtakc.weather.atak.domain.model.WeatherParameter;
import org.dtakc.weather.atak.domain.model.WindProfileModel;
import org.dtakc.weather.atak.util.HttpClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * FAA Aviation Weather Center (AWC) METAR source.
 * Hourly/daily forecast delegates to Open-Meteo (AWC has no time-series endpoint).
 */
public final class AviationWeatherDataSource implements IWeatherDataSource {

    public static final String SOURCE_ID = "aviation-weather-center";

    private static final String BASE_METAR    = "https://aviationweather.gov/api/data/metar";
    private static final String BASE_WINDTEMP = "https://aviationweather.gov/api/data/windtemp";
    private static final double BBOX_DEG      = 1.5;
    private static final double KT_TO_MS      = 0.514444;
    private static final double SM_TO_M       = 1609.34;

    private static final int[]    ALOFT_ALT_M  = {760, 1500, 3000, 4200};
    private static final String[] ALOFT_WDIR   = {"wdir3000","wdir6000","wdir9000","wdir12000"};
    private static final String[] ALOFT_WSPD   = {"wspd3000","wspd6000","wspd9000","wspd12000"};
    private static final String[] ALOFT_TEMP   = {"temp3000","temp6000","temp9000","temp12000"};

    private final OpenMeteoDataSource fallback = new OpenMeteoDataSource();

    @Override public String getSourceId()    { return SOURCE_ID; }
    @Override public String getDisplayName() { return "AWC METAR (no key)"; }

    @Override
    public List<WeatherParameter> getSupportedParameters() {
        return Collections.unmodifiableList(Arrays.asList(WeatherParameter.values()));
    }

    @Override
    public void setParameterPreferences(WeatherParameterPreferences prefs) {
        fallback.setParameterPreferences(prefs);
    }

    @Override
    public void fetchCurrentWeather(double lat, double lon, FetchCallback<WeatherModel> cb) {
        double minLat = lat - BBOX_DEG, maxLat = lat + BBOX_DEG;
        double minLon = lon - BBOX_DEG, maxLon = lon + BBOX_DEG;
        String url = String.format(Locale.US,
                "%s?format=json&bbox=%.2f,%.2f,%.2f,%.2f&hours=2",
                BASE_METAR, minLat, minLon, maxLat, maxLon);
        try {
            String json = HttpClient.get(url);
            JSONArray arr = new JSONArray(json);
            if (arr.length() == 0) { cb.onError("No METAR stations in bbox"); return; }
            // Pick closest station
            JSONObject best = null; double bestDist = Double.MAX_VALUE;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                double slat = o.optDouble("lat", 0), slon = o.optDouble("lon", 0);
                double d = Math.hypot(slat - lat, slon - lon);
                if (d < bestDist) { bestDist = d; best = o; }
            }
            if (best == null) { cb.onError("Could not select METAR station"); return; }

            String flightCat = deriveFlightCategory(best);
            WeatherModel m = new WeatherModel.Builder(lat, lon)
                    .temperatureMin(best.optDouble("temp", 0))
                    .temperatureMax(best.optDouble("temp", 0))
                    .windSpeed(best.optDouble("wspd", 0) * KT_TO_MS)
                    .windDirection(best.optDouble("wdir", 0))
                    .pressure(best.optDouble("altim", 0))
                    .visibility(parseVisib(best.optString("visib", "10+")) * SM_TO_M)
                    .icaoId(best.optString("icaoId", ""))
                    .flightCategory(flightCat)
                    .rawMetar(best.optString("rawOb", ""))
                    .requestTimestamp(best.optString("reportTime", ""))
                    .build();
            cb.onResult(m);
        } catch (Exception e) { cb.onError("METAR fetch failed: " + e.getMessage()); }
    }

    @Override
    public void fetchDailyForecast(double lat, double lon, int days,
                                   FetchCallback<List<DailyForecastModel>> cb) {
        fallback.fetchDailyForecast(lat, lon, days, cb);
    }

    @Override
    public void fetchHourlyForecast(double lat, double lon, int hours,
                                    FetchCallback<List<HourlyEntryModel>> cb) {
        fallback.fetchHourlyForecast(lat, lon, hours, cb);
    }

    @Override
    public void fetchWindProfile(double lat, double lon, FetchCallback<List<WindProfileModel>> cb) {
        double minLat = lat - BBOX_DEG, maxLat = lat + BBOX_DEG;
        double minLon = lon - BBOX_DEG, maxLon = lon + BBOX_DEG;
        String url = String.format(Locale.US,
                "%s?format=json&level=low&region=%.2f,%.2f,%.2f,%.2f&fcst=06",
                BASE_WINDTEMP, minLat, minLon, maxLat, maxLon);
        try {
            String json = HttpClient.get(url);
            JSONArray arr = new JSONArray(json);
            List<WindProfileModel> profiles = new ArrayList<>();
            if (arr.length() > 0) {
                JSONObject o = arr.getJSONObject(0);
                List<WindProfileModel.AltitudeEntry> alts = new ArrayList<>();
                // Surface tier from METAR if available
                for (int i = 0; i < ALOFT_ALT_M.length; i++) {
                    double speed = o.optDouble(ALOFT_WSPD[i], 0) * KT_TO_MS;
                    double dir   = o.optDouble(ALOFT_WDIR[i], 0);
                    double temp  = o.optDouble(ALOFT_TEMP[i], 0);
                    alts.add(new WindProfileModel.AltitudeEntry(ALOFT_ALT_M[i], speed, dir, temp, 0.0));
                }
                profiles.add(new WindProfileModel(o.optString("validTime", ""), alts));
            }
            cb.onResult(profiles);
        } catch (Exception e) { cb.onError("Windtemp fetch failed: " + e.getMessage()); }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static double parseVisib(String s) {
        if (s == null || s.startsWith("10")) return 10.0;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return 10.0; }
    }

    private static String deriveFlightCategory(JSONObject m) {
        double visibSm = parseVisib(m.optString("visib", "10+"));
        int ceilingFt = Integer.MAX_VALUE;
        JSONArray clouds = m.optJSONArray("clouds");
        if (clouds != null) {
            for (int i = 0; i < clouds.length(); i++) {
                JSONObject c = clouds.optJSONObject(i);
                if (c == null) continue;
                String cov = c.optString("cover", "");
                if (cov.equals("BKN") || cov.equals("OVC")) {
                    ceilingFt = Math.min(ceilingFt, c.optInt("base", Integer.MAX_VALUE));
                }
            }
        }
        if (visibSm >= 5 && ceilingFt >= 3000) return "VFR";
        if (visibSm >= 3 && ceilingFt >= 1000) return "MVFR";
        if (visibSm >= 1 && ceilingFt >= 500)  return "IFR";
        return "LIFR";
    }
}
