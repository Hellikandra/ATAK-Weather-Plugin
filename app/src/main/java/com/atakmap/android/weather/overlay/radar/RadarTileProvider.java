package com.atakmap.android.weather.overlay.radar;

/**
 * RadarTileProvider — builds RainViewer tile URLs and Web Mercator tile helpers.
 *
 * <h3>RainViewer public API (no key required)</h3>
 *   Manifest: GET https://api.rainviewer.com/public/weather-maps.json
 *   Tile:     https://tilecache.rainviewer.com/v2/radar/{timestamp}/256/{z}/{x}/{y}/2/1_1.png
 *
 * The manifest returns radar.past[] (last ~2 hours, 10-min frames) and
 * radar.nowcast[] (next ~30 min).  Each entry has a "time" field (Unix seconds).
 *
 * Tiles use standard Web Mercator / OSM TMS (z/x/y), 256 px per tile.
 *
 * <h3>2026 API constraints</h3>
 * <ul>
 *   <li>Max tile zoom reduced from 12 to 7 (see {@link #MAX_TILE_ZOOM}).</li>
 *   <li>Only color scheme 2 (Universal Blue) is available; the {@code /2/1_1.png}
 *       suffix in tile URLs is fixed.</li>
 *   <li>{@code radar.nowcast[]} is always empty since Jan 2026; use
 *       {@code radar.future[]} as a fallback (v5 API field name).</li>
 *   <li>Rate limit: 100 requests/min (enforced via
 *       {@link com.atakmap.android.weather.util.RateLimiter}).</li>
 * </ul>
 */
public class RadarTileProvider {

    /** RainViewer manifest URL — returns available past + nowcast frame timestamps. */
    public static final String MANIFEST_URL =
            "https://api.rainviewer.com/public/weather-maps.json";

    private static final String TILE_BASE =
            "https://tilecache.rainviewer.com/v2/radar/";

    /**
     * Default tile zoom level.  Zoom 5 gives ~16 tiles for a typical TAK viewport,
     * each ~1250 km wide — good coverage without excessive bandwidth.
     */
    public static final int TILE_ZOOM = 5;

    // Since Jan 2026: only color scheme 2 (Universal Blue) and max zoom 7
    /** Maximum tile zoom supported by the RainViewer API (reduced from 12 since Jan 2026). */
    public static final int MAX_TILE_ZOOM = 7;

    /**
     * Build a RainViewer tile URL using path-based format (2024+ API).
     * The frame path is obtained from the manifest (e.g., "/v2/radar/bcadf9eebd3b").
     */
    public static String tileUrl(String host, String path, int z, int x, int y) {
        return host + path + "/256/" + z + "/" + x + "/" + y + "/2/1_1.png";
    }

    /**
     * Legacy tile URL builder (pre-2024 API with timestamp in URL).
     * @deprecated Use {@link #tileUrl(String, String, int, int, int)} instead.
     */
    @Deprecated
    public static String tileUrl(long unixTs, int z, int x, int y) {
        return TILE_BASE + unixTs + "/256/" + z + "/" + x + "/" + y + "/2/1_1.png";
    }

    /** LruCache key for a radar tile. */
    public static String cacheKey(long ts, int z, int x, int y) {
        return ts + "_" + z + "_" + x + "_" + y;
    }

    // ── Web Mercator coordinate helpers ───────────────────────────────────────

    public static int lonToTileX(double lon, int z) {
        return (int) Math.floor((lon + 180.0) / 360.0 * (1 << z));
    }

    public static int latToTileY(double lat, int z) {
        double latR = Math.toRadians(lat);
        return (int) Math.floor(
                (1.0 - Math.log(Math.tan(latR) + 1.0 / Math.cos(latR)) / Math.PI)
                        / 2.0 * (1 << z));
    }

    /** West-edge longitude of tile column x at zoom z (degrees). */
    public static double tileWestLon(int x, int z) {
        return x / (double)(1 << z) * 360.0 - 180.0;
    }

    /** North-edge latitude of tile row y at zoom z (degrees). */
    public static double tileNorthLat(int y, int z) {
        double n = Math.PI - 2.0 * Math.PI * y / (1 << z);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }
}
