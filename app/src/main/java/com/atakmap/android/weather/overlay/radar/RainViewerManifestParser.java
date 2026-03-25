package com.atakmap.android.weather.overlay.radar;

import com.atakmap.android.weather.data.remote.schema.ManifestParsingConfig;
import com.atakmap.android.weather.data.remote.schema.TileOptionsConfig;
import com.atakmap.android.weather.data.remote.schema.WeatherSourceDefinitionV2;
import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Manifest parser for the RainViewer weather-maps.json format.
 *
 * <p>Extracts host, radar.past[], and radar.future[] (with nowcast fallback)
 * from the RainViewer v5 manifest format. Uses the {@link ManifestParsingConfig}
 * field names when available, falling back to hardcoded RainViewer defaults.</p>
 *
 * <p>Tile URLs are built using the template from the v2 source definition,
 * substituting host, path, size, zoom, coordinates, color, and options.</p>
 */
public class RainViewerManifestParser implements IRadarManifestParser {

    private static final String TAG = "RainViewerManifestParser";

    @Override
    public RadarManifest parse(String manifestJson, ManifestParsingConfig config) throws Exception {
        JSONObject root = new JSONObject(manifestJson);

        // Resolve field names from config, or use RainViewer defaults
        String hostField      = config != null && config.getHostField() != null      ? config.getHostField()      : "host";
        String generatedField = config != null && config.getGeneratedField() != null  ? config.getGeneratedField() : "generated";
        String pastPath       = config != null && config.getPastPath() != null        ? config.getPastPath()       : "radar.past";
        String futurePath     = config != null && config.getFuturePath() != null      ? config.getFuturePath()     : "radar.future";
        String timeField      = config != null && config.getTimeField() != null       ? config.getTimeField()      : "time";
        String pathField      = config != null && config.getPathField() != null       ? config.getPathField()      : "path";

        String host = root.optString(hostField, "");
        long generated = root.optLong(generatedField, 0);

        // Parse past frames
        JSONArray pastArr = resolveJsonArray(root, pastPath);
        List<RadarManifest.RadarFrame> pastFrames = new ArrayList<>();
        if (pastArr != null) {
            for (int i = 0; i < pastArr.length(); i++) {
                JSONObject entry = pastArr.getJSONObject(i);
                long t = entry.optLong(timeField, 0);
                String p = entry.optString(pathField, "");
                if (t > 0) pastFrames.add(new RadarManifest.RadarFrame(t, p));
            }
        }

        // Parse future frames: try "nowcast" first (pre-2026), then futurePath
        JSONArray futureArr = resolveJsonArray(root, "radar.nowcast");
        if (futureArr == null || futureArr.length() == 0) {
            futureArr = resolveJsonArray(root, futurePath);
        }
        List<RadarManifest.RadarFrame> futureFrames = new ArrayList<>();
        if (futureArr != null) {
            for (int i = 0; i < futureArr.length(); i++) {
                JSONObject entry = futureArr.getJSONObject(i);
                long t = entry.optLong(timeField, 0);
                String p = entry.optString(pathField, "");
                if (t > 0) futureFrames.add(new RadarManifest.RadarFrame(t, p));
            }
        }

        if (futureFrames.isEmpty()) {
            Log.w(TAG, "RainViewer nowcast discontinued (Jan 2026) — past frames only");
        }
        Log.d(TAG, "Manifest: " + pastFrames.size() + " past + " + futureFrames.size() + " future");

        return new RadarManifest.Builder()
                .host(host)
                .past(pastFrames)
                .future(futureFrames)
                .generatedTime(generated)
                .build();
    }

    @Override
    public String buildTileUrl(RadarManifest manifest, RadarManifest.RadarFrame frame,
                               WeatherSourceDefinitionV2 def, int z, int x, int y) {
        String template = def.getTileUrlTemplate();
        if (template == null || template.isEmpty()) {
            // Fallback: use path-aware URL if available, otherwise legacy timestamp URL
            String host = manifest.getHost() != null ? manifest.getHost() : "https://tilecache.rainviewer.com";
            String path = frame.getPath();
            if (path != null && !path.isEmpty()) {
                return RadarTileProvider.tileUrl(host, path, z, x, y);
            }
            return RadarTileProvider.tileUrl(frame.getTimestamp(), z, x, y);
        }

        int tileSize = def.getTileSize() > 0 ? def.getTileSize() : 256;

        // Build options string from TileOptionsConfig
        String colorStr = "2";
        String optionsStr = "1_1";
        TileOptionsConfig tileOpts = def.getTileOptions();
        if (tileOpts != null) {
            colorStr = String.valueOf(tileOpts.getColor());
            optionsStr = tileOpts.getOptionsString() != null
                    ? tileOpts.getOptionsString() : "1_1";
        }

        String host = manifest.getHost() != null ? manifest.getHost() : "";
        String path = frame.getPath() != null ? frame.getPath() : "";

        return template
                .replace("{host}", host)
                .replace("{path}", path)
                .replace("{size}", String.valueOf(tileSize))
                .replace("{z}", String.valueOf(z))
                .replace("{x}", String.valueOf(x))
                .replace("{y}", String.valueOf(y))
                .replace("{color}", colorStr)
                .replace("{options}", optionsStr)
                .replace("{timestamp}", String.valueOf(frame.getTimestamp()));
    }

    /**
     * Resolve a dot-separated path (e.g., "radar.past") to a JSONArray
     * within a nested JSONObject tree.
     */
    private static JSONArray resolveJsonArray(JSONObject root, String dotPath) {
        if (dotPath == null || dotPath.isEmpty()) return null;
        String[] parts = dotPath.split("\\.");
        JSONObject current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            current = current.optJSONObject(parts[i]);
            if (current == null) return null;
        }
        return current.optJSONArray(parts[parts.length - 1]);
    }
}
