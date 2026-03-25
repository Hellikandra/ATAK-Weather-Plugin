package com.atakmap.android.weather.data.remote;

import android.content.Context;
import android.os.Environment;

import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import com.atakmap.android.weather.data.remote.schema.SourceDefinitionV2Parser;
import com.atakmap.android.weather.data.remote.schema.WeatherSourceDefinitionV2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SourceDefinitionLoader — reads WeatherSourceDefinition JSON files from:
 *
 *   1. Plugin assets (bundled):       assets/weather_sources/*.json
 *   2. User overrides (external):     /sdcard/atak/tools/weather_sources/*.json
 *
 * User files with the same sourceId as a bundled file replace the bundled
 * definition, allowing custom API keys, base URLs, or parameter lists.
 *
 * Additional user-created source definitions (new sourceIds) are also loaded,
 * making the system open for third-party data sources with any API.
 *
 * Usage:
 *   List<WeatherSourceDefinition> sources = SourceDefinitionLoader.loadWeatherSources(ctx);
 *   List<WeatherSourceDefinition> radars  = SourceDefinitionLoader.loadRadarSources(ctx);
 */
public class SourceDefinitionLoader {

    /**
     * Simple in-process cache so repeated spinner/PARM builds in the same session
     * don't re-parse every JSON file on the filesystem.  Call {@link #clearCache()}
     * before rescanning after the user has added new files.
     */
    private static java.util.Map<String, WeatherSourceDefinition> cachedAll = null;

    /** Cached v2 definitions, keyed by sourceId or radarSourceId. */
    private static java.util.Map<String, WeatherSourceDefinitionV2> cachedV2 = null;

    /** Invalidate the in-process cache so the next {@link #loadAll} re-reads from disk. */
    public static synchronized void clearCache() {
        cachedAll = null;
        cachedV2 = null;
    }

    private static final String TAG             = "SourceDefinitionLoader";
    private static final String ASSET_DIR       = "weather_sources";
    private static final String EXTERNAL_DIR    = "atak/tools/weather_sources";

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Load all weather source definitions (non-radar) in priority order:
     * bundled assets first, then user overrides/additions on external storage.
     * Returns an empty list on failure.
     */
    public static List<WeatherSourceDefinition> loadWeatherSources(Context ctx) {
        return load(ctx, false);
    }

    /**
     * Load all radar source definitions in priority order.
     * Returns an empty list on failure.
     */
    public static List<WeatherSourceDefinition> loadRadarSources(Context ctx) {
        return load(ctx, true);
    }

    /**
     * Load all definitions (both weather and radar) keyed by sourceId / radarSourceId.
     * User files override bundled files with the same id.
     */
    public static synchronized Map<String, WeatherSourceDefinition> loadAll(Context ctx) {
        if (cachedAll != null) return cachedAll;
        LinkedHashMap<String, WeatherSourceDefinition> map = new LinkedHashMap<>();
        List<WeatherSourceDefinition> all = new ArrayList<>();
        all.addAll(loadFromAssets(ctx));
        all.addAll(loadFromExternal());
        for (WeatherSourceDefinition d : all) {
            String id = d.isRadarDefinition() ? d.radarSourceId : d.sourceId;
            if (id != null && !id.isEmpty()) map.put(id, d); // later files override earlier
        }
        cachedAll = map;
        return map;
    }

    // ── v2 Schema Detection and Loading ─────────────────────────────────────────

    /**
     * Quick check: does a JSON text contain a v2 schema marker?
     *
     * @param jsonText raw JSON file content
     * @return true if the file declares {@code _schema_version: "2.0"}
     */
    public static boolean isV2Schema(String jsonText) {
        return SourceDefinitionV2Parser.isV2Schema(jsonText);
    }

    /**
     * Load all v2 weather source definitions (keyed by sourceId or radarSourceId).
     * Scans bundled assets and external storage, just like {@link #loadAll(Context)},
     * but only parses files that declare {@code _schema_version: "2.0"}.
     * External files override bundled files with the same id.
     *
     * @param ctx Android context for asset access
     * @return ordered map of v2 definitions (never null)
     */
    public static synchronized Map<String, WeatherSourceDefinitionV2> loadAllV2(Context ctx) {
        if (cachedV2 != null) return cachedV2;
        LinkedHashMap<String, WeatherSourceDefinitionV2> map = new LinkedHashMap<>();
        List<WeatherSourceDefinitionV2> all = new ArrayList<>();
        all.addAll(loadV2FromAssets(ctx));
        all.addAll(loadV2FromExternal());
        for (WeatherSourceDefinitionV2 d : all) {
            String id = d.isRadarSource() && d.getRadarSourceId() != null
                    ? d.getRadarSourceId()
                    : d.getSourceId();
            if (id != null && !id.isEmpty()) map.put(id, d);
        }
        cachedV2 = map;
        return map;
    }

    /**
     * Convenience method: load all v2 definitions as a list.
     *
     * @param ctx Android context
     * @return list of v2 definitions
     */
    public static List<WeatherSourceDefinitionV2> loadV2Sources(Context ctx) {
        return new ArrayList<>(loadAllV2(ctx).values());
    }

    /**
     * Load v2 definitions from bundled assets.
     */
    private static List<WeatherSourceDefinitionV2> loadV2FromAssets(Context ctx) {
        List<WeatherSourceDefinitionV2> result = new ArrayList<>();
        if (ctx == null) return result;
        try {
            String[] files = ctx.getAssets().list(ASSET_DIR);
            if (files == null) return result;
            for (String file : files) {
                if (!file.endsWith(".json")) continue;
                // Skip REFERENCE, EXAMPLE, and TEMPLATE files
                if (file.startsWith("REFERENCE_") || file.startsWith("EXAMPLE_")
                        || file.startsWith("TEMPLATE_")) continue;
                try (InputStream is = ctx.getAssets().open(ASSET_DIR + "/" + file)) {
                    String json = readString(is);
                    if (!isV2Schema(json)) continue;
                    WeatherSourceDefinitionV2 def = SourceDefinitionV2Parser.parse(json);
                    if (def != null) {
                        result.add(def);
                        Log.d(TAG, "Loaded v2 asset: " + file);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "v2 asset parse failed: " + file + " — " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "loadV2FromAssets failed: " + e.getMessage());
        }
        return result;
    }

    /**
     * Load v2 definitions from external storage.
     */
    private static List<WeatherSourceDefinitionV2> loadV2FromExternal() {
        List<WeatherSourceDefinitionV2> result = new ArrayList<>();
        File dir = new File(Environment.getExternalStorageDirectory(), EXTERNAL_DIR);
        if (!dir.exists() || !dir.isDirectory()) return result;
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return result;
        for (File file : files) {
            try (FileInputStream fis = new FileInputStream(file)) {
                String json = readString(fis);
                if (!isV2Schema(json)) continue;
                WeatherSourceDefinitionV2 def = SourceDefinitionV2Parser.parse(json);
                if (def != null) {
                    result.add(def);
                    Log.d(TAG, "Loaded v2 external: " + file.getName());
                }
            } catch (Exception e) {
                Log.w(TAG, "v2 external parse failed: " + file.getName() + " — " + e.getMessage());
            }
        }
        return result;
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private static List<WeatherSourceDefinition> load(Context ctx, boolean wantRadar) {
        LinkedHashMap<String, WeatherSourceDefinition> map = new LinkedHashMap<>();
        // Load bundled first, then external (external overrides)
        for (WeatherSourceDefinition d : loadFromAssets(ctx)) {
            if (d.isRadarDefinition() == wantRadar) {
                String id = wantRadar ? d.radarSourceId : d.sourceId;
                map.put(id, d);
            }
        }
        for (WeatherSourceDefinition d : loadFromExternal()) {
            if (d.isRadarDefinition() == wantRadar) {
                String id = wantRadar ? d.radarSourceId : d.sourceId;
                map.put(id, d); // overrides bundled
            }
        }
        return new ArrayList<>(map.values());
    }

    private static List<WeatherSourceDefinition> loadFromAssets(Context ctx) {
        List<WeatherSourceDefinition> result = new ArrayList<>();
        if (ctx == null) return result;
        try {
            String[] files = ctx.getAssets().list(ASSET_DIR);
            if (files == null) return result;
            for (String file : files) {
                if (!file.endsWith(".json") && !file.endsWith(".yaml") && !file.endsWith(".yml")) continue;
                try (InputStream is = ctx.getAssets().open(ASSET_DIR + "/" + file)) {
                    String json = readString(is);
                    boolean isYml = file.endsWith(".yaml") || file.endsWith(".yml");
                    WeatherSourceDefinition def = parse(json, isYml);
                    if (def != null) result.add(def);
                } catch (Exception e) {
                    Log.w(TAG, "Asset parse failed: " + file + " — " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "loadFromAssets failed: " + e.getMessage());
        }
        return result;
    }

    private static List<WeatherSourceDefinition> loadFromExternal() {
        List<WeatherSourceDefinition> result = new ArrayList<>();
        File dir = new File(Environment.getExternalStorageDirectory(), EXTERNAL_DIR);
        if (!dir.exists() || !dir.isDirectory()) return result;
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json") || name.endsWith(".yaml") || name.endsWith(".yml"));
        if (files == null) return result;
        for (File file : files) {
            try (FileInputStream fis = new FileInputStream(file)) {
                String json = readString(fis);
                boolean isYml = file.getName().endsWith(".yaml") || file.getName().endsWith(".yml");
                WeatherSourceDefinition def = parse(json, isYml);
                if (def != null) result.add(def);
                Log.d(TAG, "Loaded user definition: " + file.getName());
            } catch (Exception e) {
                Log.w(TAG, "External parse failed: " + file.getName() + " — " + e.getMessage());
            }
        }
        return result;
    }

    /**
     * Minimal YAML → JSON converter for the weather_sources definition subset.
     *
     * Handles:
     *   • Top-level key: value  pairs (quoted and unquoted strings, booleans, numbers)
     *   • Simple lists:  - item  under a list key
     *   • Nested mappings indented by 2+ spaces
     *   • Comments (#) stripped
     *   • _comment and _doc fields (silently ignored by JSON parser)
     *
     * Does NOT handle: anchors/aliases, multi-document streams, flow sequences,
     * quoted multi-line strings, or complex nesting beyond 2 levels.
     */
    @android.annotation.SuppressLint("DefaultLocale")
    private static String yamlToJson(String yaml) {
        // Delegate: convert the YAML subset to a JSON string that JSONObject can parse.
        // Strategy: line-by-line state machine.
        String[] lines = yaml.split("\n");
        StringBuilder sb = new StringBuilder("{\n");
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        stack.push("object");
        boolean firstTopField = true;
        boolean inList = false;
        String  listKey = null;
        StringBuilder listBuf = null;
        boolean firstListItem = true;

        for (String rawLine : lines) {
            String stripped = rawLine.stripTrailing();
            // Strip comments
            int commentIdx = stripped.indexOf('#');
            if (commentIdx >= 0) stripped = stripped.substring(0, commentIdx).stripTrailing();
            if (stripped.isBlank()) continue;

            int indent = 0;
            while (indent < stripped.length() && stripped.charAt(indent) == ' ') indent++;
            String trimmed = stripped.trim();

            // List item
            if (trimmed.startsWith("- ")) {
                if (listBuf == null) continue;
                String val = trimmed.substring(2).trim();
                if (!firstListItem) listBuf.append(",\n");
                listBuf.append(jsonValue(val));
                firstListItem = false;
                continue;
            }

            // Key: value pair
            int colon = trimmed.indexOf(": ");
            if (colon < 0 && trimmed.endsWith(":")) colon = trimmed.length() - 1;
            if (colon < 0) continue;

            String key = trimmed.substring(0, colon).trim();
            String val = (colon + 2 <= trimmed.length()) ? trimmed.substring(colon + 2).trim() : "";

            // Flush pending list
            if (inList && listBuf != null && indent == 0) {
                sb.append("\"").append(listKey).append("\": [\n").append(listBuf).append("\n]");
                inList = false; listKey = null; listBuf = null;
                firstTopField = false;
            }

            if (!firstTopField && indent == 0) sb.append(",\n");
            if (indent == 0) firstTopField = false;

            if (val.isEmpty()) {
                // Start of a list or nested object — peek ahead handled as list
                inList = true;
                listKey = key;
                listBuf = new StringBuilder();
                firstListItem = true;
            } else if (indent > 0 && inList) {
                // Nested mapping inside a list item
                if (listBuf == null) continue;
                if (!firstListItem) { listBuf.append(",\n"); }
                // Check if this is a sub-key inside a list-of-objects
                // We handle this by buffering nested keys into one JSON object
                // Simple approach: just append as "key": val pairs in a flat object
                if (firstListItem || listBuf.toString().endsWith("}")) {
                    if (!firstListItem) { listBuf.deleteCharAt(listBuf.length()-1); listBuf.append(",\n"); }
                    else { listBuf.append("{\n"); firstListItem = false; }
                } else {
                    listBuf.append(", ");
                }
                listBuf.append("\"").append(key).append("\": ").append(jsonValue(val));
            } else if (indent == 0) {
                sb.append("\"").append(key).append("\": ").append(jsonValue(val));
            }
        }

        // Flush last list
        if (inList && listBuf != null) {
            sb.append("\"").append(listKey).append("\": [\n").append(listBuf).append("\n]");
        }
        sb.append("\n}");
        return sb.toString();
    }

    private static String jsonValue(String v) {
        if (v.equalsIgnoreCase("true"))  return "true";
        if (v.equalsIgnoreCase("false")) return "false";
        if (v.equalsIgnoreCase("null") || v.isEmpty()) return "null";
        // Unquoted number
        try { Double.parseDouble(v); return v; } catch (NumberFormatException ignored) {}
        // Strip wrapping quotes if already quoted
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            v = v.substring(1, v.length() - 1);
        }
        // Escape for JSON
        v = v.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + v + "\"";
    }

    /**
     * Parse a source definition from JSON or YAML text.
     *
     * YAML support is intentionally minimal: converts the subset of YAML used by
     * the templates (key-value pairs, string arrays, boolean/null literals) to
     * JSON before handing off to the JSON parser.  Complex YAML (anchors, aliases,
     * multi-document streams) is not supported.
     *
     * @param text    raw file content (JSON or YAML)
     * @param isYaml  true if the file extension was .yaml or .yml
     */
    private static WeatherSourceDefinition parse(String text, boolean isYaml) {
        String json = isYaml ? yamlToJson(text) : text;
        try {
            JSONObject root = new JSONObject(json);
            WeatherSourceDefinition.Builder b = new WeatherSourceDefinition.Builder();

            // Detect radar vs weather source
            if (root.has("radarSourceId")) {
                b.radarSourceId(root.optString("radarSourceId"))
                        .displayName(root.optString("displayName"))
                        .manifestUrl(root.optString("manifestUrl"))
                        .tileUrlTemplate(root.optString("tileUrlTemplate"))
                        .tileSize(root.optInt("tileSize", 256))
                        .defaultZoom(root.optInt("defaultZoom", 5))
                        .description(root.optString("description"))
                        .attribution(root.optString("attribution"));
            } else {
                b.sourceId(root.optString("sourceId"))
                        .displayName(root.optString("displayName"))
                        .apiBaseUrl(root.optString("apiBaseUrl"))
                        .requiresApiKey(root.optBoolean("requiresApiKey", false))
                        .description(root.optString("description"));

                JSONObject params = root.optJSONObject("parameters");
                if (params != null) {
                    b.hourlyParams(parseParams(params.optJSONArray("hourly")))
                            .dailyParams(parseParams(params.optJSONArray("daily")))
                            .currentParams(parseParams(params.optJSONArray("current")));
                }
            }
            return b.build();
        } catch (Exception e) {
            Log.w(TAG, "parse error: " + e.getMessage());
            return null;
        }
    }

    private static List<WeatherSourceDefinition.ParamEntry> parseParams(JSONArray arr) {
        List<WeatherSourceDefinition.ParamEntry> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                list.add(new WeatherSourceDefinition.ParamEntry(
                        o.optString("key"),
                        o.optString("label", o.optString("key")),
                        o.optBoolean("defaultOn", false)));
            } catch (Exception ignored) {}
        }
        return list;
    }

    private static String readString(InputStream is) throws Exception {
        byte[] buf = new byte[8192];
        StringBuilder sb = new StringBuilder();
        int n;
        while ((n = is.read(buf)) > 0) {
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    // ── File import ──────────────────────────────────────────────────────────

    /**
     * Import a weather source definition from a JSON file on external storage.
     * Copies the file to the external weather sources directory so it's loaded
     * on next plugin startup.
     */
    public static void importFromFile(Context ctx, java.io.File srcFile) throws Exception {
        java.io.File destDir = new java.io.File(
                android.os.Environment.getExternalStorageDirectory(), EXTERNAL_DIR);
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new java.io.IOException("Cannot create " + destDir.getAbsolutePath());
        }
        java.io.File dest = new java.io.File(destDir, srcFile.getName());
        copyFile(srcFile, dest);
        clearCache(); // force re-scan on next loadAll()
        Log.d(TAG, "Imported weather source: " + dest.getAbsolutePath());
    }

    /**
     * Import a tile source definition from an XML file.
     * Copies to the same external directory for discovery by RadarSourceSelector.
     */
    public static void importTileSourceFromFile(Context ctx, java.io.File srcFile) throws Exception {
        java.io.File destDir = new java.io.File(
                android.os.Environment.getExternalStorageDirectory(), EXTERNAL_DIR);
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new java.io.IOException("Cannot create " + destDir.getAbsolutePath());
        }
        java.io.File dest = new java.io.File(destDir, srcFile.getName());
        copyFile(srcFile, dest);
        clearCache();
        Log.d(TAG, "Imported tile source: " + dest.getAbsolutePath());
    }

    private static void copyFile(java.io.File src, java.io.File dst) throws java.io.IOException {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(src);
             java.io.FileOutputStream fos = new java.io.FileOutputStream(dst)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        }
    }
}
