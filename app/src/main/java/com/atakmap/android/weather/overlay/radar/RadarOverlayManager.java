package com.atakmap.android.weather.overlay.radar;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.weather.data.cache.RadarTileCache;
import com.atakmap.android.weather.data.remote.HttpClient;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoPoint;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RadarOverlayManager — RainViewer precipitation radar tiles displayed on the ATAK map.
 *
 * <h3>Rendering approach</h3>
 * Tiles are rendered by {@link RadarOverlayView}, a transparent {@code View} added on top
 * of MapView. {@code onDraw()} converts each tile's geo-corners to screen pixels via
 * manual Mercator projection, then scales the bitmap to that rect.
 *
 * <h3>Caching architecture (Sprint 1.1b)</h3>
 * Two-level cache inspired by ATAK's {@code TileProxy} + {@code MBTilesContainer}:
 * <ul>
 *   <li><b>L1:</b> In-memory {@link LruCache}&lt;String, Bitmap&gt; — 40 entries,
 *       fast hot-path for animation frame cycling.</li>
 *   <li><b>L2:</b> {@link RadarTileCache} — SQLite-backed persistent cache with
 *       per-tile expiration (15 min default), async buffered writes, and
 *       LRU eviction at 50 MB.</li>
 * </ul>
 *
 * Read path: L1 → L2 → HTTP.  On L2 hit, tile is promoted to L1.
 * On HTTP success, tile is written to both L1 and L2 simultaneously.
 * On network failure, L2 serves expired (stale) tiles for offline fallback.
 *
 * <h3>Changes vs original</h3>
 * <ul>
 *   <li>{@link #isActive()} added — used by {@code RadarMapOverlay.RadarHierarchyListItem}
 *       to keep the Overlay Manager toggle in sync with actual state.</li>
 *   <li>{@link #setVisibleFromOverlayManager(boolean)} added — lets the Overlay Manager
 *       toggle start/stop without going through the DDR, keeping both in sync.</li>
 *   <li><b>Sprint 1.1b:</b> L2 {@link RadarTileCache} integrated for persistent
 *       disk caching, offline fallback, and cache management UI support.</li>
 * </ul>
 *
 * <h3>Lifecycle</h3>
 * <pre>
 *   start()         — fetch manifest, attach overlay view, show latest frame
 *   setFrameIndex() — switch frame (instant if cached, else downloads)
 *   setOpacity()    — transparency 0–100 %
 *   stop()          — clear tiles, detach overlay view
 *   dispose()       — full teardown (call from WeatherMapComponent.onDestroyImpl)
 * </pre>
 *
 * Thread model: downloads on 4-thread pool; all View mutations on main thread.
 * L1 LruCache: 40 tiles × ~65 KB ≈ 2.6 MB.
 * L2 RadarTileCache: SQLite, 50 MB cap, 15-min per-tile expiry.
 */
public class RadarOverlayManager {

    private static final String TAG     = "RadarOverlayManager";
    private static final int    THREADS = 4;
    private static final int    LRU_MAX = 40;

    // ── Listener ─────────────────────────────────────────────────────────────

    public interface Listener {
        void onManifestLoaded(int totalFrames, int defaultIndex);
        void onFrameDisplayed(int index, String timeLabel);
        void onDiagnosticsUpdated(String info);
        void onError(String message);
    }

    /** Optional state listener — notified when active state changes via Overlay Manager. */
    public interface ActiveStateListener {
        void onActiveChanged(boolean active);
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final MapView             mapView;
    private final ExecutorService     exec    = Executors.newFixedThreadPool(THREADS);
    private final LruCache<String, Bitmap> memoryCache = new LruCache<>(LRU_MAX);
    private final AtomicBoolean       active   = new AtomicBoolean(false);
    private final AtomicInteger       frameIdx = new AtomicInteger(0);

    /** L2 disk cache — null if not injected (graceful degradation). */
    private RadarTileCache diskCache;

    /** Configurable radar source — defaults to bundled RainViewer values. */
    private String manifestUrl     = RadarTileProvider.MANIFEST_URL;
    private String tileUrlTemplate = null;  // null = use RadarTileProvider.tileUrl()

    private List<Long> pastTs    = Collections.emptyList();
    private List<Long> nowcastTs = Collections.emptyList();
    private List<Long> allTs     = Collections.emptyList();

    private int              overlayAlpha = 153;  // 60 %
    private Listener         listener;
    private ActiveStateListener activeStateListener;
    private RadarOverlayView overlayView;

    // ── Constructor ───────────────────────────────────────────────────────────

    public RadarOverlayManager(MapView mapView) {
        this.mapView = mapView;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Inject the L2 disk cache. Call once from {@code WeatherMapComponent.onCreate()}.
     * If never called, the manager degrades to L1-only (original behaviour).
     */
    public void setDiskCache(RadarTileCache cache) {
        this.diskCache = cache;
    }

    /** Return the L2 disk cache (for cache management UI). May be null. */
    public RadarTileCache getDiskCache() {
        return diskCache;
    }

    public void setListener(Listener l) { this.listener = l; }

    /**
     * Register a listener notified when the overlay is started/stopped.
     * Used by the DDR Show/Hide buttons so they stay in sync with the
     * Overlay Manager toggle.
     */
    public void setActiveStateListener(ActiveStateListener l) {
        this.activeStateListener = l;
    }

    /**
     * Returns {@code true} when the overlay is currently running (manifest loaded
     * and tiles being displayed). Used by {@link RadarMapOverlay} to reflect the
     * correct visible state in the Overlay Manager.
     */
    public boolean isActive() { return active.get(); }

    /**
     * Called by the Overlay Manager list item to toggle radar on/off.
     * Notifies {@link ActiveStateListener} so the DDR buttons update.
     */
    public void setVisibleFromOverlayManager(boolean visible) {
        if (visible) start(); else stop();
    }

    /**
     * Switch to a different radar source definition.
     * If the overlay is currently active it is stopped, cache cleared, then
     * restarted with the new manifest URL so tiles refresh immediately.
     */
    public void setRadarSource(String newManifestUrl, String newTileUrlTemplate) {
        if (newManifestUrl == null || newManifestUrl.isEmpty()) return;
        boolean wasActive = active.get();
        if (wasActive) stop();
        memoryCache.evictAll();
        // Note: disk cache is NOT cleared on source switch — tiles from different
        // sources use different timestamps, so old entries will expire naturally.
        this.manifestUrl     = newManifestUrl;
        this.tileUrlTemplate = newTileUrlTemplate;
        if (wasActive) start();
    }

    public String getManifestUrl() { return manifestUrl; }

    public void start() {
        if (!active.compareAndSet(false, true)) return;
        // Purge expired L2 tiles on start (lightweight — index scan only)
        if (diskCache != null) {
            exec.submit(() -> diskCache.purgeExpired());
        }
        mapView.post(() -> {
            if (overlayView == null) overlayView = new RadarOverlayView(mapView);
            overlayView.attach();
        });
        fetchManifest();
        notifyActiveChanged(true);
    }

    public void stop() {
        if (!active.compareAndSet(true, false)) return;
        mapView.post(() -> {
            if (overlayView != null) {
                overlayView.clearTiles();
                overlayView.detach();
            }
        });
        notifyActiveChanged(false);
    }

    public void dispose() {
        stop();
        exec.shutdownNow();
        memoryCache.evictAll();
        // Note: diskCache lifecycle is managed by WeatherMapComponent, not here.
        overlayView = null;
    }

    public int  getFrameCount()      { return allTs.size(); }
    public int  getLatestPastIndex() { return Math.max(0, pastTs.size() - 1); }

    public String getFrameLabel(int index) {
        if (index < 0 || index >= allTs.size()) return "---";
        String t = new SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(new Date(allTs.get(index) * 1000L));
        return index >= pastTs.size() ? t + "  > NOWCAST" : t;
    }

    public void setFrameIndex(int index) {
        if (!active.get()) return;
        int clamped = Math.max(0, Math.min(index, allTs.size() - 1));
        frameIdx.set(clamped);
        displayFrame(clamped);
    }

    public void setOpacity(int percent) {
        overlayAlpha = Math.round(percent / 100f * 255);
        mapView.post(() -> { if (overlayView != null) overlayView.setAlpha(overlayAlpha); });
    }

    // ── Color controls (Sprint 1.1c) ─────────────────────────────────────────

    /**
     * Set all color components at once — delegates to {@link RadarOverlayView}.
     * Mirrors ATAK's {@code HeatMapOverlay.setColorComponents(saturation, value, alpha)}.
     *
     * @param saturation 0.0 = greyscale → 1.0 = full color
     * @param value      0.0 = black → 1.0 = full brightness
     * @param intensity  0.0 = invisible → 1.0 = fully opaque
     */
    public void setColorComponents(float saturation, float value, float intensity) {
        mapView.post(() -> { if (overlayView != null) overlayView.setColorComponents(saturation, value, intensity); });
    }

    /** Set saturation only (0–1). 0 = greyscale, 1 = full color. */
    public void setSaturation(float s) {
        mapView.post(() -> { if (overlayView != null) overlayView.setSaturation(s); });
    }

    /** Set value/brightness only (0–1). 0 = dark, 1 = full brightness. */
    public void setValue(float v) {
        mapView.post(() -> { if (overlayView != null) overlayView.setValue(v); });
    }

    /** Set intensity/overall-alpha only (0–1). 0 = invisible, 1 = fully opaque. */
    public void setIntensity(float i) {
        mapView.post(() -> { if (overlayView != null) overlayView.setIntensity(i); });
    }

    /**
     * Re-fetch tiles for the current map viewport at the current frame index.
     * Call when the user pans/zooms and wants fresh tiles for the new area.
     */
    public void refresh() {
        if (!active.get()) return;
        displayFrame(frameIdx.get());
    }

    // ── Manifest ──────────────────────────────────────────────────────────────

    private void fetchManifest() {
        HttpClient.get(manifestUrl, new HttpClient.Callback() {
            @Override public void onSuccess(String body) {
                if (!active.get()) return;
                try {
                    parseManifest(body);
                    if (allTs.isEmpty()) { emit("No radar frames in manifest"); return; }
                    int def = getLatestPastIndex();
                    frameIdx.set(def);
                    final int fDef = def;
                    notifyMain(() -> {
                        if (!active.get()) return;
                        if (listener != null) listener.onManifestLoaded(allTs.size(), fDef);
                    });
                    displayFrame(def);
                } catch (Exception e) {
                    Log.e(TAG, "Manifest parse", e);
                    emit("Manifest parse error: " + e.getMessage());
                }
            }
            @Override public void onFailure(String err) {
                if (active.get()) emit("Manifest fetch failed: " + err);
            }
        });
    }

    private void parseManifest(String json) throws Exception {
        JSONObject root  = new JSONObject(json);
        JSONObject radar = root.optJSONObject("radar");
        List<Long> past = new ArrayList<>(), nowcast = new ArrayList<>();
        if (radar != null) {
            JSONArray pArr = radar.optJSONArray("past");
            if (pArr != null) for (int i = 0; i < pArr.length(); i++) {
                long t = pArr.getJSONObject(i).optLong("time", 0);
                if (t > 0) past.add(t);
            }
            JSONArray nArr = radar.optJSONArray("nowcast");
            if (nArr != null) for (int i = 0; i < nArr.length(); i++) {
                long t = nArr.getJSONObject(i).optLong("time", 0);
                if (t > 0) nowcast.add(t);
            }
        }
        pastTs    = Collections.unmodifiableList(past);
        nowcastTs = Collections.unmodifiableList(nowcast);
        List<Long> all = new ArrayList<>(past); all.addAll(nowcast);
        allTs     = Collections.unmodifiableList(all);
        Log.d(TAG, "Manifest: " + past.size() + " past + " + nowcast.size() + " nowcast");
    }

    // ── Frame display ─────────────────────────────────────────────────────────

    private void displayFrame(int index) {
        if (index < 0 || index >= allTs.size()) return;
        final long ts = allTs.get(index);

        GeoBounds bounds = mapView.getBounds();
        if (bounds == null) return;

        int z    = RadarTileProvider.TILE_ZOOM;
        int xMin = RadarTileProvider.lonToTileX(bounds.getWest(),  z);
        int xMax = RadarTileProvider.lonToTileX(bounds.getEast(),  z);
        int yMin = RadarTileProvider.latToTileY(bounds.getNorth(), z);
        int yMax = RadarTileProvider.latToTileY(bounds.getSouth(), z);
        int max  = (1 << z) - 1;
        xMin = Math.max(0, xMin); xMax = Math.min(max, xMax);
        yMin = Math.max(0, yMin); yMax = Math.min(max, yMax);

        int tileW  = Math.max(0, xMax - xMin + 1);
        int tileH  = Math.max(0, yMax - yMin + 1);
        int nTiles = tileW * tileH;
        double tileDegW = 360.0 / (1 << z);
        double areaDegW = tileW * tileDegW;
        double areaDegH = tileH * tileDegW;
        double centreLat = (bounds.getNorth() + bounds.getSouth()) / 2.0;
        double centreLon = (bounds.getEast()  + bounds.getWest())  / 2.0;

        // Build diagnostics with cache stats
        String cacheInfo = "";
        if (diskCache != null) {
            cacheInfo = String.format(Locale.US,
                    "\nL2 cache: %s  (%d tiles)",
                    diskCache.getCacheSizeLabel(), diskCache.getTileCount());
        }
        final String diagText = String.format(Locale.US,
                "z=%d  tiles=%d (%dx%d)  area=%.1f°×%.1f°\ncenter=%.4f°N %.4f°E%s",
                z, nTiles, tileW, tileH, areaDegW, areaDegH, centreLat, centreLon,
                cacheInfo);
        notifyMain(() -> { if (listener != null) listener.onDiagnosticsUpdated(diagText); });

        final List<RadarOverlayView.Tile> ready   = new ArrayList<>();
        final List<int[]>                 missing = new ArrayList<>();

        for (int x = xMin; x <= xMax; x++) {
            for (int y = yMin; y <= yMax; y++) {
                String key = RadarTileProvider.cacheKey(ts, z, x, y);

                // L1: In-memory LRU
                Bitmap cached = memoryCache.get(key);
                if (cached != null) {
                    ready.add(makeTile(cached, z, x, y));
                    continue;
                }

                // L2: SQLite disk cache
                if (diskCache != null) {
                    Bitmap diskHit = diskCache.getTile(z, x, y, ts);
                    if (diskHit != null) {
                        memoryCache.put(key, diskHit); // promote to L1
                        ready.add(makeTile(diskHit, z, x, y));
                        continue;
                    }
                }

                missing.add(new int[]{x, y});
            }
        }

        notifyMain(() -> { if (overlayView != null) overlayView.setTiles(ready); });
        for (int[] xy : missing) {
            final int fx = xy[0], fy = xy[1];
            exec.submit(() -> downloadAndAdd(ts, z, fx, fy, ready));
        }

        final String label = getFrameLabel(index);
        notifyMain(() -> { if (listener != null) listener.onFrameDisplayed(index, label); });
    }

    private RadarOverlayView.Tile makeTile(Bitmap bmp, int z, int x, int y) {
        double west  = RadarTileProvider.tileWestLon(x,     z);
        double east  = RadarTileProvider.tileWestLon(x + 1, z);
        double north = RadarTileProvider.tileNorthLat(y,     z);
        double south = RadarTileProvider.tileNorthLat(y + 1, z);
        return new RadarOverlayView.Tile(
                bmp,
                new GeoPoint(north, west),
                new GeoPoint(south, east),
                overlayAlpha);
    }

    private void downloadAndAdd(long ts, int z, int x, int y,
                                List<RadarOverlayView.Tile> accumulator) {
        if (!active.get()) return;
        try {
            HttpURLConnection conn = (HttpURLConnection)
                    new URL(buildTileUrl(ts, z, x, y)).openConnection();
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(12_000);
            conn.setRequestProperty("User-Agent", "ATAK-WeatherPlugin/3.0");
            conn.connect();
            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream()) {
                    Bitmap bmp = BitmapFactory.decodeStream(is);
                    if (bmp == null) return;

                    String key = RadarTileProvider.cacheKey(ts, z, x, y);
                    memoryCache.put(key, bmp);                   // L1
                    if (diskCache != null) {
                        diskCache.putTile(z, x, y, ts, bmp);    // L2 (async buffered)
                    }

                    if (!active.get() || frameIdx.get() >= allTs.size()
                            || ts != allTs.get(frameIdx.get())) return;
                    RadarOverlayView.Tile tile = makeTile(bmp, z, x, y);
                    notifyMain(() -> {
                        if (overlayView == null) return;
                        accumulator.add(tile);
                        overlayView.setTiles(new ArrayList<>(accumulator));
                    });
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Tile download [" + z + "/" + x + "/" + y + "] failed: " + e.getMessage());

            // Offline fallback: serve expired (stale) tile from L2 if available
            if (diskCache != null) {
                Bitmap stale = diskCache.getStaleTile(z, x, y, ts);
                if (stale != null) {
                    Log.d(TAG, "Serving stale L2 tile [" + z + "/" + x + "/" + y + "]");
                    String key = RadarTileProvider.cacheKey(ts, z, x, y);
                    memoryCache.put(key, stale);
                    RadarOverlayView.Tile tile = makeTile(stale, z, x, y);
                    notifyMain(() -> {
                        if (overlayView == null) return;
                        accumulator.add(tile);
                        overlayView.setTiles(new ArrayList<>(accumulator));
                    });
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void notifyMain(Runnable r) { mapView.post(r); }
    private void emit(String msg)       { notifyMain(() -> { if (listener != null) listener.onError(msg); }); }

    private void notifyActiveChanged(boolean isActive) {
        notifyMain(() -> {
            if (activeStateListener != null) activeStateListener.onActiveChanged(isActive);
        });
    }

    /**
     * Build a tile URL for the given timestamp and tile coordinates.
     * Uses {@code tileUrlTemplate} if set, otherwise falls back to
     * {@link RadarTileProvider#tileUrl(long, int, int, int)}.
     *
     * Template placeholders: {@code {timestamp} {size} {z} {x} {y}}
     */
    private String buildTileUrl(long ts, int z, int x, int y) {
        if (tileUrlTemplate != null && !tileUrlTemplate.isEmpty()) {
            return tileUrlTemplate
                    .replace("{timestamp}", String.valueOf(ts))
                    .replace("{z}",         String.valueOf(z))
                    .replace("{x}",         String.valueOf(x))
                    .replace("{y}",         String.valueOf(y))
                    .replace("{size}",      "256");
        }
        return RadarTileProvider.tileUrl(ts, z, x, y);
    }
}
