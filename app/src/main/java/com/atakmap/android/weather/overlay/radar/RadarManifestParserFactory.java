package com.atakmap.android.weather.overlay.radar;

import com.atakmap.coremap.log.Log;

/**
 * Factory for creating {@link IRadarManifestParser} instances based on the
 * {@code manifestFormat} string from a v2 radar source definition.
 *
 * <p>Supported formats:</p>
 * <ul>
 *   <li>{@code "rainviewer"} — {@link RainViewerManifestParser} (multi-frame manifest)</li>
 *   <li>{@code "static"} — {@link StaticTileParser} (single-frame, no manifest)</li>
 * </ul>
 *
 * <p>Unknown formats fall back to {@link RainViewerManifestParser} with a warning log.</p>
 */
public class RadarManifestParserFactory {

    private static final String TAG = "RadarManifestParserFactory";

    /**
     * Get a parser implementation for the given manifest format string.
     *
     * @param manifestFormat the format identifier from the v2 source definition
     *                       (e.g., "rainviewer", "static")
     * @return a parser instance (never null)
     */
    public static IRadarManifestParser getParser(String manifestFormat) {
        if (manifestFormat == null || manifestFormat.isEmpty()) {
            Log.d(TAG, "No manifestFormat specified, defaulting to RainViewer parser");
            return new RainViewerManifestParser();
        }

        switch (manifestFormat.toLowerCase()) {
            case "rainviewer":
                return new RainViewerManifestParser();
            case "static":
                return new StaticTileParser();
            default:
                Log.w(TAG, "Unknown manifestFormat '" + manifestFormat
                        + "', falling back to RainViewer parser");
                return new RainViewerManifestParser();
        }
    }
}
