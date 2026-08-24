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
                // YAML loader removed in v3.1.1 — see issue #19. JSON only.
                if (!file.endsWith(".json")) continue;
                try (InputStream is = ctx.getAssets().open(ASSET_DIR + "/" + file)) {
                    String json = readString(is);
                    WeatherSourceDefinition def = parse(json);
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
        // YAML loader removed in v3.1.1 — see issue #19. JSON only.
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return result;
        for (File file : files) {
            try (FileInputStream fis = new FileInputStream(file)) {
                String json = readString(fis);
                WeatherSourceDefinition def = parse(json);
                if (def != null) result.add(def);
                Log.d(TAG, "Loaded user definition: " + file.getName());
            } catch (Exception e) {
                Log.w(TAG, "External parse failed: " + file.getName() + " — " + e.getMessage());
            }
        }
        return result;
    }

    /**
     * Parse a source definition from JSON text.
     * YAML support was removed in v3.1.1 — see issue #19.
     */
    private static WeatherSourceDefinition parse(String text) {
        try {
            JSONObject root = new JSONObject(text);
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
