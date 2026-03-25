package com.atakmap.android.weather.data.remote.schema;

import com.atakmap.android.weather.domain.model.DailyForecastModel;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.model.WindProfileModel;
import com.atakmap.android.weather.util.DateUtils;
import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps API response fields to domain model fields using the {@code fieldMapping}
 * from a v2 weather source definition.
 *
 * <p>The mapping is {@code internalName -> apiFieldName}. For example,
 * {@code "temperature" -> "temperature_2m"} means the domain concept "temperature"
 * is found in the API response under the key "temperature_2m".</p>
 */
public class FieldMapper {

    private static final String TAG = "FieldMapper";

    private final Map<String, String> mapping; // internalName -> apiFieldName

    /**
     * Create a FieldMapper from a fieldMapping.
     *
     * @param fieldMapping mapping from internal names to API field names; may be null
     */
    public FieldMapper(Map<String, String> fieldMapping) {
        this.mapping = fieldMapping != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(fieldMapping))
                : Collections.<String, String>emptyMap();
    }

    /**
     * Get the API field name for an internal field name.
     *
     * @param internalName the domain-level name (e.g., "temperature")
     * @return the API-level name (e.g., "temperature_2m"), or the internalName itself
     *         if no mapping exists (pass-through)
     */
    public String apiField(String internalName) {
        String mapped = mapping.get(internalName);
        return mapped != null ? mapped : internalName;
    }

    /**
     * Read a double from a JSON object using the mapped field name.
     *
     * @param obj          the JSON object to read from
     * @param internalName the internal field name
     * @param defaultVal   value to return if the field is missing or not a number
     * @return the double value, or defaultVal
     */
    public double getDouble(JSONObject obj, String internalName, double defaultVal) {
        try {
            String key = apiField(internalName);
            if (obj.has(key)) {
                return obj.getDouble(key);
            }
        } catch (Exception e) {
            Log.w(TAG, "getDouble failed for '" + internalName + "': " + e.getMessage());
        }
        return defaultVal;
    }

    /**
     * Read a string from a JSON object using the mapped field name.
     */
    public String getString(JSONObject obj, String internalName, String defaultVal) {
        try {
            String key = apiField(internalName);
            if (obj.has(key)) {
                return obj.getString(key);
            }
        } catch (Exception e) {
            Log.w(TAG, "getString failed for '" + internalName + "': " + e.getMessage());
        }
        return defaultVal;
    }

    /**
     * Read an int from a JSON object using the mapped field name.
     */
    public int getInt(JSONObject obj, String internalName, int defaultVal) {
        try {
            String key = apiField(internalName);
            if (obj.has(key)) {
                return obj.getInt(key);
            }
        } catch (Exception e) {
            Log.w(TAG, "getInt failed for '" + internalName + "': " + e.getMessage());
        }
        return defaultVal;
    }

    /**
     * Read a long from a JSON object using the mapped field name.
     */
    public long getLong(JSONObject obj, String internalName, long defaultVal) {
        try {
            String key = apiField(internalName);
            if (obj.has(key)) {
                return obj.getLong(key);
            }
        } catch (Exception e) {
            Log.w(TAG, "getLong failed for '" + internalName + "': " + e.getMessage());
        }
        return defaultVal;
    }

    // ── Static model builders ────────────────────────────────────────────────

    /**
     * Build a {@link WeatherModel} from a "current" JSON response section using the
     * provided field mapping.
     *
     * <p>The current data may be a flat object (e.g., Open-Meteo's {@code current}
     * block) where each field is a scalar, or it may contain arrays where we take
     * index 0. This method handles both cases.</p>
     *
     * @param currentData  the JSON object at the endpoint's responsePath
     * @param fieldMapping internalName -> apiFieldName mapping
     * @param lat          request latitude
     * @param lon          request longitude
     * @param sourceName   display name for logging
     * @return a fully constructed WeatherModel
     */
    public static WeatherModel buildWeatherModel(JSONObject currentData,
                                                   Map<String, String> fieldMapping,
                                                   double lat, double lon,
                                                   String sourceName) {
        FieldMapper fm = new FieldMapper(fieldMapping);

        double temperature    = fm.getDouble(currentData, "temperature", 0);
        double humidity       = fm.getDouble(currentData, "humidity", 0);
        double apparentTemp   = fm.getDouble(currentData, "apparentTemp", 0);
        double windSpeed      = fm.getDouble(currentData, "windSpeed", 0);
        double windDirection  = fm.getDouble(currentData, "windDirection", 0);
        double visibility     = fm.getDouble(currentData, "visibility", 0);
        double pressure       = fm.getDouble(currentData, "pressure", 0);
        int    weatherCode    = fm.getInt(currentData, "weatherCode", 0);
        double precipitation  = fm.getDouble(currentData, "precipitation", 0);

        return new WeatherModel.Builder(lat, lon)
                .temperatureMax(temperature)
                .temperatureMin(temperature)
                .apparentTemperature(apparentTemp)
                .humidity(humidity)
                .pressure(pressure)
                .visibility(visibility)
                .windSpeed(windSpeed)
                .windDirection(windDirection)
                .precipitationSum(precipitation)
                .weatherCode(weatherCode)
                .requestTimestamp(DateUtils.nowFormatted())
                .build();
    }

    /**
     * Build a list of {@link DailyForecastModel} from a "daily" JSON response section.
     *
     * <p>The daily data contains parallel arrays (one per field), indexed by day.
     * The time array provides date strings (e.g., "2024-07-27").</p>
     *
     * @param dailyData    the JSON object at the endpoint's responsePath
     * @param fieldMapping internalName -> apiFieldName mapping
     * @param timeField    the key for the time array (e.g., "time")
     * @return list of daily forecast entries
     */
    public static List<DailyForecastModel> buildDailyForecast(JSONObject dailyData,
                                                                Map<String, String> fieldMapping,
                                                                String timeField) {
        List<DailyForecastModel> result = new ArrayList<>();
        try {
            FieldMapper fm = new FieldMapper(fieldMapping);
            String tf = timeField != null ? timeField : "time";
            JSONArray times = dailyData.getJSONArray(tf);

            // Resolve API field names for each domain field
            String tempMaxKey    = fm.apiField("temperatureMax");
            String tempMinKey    = fm.apiField("temperatureMin");
            String codeKey       = fm.apiField("weatherCode");
            String precipSumKey  = fm.apiField("precipitationSum");
            String precipHrsKey  = fm.apiField("precipHours");
            String precipPctKey  = fm.apiField("precipProbMax");
            String sunriseKey    = fm.apiField("sunrise");
            String sunsetKey     = fm.apiField("sunset");
            String daylightKey   = fm.apiField("daylightDuration");

            JSONArray tempMaxArr    = dailyData.optJSONArray(tempMaxKey);
            JSONArray tempMinArr    = dailyData.optJSONArray(tempMinKey);
            JSONArray codeArr       = dailyData.optJSONArray(codeKey);
            JSONArray precipSumArr  = dailyData.optJSONArray(precipSumKey);
            JSONArray precipHrsArr  = dailyData.optJSONArray(precipHrsKey);
            JSONArray precipPctArr  = dailyData.optJSONArray(precipPctKey);
            JSONArray sunriseArr    = dailyData.optJSONArray(sunriseKey);
            JSONArray sunsetArr     = dailyData.optJSONArray(sunsetKey);
            JSONArray daylightArr   = dailyData.optJSONArray(daylightKey);

            int count = times.length();
            for (int i = 0; i < count; i++) {
                String dateStr = times.getString(i);
                DailyForecastModel.Builder b = new DailyForecastModel.Builder()
                        .date(dateStr)
                        .dayLabel(DateUtils.dayLabel(dateStr, i == 0));

                if (tempMaxArr != null && i < tempMaxArr.length()) {
                    b.temperatureMax(tempMaxArr.getDouble(i));
                }
                if (tempMinArr != null && i < tempMinArr.length()) {
                    b.temperatureMin(tempMinArr.getDouble(i));
                }
                if (codeArr != null && i < codeArr.length()) {
                    b.weatherCode(codeArr.getInt(i));
                }
                if (precipSumArr != null && i < precipSumArr.length()) {
                    b.precipitationSum(precipSumArr.getDouble(i));
                }
                if (precipHrsArr != null && i < precipHrsArr.length()) {
                    b.precipitationHours(precipHrsArr.getDouble(i));
                }
                if (precipPctArr != null && i < precipPctArr.length()) {
                    b.precipitationProbabilityMax(precipPctArr.getDouble(i));
                }
                // Sprint 9 (S9.2): sunrise, sunset, daylight duration
                if (sunriseArr != null && i < sunriseArr.length()) {
                    b.sunrise(sunriseArr.optString(i, null));
                }
                if (sunsetArr != null && i < sunsetArr.length()) {
                    b.sunset(sunsetArr.optString(i, null));
                }
                if (daylightArr != null && i < daylightArr.length()) {
                    b.daylightDurationSec(daylightArr.optDouble(i, 0));
                }

                result.add(b.build());
            }
        } catch (Exception e) {
            Log.e(TAG, "buildDailyForecast error: " + e.getMessage(), e);
        }
        return result;
    }

    /**
     * Build a list of {@link HourlyEntryModel} from an "hourly" JSON response section.
     *
     * @param hourlyData   the JSON object at the endpoint's responsePath
     * @param fieldMapping internalName -> apiFieldName mapping
     * @param timeField    the key for the time array (e.g., "time")
     * @return list of hourly forecast entries
     */
    public static List<HourlyEntryModel> buildHourlyForecast(JSONObject hourlyData,
                                                               Map<String, String> fieldMapping,
                                                               String timeField) {
        List<HourlyEntryModel> result = new ArrayList<>();
        try {
            FieldMapper fm = new FieldMapper(fieldMapping);
            String tf = timeField != null ? timeField : "time";
            JSONArray times = hourlyData.getJSONArray(tf);

            // Resolve API field names
            String tempKey      = fm.apiField("temperature");
            String humidKey     = fm.apiField("humidity");
            String feelKey      = fm.apiField("apparentTemp");
            String wspdKey      = fm.apiField("windSpeed");
            String wdirKey      = fm.apiField("windDirection");
            String visKey       = fm.apiField("visibility");
            String pressKey     = fm.apiField("pressure");
            String codeKey      = fm.apiField("weatherCode");
            String precipPctKey = fm.apiField("precipProb");
            String precipKey    = fm.apiField("precipitation");

            JSONArray tempArr      = hourlyData.optJSONArray(tempKey);
            JSONArray humidArr     = hourlyData.optJSONArray(humidKey);
            JSONArray feelArr      = hourlyData.optJSONArray(feelKey);
            JSONArray wspdArr      = hourlyData.optJSONArray(wspdKey);
            JSONArray wdirArr      = hourlyData.optJSONArray(wdirKey);
            JSONArray visArr       = hourlyData.optJSONArray(visKey);
            JSONArray pressArr     = hourlyData.optJSONArray(pressKey);
            JSONArray codeArr      = hourlyData.optJSONArray(codeKey);
            JSONArray precipPctArr = hourlyData.optJSONArray(precipPctKey);
            JSONArray precipArr    = hourlyData.optJSONArray(precipKey);

            int count = times.length();
            for (int i = 0; i < count; i++) {
                String iso = times.getString(i);
                HourlyEntryModel.Builder b = new HourlyEntryModel.Builder()
                        .isoTime(iso)
                        .hour(DateUtils.hourFromIso(iso));

                if (tempArr != null && i < tempArr.length()) {
                    b.temperature(tempArr.getDouble(i));
                }
                if (humidArr != null && i < humidArr.length()) {
                    b.humidity(humidArr.getDouble(i));
                }
                if (feelArr != null && i < feelArr.length()) {
                    b.apparentTemperature(feelArr.getDouble(i));
                }
                if (wspdArr != null && i < wspdArr.length()) {
                    b.windSpeed(wspdArr.getDouble(i));
                }
                if (wdirArr != null && i < wdirArr.length()) {
                    b.windDirection(wdirArr.getDouble(i));
                }
                if (visArr != null && i < visArr.length()) {
                    b.visibility(visArr.getDouble(i));
                }
                if (pressArr != null && i < pressArr.length()) {
                    b.pressure(pressArr.getDouble(i));
                }
                if (codeArr != null && i < codeArr.length()) {
                    b.weatherCode(codeArr.getInt(i));
                }
                if (precipPctArr != null && i < precipPctArr.length()) {
                    b.precipitationProbability(precipPctArr.getDouble(i));
                }
                if (precipArr != null && i < precipArr.length()) {
                    b.precipitation(precipArr.getDouble(i));
                }

                result.add(b.build());
            }
        } catch (Exception e) {
            Log.e(TAG, "buildHourlyForecast error: " + e.getMessage(), e);
        }
        return result;
    }

    /**
     * Build a list of {@link WindProfileModel} from a wind profile JSON response.
     *
     * <p>The wind profile endpoint returns parallel arrays for each altitude level.
     * The altitude field pattern uses {@code {alt}} as a placeholder for the altitude
     * in metres (e.g., {@code "wind_speed_{alt}m"} becomes {@code "wind_speed_10m"}).</p>
     *
     * @param hourlyData           the JSON object at the endpoint's responsePath
     * @param altitudesM           list of altitudes in metres (e.g., [10, 80, 120, 180])
     * @param altitudeFieldPattern map of internal concept to pattern string
     *                             (e.g., "windSpeed" -> "wind_speed_{alt}m")
     * @return list of wind profile entries, one per time step
     */
    public static List<WindProfileModel> buildWindProfile(JSONObject hourlyData,
                                                           List<Integer> altitudesM,
                                                           Map<String, String> altitudeFieldPattern) {
        return buildWindProfile(hourlyData, altitudesM, altitudeFieldPattern,
                null, WindProfileModel.SOURCE_SURFACE);
    }

    /**
     * Build a list of {@link WindProfileModel} from a wind profile JSON response,
     * with explicit source type and optional pressure-level info.
     *
     * <p>Sprint 9 (S9.1): Extended to support pressure-level wind data. When
     * {@code pressureLevelsHPa} is non-null, the {@code {pressure}} placeholder
     * in field patterns is replaced with each pressure value, and entries are
     * tagged with {@code SOURCE_PRESSURE}.</p>
     *
     * @param hourlyData           the JSON object at the endpoint's responsePath
     * @param altitudesM           list of ISA altitudes in metres
     * @param altitudeFieldPattern map of internal concept to pattern string
     * @param pressureLevelsHPa    pressure levels in hPa (null for surface data)
     * @param sourceType           {@link WindProfileModel#SOURCE_SURFACE} or
     *                             {@link WindProfileModel#SOURCE_PRESSURE}
     * @return list of wind profile entries, one per time step
     */
    public static List<WindProfileModel> buildWindProfile(JSONObject hourlyData,
                                                           List<Integer> altitudesM,
                                                           Map<String, String> altitudeFieldPattern,
                                                           List<Integer> pressureLevelsHPa,
                                                           String sourceType) {
        List<WindProfileModel> result = new ArrayList<>();
        try {
            JSONArray times = hourlyData.getJSONArray("time");
            int count = times.length();

            boolean isPressure = pressureLevelsHPa != null && !pressureLevelsHPa.isEmpty();
            String placeholder = isPressure ? "{pressure}" : "{alt}";

            String speedPattern = altitudeFieldPattern != null
                    ? altitudeFieldPattern.get("windSpeed") : null;
            String dirPattern = altitudeFieldPattern != null
                    ? altitudeFieldPattern.get("windDirection") : null;
            String tempPattern = altitudeFieldPattern != null
                    ? altitudeFieldPattern.get("temperature") : null;

            if (speedPattern == null) speedPattern = "wind_speed_{alt}m";
            if (dirPattern == null) dirPattern = "wind_direction_{alt}m";

            // Pre-resolve field names for each altitude
            int numAlts = altitudesM != null ? altitudesM.size() : 0;
            String[] speedKeys = new String[numAlts];
            String[] dirKeys = new String[numAlts];
            String[] tempKeys = new String[numAlts];

            for (int a = 0; a < numAlts; a++) {
                String replaceVal;
                if (isPressure && a < pressureLevelsHPa.size()) {
                    replaceVal = String.valueOf(pressureLevelsHPa.get(a));
                } else {
                    replaceVal = String.valueOf(altitudesM.get(a));
                }
                speedKeys[a] = speedPattern.replace(placeholder, replaceVal);
                dirKeys[a] = dirPattern.replace(placeholder, replaceVal);
                if (tempPattern != null) {
                    tempKeys[a] = tempPattern.replace(placeholder, replaceVal);
                }
                // Also handle special case: 10m uses temperature_2m in Open-Meteo
                if (tempKeys[a] == null && altitudesM.get(a) <= 10) {
                    tempKeys[a] = "temperature_2m";
                }
            }

            for (int i = 0; i < count; i++) {
                List<WindProfileModel.AltitudeEntry> entries = new ArrayList<>(numAlts);
                for (int a = 0; a < numAlts; a++) {
                    double speed = getArrayDouble(hourlyData, speedKeys[a], i, 0);
                    double dir = getArrayDouble(hourlyData, dirKeys[a], i, 0);
                    double temp = Double.NaN;
                    if (tempKeys[a] != null) {
                        temp = getArrayDouble(hourlyData, tempKeys[a], i, Double.NaN);
                    }
                    // Wind gusts only available at surface level (first altitude, non-pressure)
                    double gusts = 0;
                    if (a == 0 && !isPressure) {
                        gusts = getArrayDouble(hourlyData, "wind_gusts_10m", i, 0);
                        if (gusts == 0) {
                            gusts = getArrayDouble(hourlyData, "windgusts_10m", i, 0);
                        }
                    }
                    Integer pressureHPa = (isPressure && a < pressureLevelsHPa.size())
                            ? pressureLevelsHPa.get(a) : null;
                    entries.add(new WindProfileModel.AltitudeEntry(
                            altitudesM.get(a), speed, dir, temp, gusts,
                            sourceType, pressureHPa));
                }
                result.add(new WindProfileModel(times.getString(i), entries));
            }
        } catch (Exception e) {
            Log.e(TAG, "buildWindProfile error: " + e.getMessage(), e);
        }
        return result;
    }

    /**
     * Merge surface and pressure wind profiles into a single combined profile list.
     * Each time step from surface is merged with the corresponding pressure step (by index).
     * The resulting entries are sorted by altitude ascending.
     *
     * @param surfaceProfiles  surface-level profiles (10m, 80m, 120m, 180m)
     * @param pressureProfiles pressure-level profiles (1000..300 hPa)
     * @return merged profile list, or whichever is non-empty if the other is null/empty
     */
    public static List<WindProfileModel> mergeWindProfiles(
            List<WindProfileModel> surfaceProfiles,
            List<WindProfileModel> pressureProfiles) {
        if (surfaceProfiles == null || surfaceProfiles.isEmpty()) return pressureProfiles;
        if (pressureProfiles == null || pressureProfiles.isEmpty()) return surfaceProfiles;

        int count = Math.min(surfaceProfiles.size(), pressureProfiles.size());
        List<WindProfileModel> merged = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            WindProfileModel sFrame = surfaceProfiles.get(i);
            WindProfileModel pFrame = pressureProfiles.get(i);

            List<WindProfileModel.AltitudeEntry> combined = new ArrayList<>();
            if (sFrame.getAltitudes() != null) combined.addAll(sFrame.getAltitudes());
            if (pFrame.getAltitudes() != null) combined.addAll(pFrame.getAltitudes());

            // Sort by altitude ascending
            Collections.sort(combined, (a, b) -> Integer.compare(a.altitudeMeters, b.altitudeMeters));

            merged.add(new WindProfileModel(sFrame.getIsoTime(), combined));
        }
        return merged;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Safely get a double from a JSON array field at a given index.
     */
    private static double getArrayDouble(JSONObject obj, String key, int index,
                                          double defaultVal) {
        try {
            JSONArray arr = obj.optJSONArray(key);
            if (arr != null && index < arr.length()) {
                return arr.getDouble(index);
            }
        } catch (Exception ignored) { }
        return defaultVal;
    }
}
