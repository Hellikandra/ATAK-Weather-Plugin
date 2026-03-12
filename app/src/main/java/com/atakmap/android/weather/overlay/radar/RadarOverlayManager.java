package com.atakmap.android.weather.overlay.radar;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import com.atakmap.android.maps.MapView;
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
 * Tiles are rendered by RadarOverlayView, a transparent View added on top of
 * MapView.  onDraw() calls MapView.getProjection().forward() to convert each
 * tile's geo corners to screen pixels, then drawBitmap() scales to that rect.
 * This is the correct ATAK-plugin approach — the Marker/MapItem API cannot
 * stretch bitmaps to arbitrary geographic extents.
 *
 * Lifecycle:
 *   start()         — fetch manifest, attach overlay view, show latest frame
 *   setFrameIndex() — switch frame (instant if cached, else downloads)
 *   setOpacity()    — transparency 0-100 %
 *   stop()          — clear tiles, detach overlay view
 *   dispose()       — full teardown (call from disposeImpl)
 *
 * Thread model: downloads on 4-thread pool; all View mutations on main thread.
 * LruCache: 40 tiles x ~65 KB = ~2.6 MB.
 */
public class RadarOverlayManager {

    private static final String TAG     = "RadarOverlayManager";
    private static final int    THREADS = 4;
    private static final int    LRU_MAX = 40;

    // ---- Listener ------------------------------------------------------------

    public interface Listener {
        void onManifestLoaded(int totalFrames, int defaultIndex);
        void onFrameDisplayed(int index, String timeLabel);
        void onDiagnosticsUpdated(String info);
        void onError(String message);
    }

    // ---- Fields --------------------------------------------------------------

    private final MapView            mapView;
    private final ExecutorService    exec    = Executors.newFixedThreadPool(THREADS);
    private final LruCache<String, Bitmap> cache = new LruCache<>(LRU_MAX);
    private final AtomicBoolean      active  = new AtomicBoolean(false);
    private final AtomicInteger      frameIdx = new AtomicInteger(0);

    /** Configurable radar source — defaults to bundled RainViewer values. */
    private String manifestUrl     = RadarTileProvider.MANIFEST_URL;
    private String tileUrlTemplate = null; // null = use RadarTileProvider.tileUrl()

    private List<Long> pastTs    = Collections.emptyList();
    private List<Long> nowcastTs = Collections.emptyList();
    private List<Long> allTs     = Collections.emptyList();

    private int             overlayAlpha = 153;  // 60 %
    private Listener        listener;
    private RadarOverlayView overlayView;

    // ---- Constructor ---------------------------------------------------------

    public RadarOverlayManager(MapView mapView) {
        this.mapView = mapView;
    }

    // ---- Public API ----------------------------------------------------------

    public void setListener(Listener l) { this.listener = l; }

    /**
     * Switch to a different radar source definition.
     * If the overlay is currently active it is stopped, cache cleared, then restarted
     * with the new manifest URL so tiles refresh immediately.
     *
     * @param newManifestUrl     RainViewer-compatible manifest URL
     * @param newTileUrlTemplate Tile URL template with {timestamp}/{z}/{x}/{y} placeholders,
     *                           or null to keep using the default RadarTileProvider format.
     */
    public void setRadarSource(String newManifestUrl, String newTileUrlTemplate) {
        if (newManifestUrl == null || newManifestUrl.isEmpty()) return;
        boolean wasActive = active.get();
        if (wasActive) stop();
        cache.evictAll();
        this.manifestUrl     = newManifestUrl;
        this.tileUrlTemplate = newTileUrlTemplate;
        if (wasActive) start();
    }

    public String getManifestUrl() { return manifestUrl; }

    public void start() {
        if (!active.compareAndSet(false, true)) return;
        mapView.post(() -> {
            if (overlayView == null) overlayView = new RadarOverlayView(mapView);
            overlayView.attach();
        });
        fetchManifest();
    }

    public void stop() {
        active.set(false);
        mapView.post(() -> {
            if (overlayView != null) {
                overlayView.clearTiles();
                overlayView.detach();
            }
        });
    }

    public void dispose() {
        stop();
        exec.shutdownNow();
        cache.evictAll();
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

    /**
     * Re-fetch tiles for the current map viewport at the current frame index.
     * Call this when the user has panned/zoomed outside the original radar coverage
     * and wants to pull tiles for the new area.
     */
    public void refresh() {
        if (!active.get()) return;
        displayFrame(frameIdx.get());
    }

    // ---- Manifest ------------------------------------------------------------

    private void fetchManifest() {
        HttpClient.get(manifestUrl, new HttpClient.Callback() {
            @Override public void onSuccess(String body) {
                if (!active.get()) return;  // stop() was called before reply arrived
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

    // ---- Frame display -------------------------------------------------------

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

        // Emit tile diagnostics
        int tileW  = Math.max(0, xMax - xMin + 1);
        int tileH  = Math.max(0, yMax - yMin + 1);
        int nTiles = tileW * tileH;
        double tileDegW = 360.0 / (1 << z);  // degrees per tile at this zoom
        double areaDegW = tileW * tileDegW;
        double areaDegH = tileH * tileDegW;
        double centreLat = (bounds.getNorth() + bounds.getSouth()) / 2.0;
        double centreLon = (bounds.getEast()  + bounds.getWest())  / 2.0;
        String diagText = String.format(java.util.Locale.US,
                "z=%d  tiles=%d (%dx%d)  area=%.1f°×%.1f°\ncenter=%.4f°N %.4f°E",
                z, nTiles, tileW, tileH, areaDegW, areaDegH, centreLat, centreLon);
        notifyMain(() -> { if (listener != null) listener.onDiagnosticsUpdated(diagText); });
        yMin = Math.max(0, yMin); yMax = Math.min(max, yMax);

        final List<RadarOverlayView.Tile> ready = new ArrayList<>();
        final List<int[]>                 missing = new ArrayList<>();

        for (int x = xMin; x <= xMax; x++) {
            for (int y = yMin; y <= yMax; y++) {
                Bitmap cached = cache.get(RadarTileProvider.cacheKey(ts, z, x, y));
                if (cached != null) {
                    ready.add(makeTile(cached, z, x, y));
                } else {
                    missing.add(new int[]{x, y});
                }
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
                    cache.put(RadarTileProvider.cacheKey(ts, z, x, y), bmp);
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
        }
    }

    // ── Helpers -------------------------------------------------------------

    private void notifyMain(Runnable r) { mapView.post(r); }
    private void emit(String msg) { notifyMain(() -> { if (listener != null) listener.onError(msg); }); }

    /**
     * Build a tile URL for the given timestamp and tile coordinates.
     * Uses {@code tileUrlTemplate} if set (user-defined source), otherwise
     * falls back to {@link RadarTileProvider#tileUrl(long, int, int, int)}.
     *
     * Template placeholders: {timestamp} {size} {z} {x} {y}
     * Example: "https://tilecache.rainviewer.com/v2/radar/{timestamp}/512/{z}/{x}/{y}/4/1_1.png"
     */
    private String buildTileUrl(long ts, int z, int x, int y) {
        if (tileUrlTemplate != null && !tileUrlTemplate.isEmpty()) {
            return tileUrlTemplate
                    .replace("{timestamp}", String.valueOf(ts))
                    .replace("{z}", String.valueOf(z))
                    .replace("{x}", String.valueOf(x))
                    .replace("{y}", String.valueOf(y))
                    .replace("{size}", "256");
        }
        return RadarTileProvider.tileUrl(ts, z, x, y);
    }
}
