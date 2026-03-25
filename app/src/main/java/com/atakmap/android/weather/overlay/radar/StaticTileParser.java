package com.atakmap.android.weather.overlay.radar;

import com.atakmap.android.weather.data.remote.schema.ManifestParsingConfig;
import com.atakmap.android.weather.data.remote.schema.WeatherSourceDefinitionV2;
import com.atakmap.coremap.log.Log;

import java.util.Collections;

/**
 * Manifest parser for single-frame / static radar tile sources.
 *
 * <p>Used for radar providers that do not have a manifest endpoint
 * (e.g., OpenWeatherMap precipitation tiles, Iowa Mesonet MRMS).
 * These sources serve a single "current" frame — there is no history
 * or animation timeline.</p>
 *
 * <p>The {@link #parse} method ignores the manifest JSON (which may be
 * null or empty) and creates a single-frame {@link RadarManifest} using
 * the current system time.</p>
 *
 * <p>The {@link #buildTileUrl} method performs simple placeholder
 * substitution on the tile URL template from the source definition.</p>
 */
public class StaticTileParser implements IRadarManifestParser {

    private static final String TAG = "StaticTileParser";

    @Override
    public RadarManifest parse(String manifestJson, ManifestParsingConfig config) {
        // Static sources have no manifest — create a single frame at current time
        long now = System.currentTimeMillis() / 1000L;
        Log.d(TAG, "Static source: single frame at t=" + now);

        return new RadarManifest.Builder()
                .host(null)
                .past(Collections.singletonList(
                        new RadarManifest.RadarFrame(now, null)))
                .future(Collections.<RadarManifest.RadarFrame>emptyList())
                .generatedTime(now)
                .build();
    }

    @Override
    public String buildTileUrl(RadarManifest manifest, RadarManifest.RadarFrame frame,
                               WeatherSourceDefinitionV2 def, int z, int x, int y) {
        String template = def.getTileUrlTemplate();
        if (template == null || template.isEmpty()) {
            Log.w(TAG, "No tileUrlTemplate for static source " + def.getRadarSourceId());
            return "";
        }

        int tileSize = def.getTileSize() > 0 ? def.getTileSize() : 256;

        return template
                .replace("{z}", String.valueOf(z))
                .replace("{x}", String.valueOf(x))
                .replace("{y}", String.valueOf(y))
                .replace("{size}", String.valueOf(tileSize))
                .replace("{timestamp}", String.valueOf(frame.getTimestamp()))
                .replace("{apikey}", "");  // Placeholder — API key injection handled elsewhere
    }
}
