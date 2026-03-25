package com.atakmap.android.weather.data.remote;

import com.atakmap.android.weather.domain.model.DailyForecastModel;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.model.WeatherParameter;
import com.atakmap.android.weather.domain.model.WindProfileModel;
import com.atakmap.android.weather.infrastructure.preferences.WeatherParameterPreferences;
import com.atakmap.android.weather.util.DateUtils;
import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * IWeatherRemoteSource backed by the FAA Aviation Weather Center (AWC) REST API.
 *
 * ── Data endpoints used ───────────────────────────────────────────────────────
 *
 *  Current / wind:
 *    GET https://aviationweather.gov/api/data/metar
 *        ?format=json&bbox=<minLat>,<minLon>,<maxLat>,<maxLon>&hours=2
 *
 *    Returns a JSON array of METAR station objects. We pick the one closest
 *    to the requested coordinate. Key fields used (all confirmed from AWC docs):
 *      temp   (°C), dewp (°C), wdir (°true), wspd (kt), wgst (kt, nullable),
 *      visib  ("10+" or numeric statute miles), altim (hPa),
 *      lat, lon, name (station name), clouds (array of cover/base objects)
 *
 *    Wind speed in knots is converted to m/s (÷ 1.94384).
 *    Visibility in statute miles is converted to metres (× 1609.34).
 *
 *  Wind aloft (upper-air wind profile):
 *    GET https://aviationweather.gov/api/data/windtemp
 *        ?format=json&level=low&region=<bbox>&fcst=06
 *
 *    Returns an array of wind-aloft forecast objects. Key fields:
 *      wdir3000, wspd3000  (925 hPa ≈ 760 m)
 *      wdir6000, wspd6000  (850 hPa ≈ 1500 m)
 *      wdir9000, wspd9000  (700 hPa ≈ 3000 m)
 *      wdir12000,wspd12000 (600 hPa ≈ 4200 m)
 *      temp3000, temp6000, temp9000, temp12000
 *
 * ── Hourly / Daily fallback ───────────────────────────────────────────────────
 *
 * AWC METARs are point observations, not gridded time-series, so there is no
 * equivalent to Open-Meteo's 168-hour hourly forecast.  To maintain full plugin
 * functionality when this source is active, fetchHourlyForecast() and
 * fetchDailyForecast() delegate to an internal OpenMeteoSource instance.
 * This is intentional and documented here so future developers are not confused.
 *
 * ── No API key required ───────────────────────────────────────────────────────
 *
 * The AWC Data API is a free, unauthenticated NOAA/FAA government service.
 * No registration or key is needed. The User-Agent header is set as courtesy.
 *
 * ── TAK operational value ─────────────────────────────────────────────────────
 *
 * METAR data is the primary authoritative surface observation used in aviation
 * weather briefings.  Selecting this source gives operators the same wind,
 * visibility, and pressure values that pilots and ATC use, rather than a model
 * forecast.  It is most accurate near airports or military airfields that have
 * an ASOS/AWOS station within the query bbox.
 */
public class AviationWeatherSource implements IWeatherRemoteSource {

    private static final String TAG = "AviationWeatherSource";

    public static final String SOURCE_ID = "aviation-weather-center";

    // ── Base URLs ─────────────────────────────────────────────────────────────
    private static final String BASE_METAR   = "https://aviationweather.gov/api/data/metar";
    private static final String BASE_WINDTEMP = "https://aviationweather.gov/api/data/windtemp";

    // Bounding-box radius around the requested point (degrees ≈ 111 km/deg)
    private static final double BBOX_DEG = 1.5;   // ~165 km radius

    // Conversion factors — delegated to WeatherUnitConverter (S2.2)
    private static final double KT_TO_MS = com.atakmap.android.weather.util.WeatherUnitConverter.KT_TO_MS;
    private static final double SM_TO_M  = com.atakmap.android.weather.util.WeatherUnitConverter.SM_TO_M;

    // Wind-aloft pressure levels and approximate altitudes (metres MSL)
    private static final int[]    ALOFT_ALT_M  = {  760, 1500, 3000, 4200 };
    private static final String[] ALOFT_WDIR   = {"wdir3000","wdir6000","wdir9000","wdir12000"};
    private static final String[] ALOFT_WSPD   = {"wspd3000","wspd6000","wspd9000","wspd12000"};
    private static final String[] ALOFT_TEMP   = {"temp3000","temp6000","temp9000","temp12000"};

    // ── Fallback forecast source ──────────────────────────────────────────────

    /**
     * Open-Meteo instance used for hourly/daily series (AWC has no equivalent).
     * Prefs are forwarded to it via setParameterPreferences().
     */
    private final OpenMeteoSource fallback = new OpenMeteoSource();

    // ── IWeatherRemoteSource ──────────────────────────────────────────────────

    @Override public String getSourceId()    { return SOURCE_ID; }
    @Override public String getDisplayName() { return "Aviation Weather Center (METAR)"; }

    @Override
    public List<WeatherParameter> getSupportedParameters() {
        // AWC surface obs cover the same meteorological parameters as Open-Meteo.
        // Hourly/daily series are served by the Open-Meteo fallback so the full
        // parameter set is still configurable.
        return Collections.unmodifiableList(Arrays.asList(WeatherParameter.values()));
    }

    @Override
    public void setParameterPreferences(WeatherParameterPreferences prefs) {
        // Forward to the fallback so hourly/daily URLs respect user parameter prefs.
        fallback.setParameterPreferences(prefs);
    }

    // ── fetchCurrentWeather ───────────────────────────────────────────────────

    /**
     * Queries the nearest METAR station within BBOX_DEG degrees of the
     * requested coordinate and maps it to WeatherModel.
     *
     * Selection strategy: iterate the returned array, compute Euclidean
     * distance in (lat,lon) space, pick the closest station.
     */
    @Override
    public void fetchCurrentWeather(double lat, double lon,
                                    FetchCallback<WeatherModel> callback) {
        String url = buildMetarUrl(lat, lon);
        Log.d(TAG, "fetchCurrentWeather → " + url);

        HttpClient.get(url, new HttpClient.Callback() {
            @Override
            public void onSuccess(String body) {
                try {
                    JSONArray arr = new JSONArray(body);
                    if (arr.length() == 0) {
                        // No station in bbox — fall back to Open-Meteo current weather
                        Log.w(TAG, "No METAR stations in bbox, falling back to Open-Meteo");
                        fallback.fetchCurrentWeather(lat, lon, callback);
                        return;
                    }

                    JSONObject nearest = findNearest(arr, lat, lon);
                    if (nearest == null) {
                        fallback.fetchCurrentWeather(lat, lon, callback);
                        return;
                    }

                    WeatherModel model = parseMETAR(nearest, lat, lon);
                    callback.onResult(model);

                } catch (Exception e) {
                    Log.e(TAG, "fetchCurrentWeather parse error", e);
                    callback.onError("AWC parse error: " + e.getMessage());
                }
            }
            @Override public void onFailure(String error) {
                Log.w(TAG, "fetchCurrentWeather network error: " + error + " — falling back");
                fallback.fetchCurrentWeather(lat, lon, callback);
            }
        });
    }

    // ── fetchDailyForecast ────────────────────────────────────────────────────

    /**
     * AWC provides no gridded daily forecast — delegate to Open-Meteo.
     * This is by design; see class Javadoc.
     */
    @Override
    public void fetchDailyForecast(double lat, double lon, int days,
                                   FetchCallback<List<DailyForecastModel>> callback) {
        fallback.fetchDailyForecast(lat, lon, days, callback);
    }

    // ── fetchHourlyForecast ───────────────────────────────────────────────────

    /**
     * AWC provides no hourly time-series — delegate to Open-Meteo.
     * This is by design; see class Javadoc.
     */
    @Override
    public void fetchHourlyForecast(double lat, double lon, int hours,
                                    FetchCallback<List<HourlyEntryModel>> callback) {
        fallback.fetchHourlyForecast(lat, lon, hours, callback);
    }

    // ── fetchWindProfile ──────────────────────────────────────────────────────

    /**
     * Fetches upper-air wind aloft data from the AWC windtemp endpoint and
     * combines it with the surface METAR (10 m) to produce a multi-altitude
     * WindProfileModel list (one entry = current obs, not a time-series).
     *
     * Altitudes provided:
     *   10 m   — from METAR wdir/wspd
     *   760 m  — windtemp 3000 ft level (925 hPa equivalent)
     *   1500 m — windtemp 6000 ft level (850 hPa)
     *   3000 m — windtemp 9000 ft level (700 hPa)
     *   4200 m — windtemp 12000 ft level (600 hPa)
     *
     * The WindProfileModel list returned contains exactly one frame (the current
     * observation), matching the shape produced by OpenMeteoSource.
     */
    @Override
    public void fetchWindProfile(double lat, double lon,
                                 FetchCallback<List<WindProfileModel>> callback) {
        // Step 1: fetch surface METAR for the 10 m tier
        String metarUrl = buildMetarUrl(lat, lon);
        HttpClient.get(metarUrl, new HttpClient.Callback() {
            @Override
            public void onSuccess(String metarBody) {
                try {
                    JSONArray metarArr = new JSONArray(metarBody);
                    JSONObject nearest = (metarArr.length() > 0)
                            ? findNearest(metarArr, lat, lon) : null;

                    // Surface tier defaults (used if METAR lookup fails)
                    double surfaceSpeed = 0, surfaceDir = 0, surfaceTemp = 20, surfaceGusts = 0;
                    String obsTime = DateUtils.nowFormatted();

                    if (nearest != null) {
                        surfaceSpeed  = nearest.optDouble("wspd", 0) * KT_TO_MS;
                        surfaceDir    = nearest.optDouble("wdir", 0);
                        surfaceTemp   = nearest.optDouble("temp", 20);
                        surfaceGusts  = nearest.optDouble("wgst", 0) * KT_TO_MS;
                        String rt = nearest.optString("reportTime", "");
                        if (!rt.isEmpty()) obsTime = rt;
                    }

                    final double fSurfaceSpeed  = surfaceSpeed;
                    final double fSurfaceDir    = surfaceDir;
                    final double fSurfaceTemp   = surfaceTemp;
                    final double fSurfaceGusts  = surfaceGusts;
                    final String fObsTime       = obsTime;

                    // Step 2: fetch wind aloft for upper tiers
                    String windUrl = buildWindTempUrl(lat, lon);
                    HttpClient.get(windUrl, new HttpClient.Callback() {
                        @Override
                        public void onSuccess(String windBody) {
                            try {
                                List<WindProfileModel> result =
                                        buildWindProfile(windBody, lat, lon,
                                                fSurfaceSpeed, fSurfaceDir,
                                                fSurfaceTemp, fSurfaceGusts,
                                                fObsTime);
                                callback.onResult(result);
                            } catch (Exception e) {
                                Log.e(TAG, "fetchWindProfile wind-aloft parse error", e);
                                // Return AWC surface-only profile (5 tiers, same value) —
                                // NEVER mix in Open-Meteo altitudes; chart must stay at
                                // 10/760/1500/3000/4200 m for METAR source.
                                callback.onResult(buildSurfaceRepeatedProfile(
                                        fSurfaceSpeed, fSurfaceDir,
                                        fSurfaceTemp, fSurfaceGusts, fObsTime));
                            }
                        }
                        @Override public void onFailure(String error) {
                            Log.w(TAG, "fetchWindProfile wind-aloft failed: " + error);
                            callback.onResult(buildSurfaceRepeatedProfile(
                                    fSurfaceSpeed, fSurfaceDir,
                                    fSurfaceTemp, fSurfaceGusts, fObsTime));
                        }
                    });

                } catch (Exception e) {
                    Log.e(TAG, "fetchWindProfile METAR parse error", e);
                    // METAR itself failed — fall back to Open-Meteo entirely
                    fallback.fetchWindProfile(lat, lon, callback);
                }
            }
            @Override public void onFailure(String error) {
                Log.w(TAG, "fetchWindProfile METAR network error: " + error + " — falling back");
                fallback.fetchWindProfile(lat, lon, callback);
            }
        });
    }


    // ── URL builders ─────────────────────────────────────────────────────────

    private static String buildMetarUrl(double lat, double lon) {
        return String.format(Locale.US,
                "%s?format=json&bbox=%.4f,%.4f,%.4f,%.4f&hours=2",
                BASE_METAR,
                lat - BBOX_DEG, lon - BBOX_DEG,
                lat + BBOX_DEG, lon + BBOX_DEG);
    }

    private static String buildWindTempUrl(double lat, double lon) {
        // The AWC windtemp endpoint's 'region' parameter accepts predefined region
        // strings (all, us, alaska, pacific …), NOT arbitrary lat/lon bboxes.
        // We fetch all low-level stations and pick the nearest one client-side via
        // findNearest().  'level=low' returns 3000/6000/9000/12000 ft wind aloft.
        // 'fcst=06' requests the 6-hour forecast cycle (most current available).
        return BASE_WINDTEMP + "?format=json&level=low&region=all&fcst=06";
    }

    // ── METAR station selection ───────────────────────────────────────────────

    /**
     * Returns the JSONObject from the array whose (lat, lon) fields are
     * closest to the requested point, using squared Euclidean distance.
     * Returns null if no station has valid coordinates.
     */
    private static JSONObject findNearest(JSONArray arr, double targetLat, double targetLon) {
        JSONObject best = null;
        double bestDist2 = Double.MAX_VALUE;
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o   = arr.getJSONObject(i);
                double sLat    = o.optDouble("lat", Double.NaN);
                double sLon    = o.optDouble("lon", Double.NaN);
                if (Double.isNaN(sLat) || Double.isNaN(sLon)) continue;
                double dLat = sLat - targetLat;
                double dLon = sLon - targetLon;
                double d2 = dLat * dLat + dLon * dLon;
                if (d2 < bestDist2) { bestDist2 = d2; best = o; }
            } catch (Exception ignored) {}
        }
        return best;
    }

    // ── METAR → WeatherModel ─────────────────────────────────────────────────

    /**
     * Maps an AWC METAR JSON object to WeatherModel.
     *
     * Field mapping (AWC → WeatherModel):
     *   temp     °C            → temperatureMax + temperatureMin (obs = current)
     *   dewp     °C            → (not in model; used for humidity calculation)
     *   wspd     kt            → windSpeed (converted to m/s)
     *   wdir     °true         → windDirection
     *   wgst     kt (nullable) → (not in base model; used in wind profile)
     *   visib    SM or "10+"   → visibility (converted to metres)
     *   altim    hPa           → pressure
     *   clouds[0].cover+base   → weatherCode approximation (see cloudCoverToWmo())
     *   lat, lon               → model coordinates
     *   name                   → locationName
     */
    private static WeatherModel parseMETAR(JSONObject o, double reqLat, double reqLon) throws Exception {
        double stationLat  = o.optDouble("lat",  reqLat);
        double stationLon  = o.optDouble("lon",  reqLon);
        String icaoId      = o.optString("icaoId", "");
        String name        = o.optString("name", icaoId);   // fall back to ICAO if no name
        String fltCat      = o.optString("fltCat", "");     // VFR / MVFR / IFR / LIFR
        String rawOb       = o.optString("rawOb", "");
        double tempC       = o.optDouble("temp", Double.NaN);
        double dewpC       = o.optDouble("dewp", Double.NaN);
        double wspdKt      = o.optDouble("wspd", 0.0);
        double wdir        = o.optDouble("wdir", 0.0);
        double altimHpa    = o.optDouble("altim", 1013.25);
        double visibSM     = parseVisibility(o.optString("visib", "10+"));

        // Convert units
        double wspdMs      = wspdKt * KT_TO_MS;
        double visibM      = visibSM * SM_TO_M;

        // Humidity from Magnus approximation (temp + dewpoint)
        double humidity    = 0.0;
        if (!Double.isNaN(tempC) && !Double.isNaN(dewpC)) {
            humidity = 100.0 * Math.exp((17.625 * dewpC) / (243.04 + dewpC))
                    / Math.exp((17.625 * tempC) / (243.04 + tempC));
        }

        // WMO weather code approximated from cloud cover + visibility
        int wmoCode = approximateWmoCode(o, visibM);

        double temp = Double.isNaN(tempC) ? 0.0 : tempC;

        return new WeatherModel.Builder(stationLat, stationLon)
                .locationName(name)
                .temperatureMax(temp)
                .temperatureMin(temp)
                .apparentTemperature(temp)     // METAR has no feels-like; use temp
                .humidity(humidity)
                .pressure(altimHpa)
                .visibility(visibM)
                .windSpeed(wspdMs)
                .windDirection(wdir)
                .precipitationSum(0.0)         // not in METAR surface obs
                .precipitationHours(0.0)
                .weatherCode(wmoCode)
                .requestTimestamp(DateUtils.nowFormatted())
                // AWC-specific fields
                .icaoId(icaoId)
                .flightCategory(fltCat)
                .rawMetar(rawOb)
                .build();
    }

    /**
     * Parse the AWC visibility field which may be "10+" (≥10 SM) or a numeric string.
     */
    private static double parseVisibility(String visib) {
        if (visib == null || visib.isEmpty()) return 10.0;
        if (visib.startsWith("10+")) return 10.0;
        try { return Double.parseDouble(visib.trim()); }
        catch (NumberFormatException e) { return 10.0; }
    }

    /**
     * Approximate a WMO weather code from clouds and visibility.
     *
     * This is a simplified heuristic — AWC wxString carries the full present
     * weather group but parsing the free-text METAR remarks is non-trivial.
     * The mapping is conservative: it only distinguishes clear/few/scattered/
     * broken/overcast, fog (<800 m), and light-precipitation categories.
     *
     * WMO codes used:
     *   0  = clear sky
     *   1  = mainly clear
     *   2  = partly cloudy
     *   3  = overcast
     *   45 = fog
     *   51 = drizzle (guessed when wxString contains "DZ")
     *   61 = rain    (guessed when wxString contains "RA")
     *   71 = snow    (guessed when wxString contains "SN")
     */
    private static int approximateWmoCode(JSONObject o, double visibM) {
        // Precipitation hints from wxString
        String wx = o.optString("wxString", "");
        if (wx.contains("SN") || wx.contains("GS")) return 71;
        if (wx.contains("RA") || wx.contains("SH")) return 61;
        if (wx.contains("DZ"))                      return 51;
        if (wx.contains("TS"))                      return 95;  // thunderstorm

        // Fog/mist
        if (visibM < 800) return 45;

        // Cloud cover from first layer
        JSONArray clouds = o.optJSONArray("clouds");
        if (clouds != null && clouds.length() > 0) {
            try {
                String cover = clouds.getJSONObject(0).optString("cover", "CLR");
                switch (cover) {
                    case "OVC":           return 3;   // overcast
                    case "BKN":           return 3;   // broken (≥5/8 = overcast-ish)
                    case "SCT":           return 2;   // scattered
                    case "FEW":           return 1;   // mainly clear
                    case "CLR": case "SKC": case "CAVOK": default: return 0;
                }
            } catch (Exception ignored) {}
        }
        return 0; // clear
    }

    // ── Wind profile builder ─────────────────────────────────────────────────

    /**
     * Combines a surface METAR tier (10 m) with wind-aloft tiers from the
     * AWC windtemp endpoint to produce a single-frame WindProfileModel list.
     *
     * The windtemp response is a JSON array; we pick the record closest to the
     * requested coordinate (same findNearest() strategy as for METARs).
     */
    private List<WindProfileModel> buildWindProfile(
            String windBody,
            double lat, double lon,
            double surfaceSpeed, double surfaceDir,
            double surfaceTemp, double surfaceGusts,
            String obsTime) throws Exception {

        JSONArray windArr  = new JSONArray(windBody);
        JSONObject nearest = (windArr.length() > 0)
                ? findNearest(windArr, lat, lon) : null;

        List<WindProfileModel.AltitudeEntry> entries = new ArrayList<>();

        // Tier 0: surface (10 m) from METAR
        entries.add(new WindProfileModel.AltitudeEntry(
                10, surfaceSpeed, surfaceDir, surfaceTemp, surfaceGusts));

        if (nearest != null) {
            for (int i = 0; i < ALOFT_ALT_M.length; i++) {
                // AWC wind speed at these levels is already in knots — convert
                double spd = nearest.optDouble(ALOFT_WSPD[i], 0.0) * KT_TO_MS;
                double dir = nearest.optDouble(ALOFT_WDIR[i], 0.0);
                double tmp = nearest.optDouble(ALOFT_TEMP[i], surfaceTemp);
                entries.add(new WindProfileModel.AltitudeEntry(
                        ALOFT_ALT_M[i], spd, dir, tmp, 0.0));
            }
        } else {
            // No wind-aloft data available: fill upper tiers with surface values
            // so the WindProfileView still renders (bars will be identical)
            for (int altM : ALOFT_ALT_M) {
                entries.add(new WindProfileModel.AltitudeEntry(
                        altM, surfaceSpeed, surfaceDir, surfaceTemp, 0.0));
            }
        }

        // Return a single-frame list — the time-stamp is the METAR obs time
        return Collections.singletonList(new WindProfileModel(obsTime, entries));
    }

    /**
     * Returns a single-frame profile containing only the 10 m surface tier.
     * Used when the wind-aloft endpoint fails and Open-Meteo fallback is
     * not appropriate (e.g. the caller already has a surface METAR).
     */
    /**
     * Builds a fallback profile containing all 5 AWC altitude tiers
     * (10 / 760 / 1500 / 3000 / 4200 m) filled with the surface METAR values.
     *
     * This keeps the chart x-axis on the correct AWC scale when wind-aloft data
     * is unavailable — the bars will be identical at all levels but the user can
     * see that the source is METAR and that only surface data was available.
     *
     * NEVER falls back to Open-Meteo altitude tiers here; that would mix
     * 10/80/120/180 m into an AWC METAR result, confusing the chart.
     */
    private static List<WindProfileModel> buildSurfaceRepeatedProfile(
            double speed, double dir, double temp, double gusts, String time) {
        List<WindProfileModel.AltitudeEntry> entries = new ArrayList<>();
        entries.add(new WindProfileModel.AltitudeEntry(10, speed, dir, temp, gusts));
        for (int altM : ALOFT_ALT_M) {
            entries.add(new WindProfileModel.AltitudeEntry(altM, speed, dir, temp, 0.0));
        }
        return Collections.singletonList(new WindProfileModel(time, entries));
    }
}
