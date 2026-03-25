package com.atakmap.android.weather.data.remote.schema;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser that converts a v2 executable JSON schema string into a
 * {@link WeatherSourceDefinitionV2} instance.
 *
 * <p>Handles both weather-type sources (Open-Meteo style with endpoints,
 * parameters, unit conversions) and radar-type sources (RainViewer style
 * with manifest parsing, tile URL templates).</p>
 *
 * <p>The parser is lenient: missing optional fields receive sensible defaults.
 * Only {@code sourceId} (or {@code radarSourceId}), {@code displayName}, and
 * {@code type} are required. Fields whose keys start with {@code _} are
 * treated as documentation and skipped.</p>
 */
public class SourceDefinitionV2Parser {

    private static final String TAG = "SourceDefV2Parser";

    private SourceDefinitionV2Parser() { /* static utility */ }

    /**
     * Parse a JSON string into a v2 source definition.
     *
     * @param json the raw JSON text
     * @return the parsed definition, or null if the JSON is not valid v2
     */
    public static WeatherSourceDefinitionV2 parse(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(json);
            if (!isV2Object(root)) return null;

            WeatherSourceDefinitionV2.Builder b = new WeatherSourceDefinitionV2.Builder();

            // ── Identity ─────────────────────────────────────────────────
            b.schemaVersion(root.optString("_schema_version", "2.0"));
            b.sourceId(optNullableString(root, "sourceId"));
            b.radarSourceId(optNullableString(root, "radarSourceId"));
            b.displayName(root.optString("displayName", ""));
            b.description(root.optString("description", ""));
            b.type(root.optString("type", "weather"));
            b.provider(root.optString("provider", ""));

            // ── Model ────────────────────────────────────────────────────
            JSONObject modelObj = root.optJSONObject("model");
            if (modelObj != null) {
                b.model(parseModel(modelObj));
            }

            // ── Auth ─────────────────────────────────────────────────────
            JSONObject authObj = root.optJSONObject("auth");
            if (authObj != null) {
                b.auth(parseAuth(authObj));
            }

            // ── Rate Limit ───────────────────────────────────────────────
            JSONObject rlObj = root.optJSONObject("rateLimit");
            if (rlObj != null) {
                b.rateLimit(parseRateLimit(rlObj));
            }

            // ── Endpoints ────────────────────────────────────────────────
            JSONObject endpointsObj = root.optJSONObject("endpoints");
            if (endpointsObj != null) {
                b.endpoints(parseEndpoints(endpointsObj));
            }

            // ── Unit Conversions ─────────────────────────────────────────
            JSONObject ucObj = root.optJSONObject("unitConversions");
            if (ucObj != null) {
                b.unitConversions(parseUnitConversions(ucObj));
            }

            // ── Server-Side Units ────────────────────────────────────────
            JSONObject ssuObj = root.optJSONObject("serverSideUnits");
            if (ssuObj != null) {
                b.serverSideUnits(parseServerSideUnits(ssuObj));
            }

            // ── Weather Code Mapping ─────────────────────────────────────
            if (root.has("weatherCodeMapping") && !root.isNull("weatherCodeMapping")) {
                JSONObject wcObj = root.optJSONObject("weatherCodeMapping");
                if (wcObj != null) {
                    b.weatherCodeMapping(parseWeatherCodeMapping(wcObj));
                }
            }

            // ── Batch ────────────────────────────────────────────────────
            JSONObject batchObj = root.optJSONObject("batch");
            if (batchObj != null) {
                b.batch(parseBatch(batchObj));
            }

            // ── Parameters ───────────────────────────────────────────────
            JSONObject paramsObj = root.optJSONObject("parameters");
            if (paramsObj != null) {
                b.parameters(parseParameters(paramsObj));
            }

            // ── Radar-specific ───────────────────────────────────────────
            b.manifestUrl(optNullableString(root, "manifestUrl"));
            b.manifestFormat(optNullableString(root, "manifestFormat"));

            JSONObject mpObj = root.optJSONObject("manifestParsing");
            if (mpObj != null) {
                b.manifestParsing(parseManifestParsing(mpObj));
            }

            b.tileUrlTemplate(optNullableString(root, "tileUrlTemplate"));
            b.tileSize(root.optInt("tileSize", 256));
            b.maxZoom(root.optInt("maxZoom", 7));
            b.defaultZoom(root.optInt("defaultZoom", 5));

            JSONObject toObj = root.optJSONObject("tileOptions");
            if (toObj != null) {
                b.tileOptions(parseTileOptions(toObj));
            }

            b.attribution(root.optString("attribution", ""));
            b.license(root.optString("license", ""));

            return b.build();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Check whether a JSON string represents a v2 schema
     * (contains {@code _schema_version: "2.0"}).
     *
     * @param json the raw JSON text
     * @return true if this is a v2 schema document
     */
    public static boolean isV2Schema(String json) {
        if (json == null || json.isEmpty()) return false;
        try {
            JSONObject root = new JSONObject(json);
            return isV2Object(root);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Validate a parsed v2 definition for completeness.
     * Returns a list of warning messages; an empty list means no issues found.
     *
     * @param def the definition to validate
     * @return list of warning strings (never null)
     */
    public static List<String> validate(WeatherSourceDefinitionV2 def) {
        List<String> warnings = new ArrayList<>();
        if (def == null) {
            warnings.add("Definition is null");
            return warnings;
        }

        // Must have at least one id
        if (isBlank(def.getSourceId()) && isBlank(def.getRadarSourceId())) {
            warnings.add("Missing both sourceId and radarSourceId; at least one is required");
        }

        if (isBlank(def.getDisplayName())) {
            warnings.add("Missing displayName");
        }

        if (isBlank(def.getType())) {
            warnings.add("Missing type (expected 'weather', 'radar', or 'both')");
        }

        // Weather-specific checks
        if (def.isWeatherSource()) {
            if (def.getEndpoints().isEmpty()) {
                warnings.add("Weather source has no endpoints defined");
            }
            if (def.getEndpoint("current") == null && def.getEndpoint("hourly") == null) {
                warnings.add("Weather source has no 'current' or 'hourly' endpoint");
            }
        }

        // Radar-specific checks
        if (def.isRadarSource()) {
            if (isBlank(def.getManifestUrl())) {
                warnings.add("Radar source is missing manifestUrl");
            }
            if (isBlank(def.getTileUrlTemplate())) {
                warnings.add("Radar source is missing tileUrlTemplate");
            }
        }

        // Auth check
        if (def.getAuth() != null && def.getAuth().isRequired()) {
            if (isBlank(def.getAuth().getValue())
                    && isBlank(def.getAuth().getEnvVar())) {
                warnings.add("Auth is required but no value or envVar is configured");
            }
        }

        return warnings;
    }

    // ── Internal parsing helpers ─────────────────────────────────────────────

    private static boolean isV2Object(JSONObject root) {
        String ver = root.optString("_schema_version", "");
        return ver.startsWith("2");
    }

    private static ModelMetadata parseModel(JSONObject obj) {
        ModelMetadata.Builder b = new ModelMetadata.Builder();
        b.name(obj.optString("name", ""));
        if (!obj.isNull("gridResolutionKm")) {
            b.gridResolutionKm(obj.optDouble("gridResolutionKm", 0));
        }
        if (!obj.isNull("accuracyRadiusKm")) {
            b.accuracyRadiusKm(obj.optDouble("accuracyRadiusKm", 0));
        }
        b.forecastDaysMax(obj.optInt("forecastDaysMax", 0));
        b.updateFrequencyHours(obj.optInt("updateFrequencyHours", 0));
        b.coverage(obj.optString("coverage", ""));
        b.temporalResolution(obj.optString("temporalResolution", ""));
        b.sourceUrl(obj.optString("source_url", ""));

        JSONArray plArr = obj.optJSONArray("pressureLevels");
        if (plArr != null) {
            List<Integer> levels = new ArrayList<>();
            for (int i = 0; i < plArr.length(); i++) {
                levels.add(plArr.optInt(i));
            }
            b.pressureLevels(levels);
        }
        return b.build();
    }

    private static AuthConfig parseAuth(JSONObject obj) {
        AuthConfig.Builder b = new AuthConfig.Builder();
        b.type(obj.optString("type", "none"));
        b.headerName(optNullableString(obj, "headerName"));
        b.queryParam(optNullableString(obj, "queryParam"));
        b.envVar(optNullableString(obj, "envVar"));
        b.value(obj.optString("value", ""));
        return b.build();
    }

    private static RateLimitConfig parseRateLimit(JSONObject obj) {
        Integer rpm = obj.isNull("requestsPerMinute") ? null : obj.optInt("requestsPerMinute");
        Integer rph = obj.isNull("requestsPerHour") ? null : obj.optInt("requestsPerHour");
        Integer rpd = obj.isNull("requestsPerDay") ? null : obj.optInt("requestsPerDay");
        Integer rpmth = obj.isNull("requestsPerMonth") ? null : obj.optInt("requestsPerMonth");
        String note = obj.optString("note", "");
        return new RateLimitConfig(rpm, rph, rpd, rpmth, note);
    }

    private static Map<String, EndpointDef> parseEndpoints(JSONObject obj) {
        Map<String, EndpointDef> map = new LinkedHashMap<>();
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.startsWith("_")) continue;
            JSONObject epObj = obj.optJSONObject(key);
            if (epObj == null) continue;
            map.put(key, parseEndpoint(epObj));
        }
        return map;
    }

    private static EndpointDef parseEndpoint(JSONObject obj) {
        EndpointDef.Builder b = new EndpointDef.Builder();
        b.url(obj.optString("url", ""));
        b.method(obj.optString("method", "GET"));
        b.responsePath(obj.optString("responsePath", ""));
        b.timeField(optNullableString(obj, "timeField"));

        // queryParams
        JSONObject qpObj = obj.optJSONObject("queryParams");
        if (qpObj != null) {
            b.queryParams(parseStringMap(qpObj));
        }

        // fieldMapping
        JSONObject fmObj = obj.optJSONObject("fieldMapping");
        if (fmObj != null) {
            b.fieldMapping(parseStringMap(fmObj));
        }

        // altitudeFieldPattern
        JSONObject afpObj = obj.optJSONObject("altitudeFieldPattern");
        if (afpObj != null) {
            b.altitudeFieldPattern(parseStringMap(afpObj));
        }

        // windAltitudesM
        JSONArray waArr = obj.optJSONArray("windAltitudesM");
        if (waArr != null) {
            List<Integer> list = new ArrayList<>();
            for (int i = 0; i < waArr.length(); i++) {
                list.add(waArr.optInt(i));
            }
            b.windAltitudesM(list);
        }

        // windPressureLevelsHPa
        JSONArray wpArr = obj.optJSONArray("windPressureLevelsHPa");
        if (wpArr != null) {
            List<Integer> list = new ArrayList<>();
            for (int i = 0; i < wpArr.length(); i++) {
                list.add(wpArr.optInt(i));
            }
            b.windPressureLevelsHPa(list);
        }

        return b.build();
    }

    private static Map<String, UnitConversionDef> parseUnitConversions(JSONObject obj) {
        Map<String, UnitConversionDef> map = new LinkedHashMap<>();
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.startsWith("_")) continue;
            JSONObject ucObj = obj.optJSONObject(key);
            if (ucObj == null) continue;
            map.put(key, new UnitConversionDef(
                    ucObj.optString("from", ""),
                    ucObj.optString("to", ""),
                    ucObj.optDouble("factor", 1.0),
                    ucObj.optDouble("offset", 0.0)
            ));
        }
        return map;
    }

    private static Map<String, Map<String, String>> parseServerSideUnits(JSONObject obj) {
        Map<String, Map<String, String>> map = new LinkedHashMap<>();
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.startsWith("_")) continue;
            JSONObject inner = obj.optJSONObject(key);
            if (inner == null) continue;
            map.put(key, parseStringMap(inner));
        }
        return map;
    }

    private static Map<String, Integer> parseWeatherCodeMapping(JSONObject obj) {
        Map<String, Integer> map = new LinkedHashMap<>();
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.startsWith("_")) continue;
            map.put(key, obj.optInt(key));
        }
        return map;
    }

    private static BatchConfig parseBatch(JSONObject obj) {
        return new BatchConfig(
                obj.optBoolean("supported", false),
                obj.optInt("maxLocations", 1),
                obj.optString("latParam", "latitude"),
                obj.optString("lonParam", "longitude"),
                obj.optString("separator", ",")
        );
    }

    private static Map<String, List<ParameterDef>> parseParameters(JSONObject obj) {
        Map<String, List<ParameterDef>> map = new LinkedHashMap<>();
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.startsWith("_")) continue;
            JSONArray arr = obj.optJSONArray(key);
            if (arr == null) continue;
            List<ParameterDef> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject pObj = arr.optJSONObject(i);
                if (pObj == null) continue;
                list.add(new ParameterDef(
                        pObj.optString("key", ""),
                        pObj.optString("label", pObj.optString("key", "")),
                        pObj.optString("unit", ""),
                        pObj.optBoolean("defaultOn", false)
                ));
            }
            map.put(key, list);
        }
        return map;
    }

    private static ManifestParsingConfig parseManifestParsing(JSONObject obj) {
        return new ManifestParsingConfig(
                obj.optString("hostField", "host"),
                obj.optString("versionField", "version"),
                obj.optString("generatedField", "generated"),
                obj.optString("pastPath", ""),
                obj.optString("futurePath", ""),
                obj.optString("timeField", "time"),
                obj.optString("pathField", "path")
        );
    }

    private static TileOptionsConfig parseTileOptions(JSONObject obj) {
        return new TileOptionsConfig(
                obj.optInt("color", 2),
                obj.optInt("smooth", 1),
                obj.optInt("snow", 1),
                obj.optString("optionsString", "1_1")
        );
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    private static Map<String, String> parseStringMap(JSONObject obj) {
        Map<String, String> map = new LinkedHashMap<>();
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.startsWith("_")) continue;
            String val = obj.optString(key, "");
            map.put(key, val);
        }
        return map;
    }

    private static String optNullableString(JSONObject obj, String key) {
        if (obj.isNull(key)) return null;
        String val = obj.optString(key, null);
        return val;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
