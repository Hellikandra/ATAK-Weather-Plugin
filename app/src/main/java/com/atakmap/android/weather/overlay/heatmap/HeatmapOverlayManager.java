package com.atakmap.android.weather.overlay.heatmap;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.weather.data.remote.WeatherSourceManager;
import com.atakmap.android.weather.data.remote.schema.ModelMetadata;
import com.atakmap.android.weather.data.remote.schema.WeatherSourceDefinitionV2;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoBounds;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Central orchestrator for the weather heatmap overlay.
 *
 * <p>Manages the full lifecycle: grid computation, batch data fetching,
 * bitmap rendering, and display on the ATAK map. Follows the same pattern
 * as {@link com.atakmap.android.weather.overlay.radar.RadarOverlayManager}.</p>
 *
 * <h3>Lifecycle</h3>
 * <pre>
 *   start()         — begin listening to viewport changes, show initial heatmap
 *   setParameter()  — change the displayed weather parameter
 *   setHourIndex()  — time scrub (forecast replay)
 *   setOpacity()    — adjust transparency
 *   stop()          — remove overlay and listeners
 *   dispose()       — full teardown
 * </pre>
 *
 * <h3>Threading</h3>
 * <ul>
 *   <li>Data fetching: background via HttpClient's executor</li>
 *   <li>Bitmap rendering: background via dedicated single-thread pool</li>
 *   <li>All View mutations: main thread via Handler</li>
 *   <li>Viewport change debounced by 2 seconds</li>
 * </ul>
 */
public class HeatmapOverlayManager {

    private static final String TAG = "HeatmapOverlayMgr";

    /** Debounce delay for viewport changes (milliseconds). */
    private static final long VIEWPORT_DEBOUNCE_MS = 2000;

    /** Default model resolution (GFS = 13km). */
    private static final double DEFAULT_MODEL_RES_KM = 13.0;

    /** Default accuracy radius. */
    private static final double DEFAULT_ACCURACY_KM = 5.0;

    /** Bitmap output size. */
    private static final int BITMAP_SIZE = 512;

    // ── Listener ────────────────────────────────────────────────────────────

    public interface Listener {
        void onDataLoaded(int hoursCount, String[] paramKeys);
        void onFrameDisplayed(int hourIndex, String timeLabel);
        void onError(String message);
    }

    /** State listener for Overlay Manager integration. */
    public interface ActiveStateListener {
        void onActiveChanged(boolean active);
    }

    // ── Fields ──────────────────────────────────────────────────────────────

    private final MapView              mapView;
    private final Context              context;
    private final HeatmapTileCache     cache;
    private final HeatmapBatchFetcher  fetcher;
    private final ExecutorService      renderExec = Executors.newSingleThreadExecutor();
    private final Handler              handler    = new Handler(Looper.getMainLooper());
    private final AtomicBoolean        active     = new AtomicBoolean(false);

    private HeatmapDataSet currentDataSet;
    private String         activeParameter = "temperature_2m";
    private int            currentHourIndex = 0;
    private float          opacity = 0.6f;

    private HeatmapOverlayView overlayView;
    private Runnable            pendingRefresh;
    private Listener            listener;
    private ActiveStateListener activeStateListener;

    /** Viewport listener for debounced re-fetch on pan/zoom. */
    private MapView.OnMapMovedListener mapMovedListener;

    // ── Constructor ─────────────────────────────────────────────────────────

    public HeatmapOverlayManager(MapView mapView, Context context) {
        this.mapView = mapView;
        this.context = context;
        this.cache   = new HeatmapTileCache(context);
        this.fetcher = new HeatmapBatchFetcher();
    }

    // ── Public API ──────────────────────────────────────────────────────────

    public void setListener(Listener l) { this.listener = l; }

    public void setActiveStateListener(ActiveStateListener l) {
        this.activeStateListener = l;
    }

    public boolean isActive() { return active.get(); }

    /** Begin listening to viewport changes and display initial heatmap. */
    public void start() {
        if (!active.compareAndSet(false, true)) return;
        Log.d(TAG, "Starting heatmap overlay");

        mapView.post(() -> {
            if (overlayView == null) {
                overlayView = new HeatmapOverlayView(mapView);
            }
            overlayView.attach();
        });

        // Register viewport change listener
        mapMovedListener = (v, animate) -> scheduleRefresh();
        mapView.addOnMapMovedListener(mapMovedListener);

        // Initial fetch
        onViewportChanged();
        notifyActiveChanged(true);
    }

    /** Remove overlay and stop listening. */
    public void stop() {
        if (!active.compareAndSet(true, false)) return;
        Log.d(TAG, "Stopping heatmap overlay");

        // Cancel pending refresh
        if (pendingRefresh != null) {
            handler.removeCallbacks(pendingRefresh);
            pendingRefresh = null;
        }

        // Remove viewport listener
        if (mapMovedListener != null) {
            mapView.removeOnMapMovedListener(mapMovedListener);
            mapMovedListener = null;
        }

        mapView.post(() -> {
            if (overlayView != null) {
                overlayView.clearHeatmap();
                overlayView.detach();
            }
        });

        notifyActiveChanged(false);
    }

    /**
     * Force a fresh data fetch for the current viewport.
     * Clears existing overlay, discards cached dataset, and re-fetches from network.
     */
    public void forceRefresh() {
        if (!active.get()) return;
        // Clear the current display immediately
        mapView.post(() -> {
            if (overlayView != null) overlayView.clearHeatmap();
        });
        // Discard cached dataset so next render fetches fresh
        currentDataSet = null;
        // Trigger viewport re-fetch
        onViewportChanged();
    }

    /** Full teardown. Call from WeatherMapComponent.onDestroyImpl(). */
    public void dispose() {
        stop();
        renderExec.shutdownNow();
        if (cache != null) cache.dispose();
        overlayView = null;
    }

    /** Toggle visibility from the Overlay Manager. */
    public void setVisibleFromOverlayManager(boolean visible) {
        if (visible) start(); else stop();
    }

    /** Change the displayed weather parameter. */
    public void setParameter(String paramKey) {
        if (paramKey == null || paramKey.equals(activeParameter)) return;
        this.activeParameter = paramKey;
        Log.d(TAG, "Parameter changed to: " + paramKey);
        renderAndDisplay();
    }

    /** Set the current forecast hour index (time scrub). */
    public void setHourIndex(int hour) {
        if (currentDataSet == null) return;
        int clamped = Math.max(0, Math.min(hour,
                currentDataSet.getHoursCount() - 1));
        this.currentHourIndex = clamped;
        renderAndDisplay();
    }

    /** Set overlay opacity (0.0 - 1.0). */
    public void setOpacity(float alpha) {
        this.opacity = Math.max(0f, Math.min(1f, alpha));
        int alphaByte = Math.round(opacity * 255f);
        mapView.post(() -> {
            if (overlayView != null) overlayView.setOverlayAlpha(alphaByte);
        });
    }

    public String getActiveParameter() { return activeParameter; }
    public int getCurrentHourIndex() { return currentHourIndex; }
    public float getOpacity() { return opacity; }

    public HeatmapDataSet getCurrentDataSet() { return currentDataSet; }

    /** Get available parameter keys from the current data set. */
    public String[] getAvailableParameters() {
        if (currentDataSet == null) return new String[0];
        return currentDataSet.getParameters().toArray(new String[0]);
    }

    /** Get the cache instance (for cache management UI). */
    public HeatmapTileCache getCache() { return cache; }

    // ── Viewport handling ───────────────────────────────────────────────────

    /**
     * Schedule a debounced viewport refresh.
     * Cancels any pending refresh and schedules a new one after the debounce delay.
     */
    private void scheduleRefresh() {
        if (!active.get()) return;
        if (pendingRefresh != null) {
            handler.removeCallbacks(pendingRefresh);
        }
        pendingRefresh = this::onViewportChanged;
        handler.postDelayed(pendingRefresh, VIEWPORT_DEBOUNCE_MS);
    }

    /**
     * Called when the viewport changes (debounced).
     * Checks cache for coverage, fetches if needed, then renders.
     */
    private void onViewportChanged() {
        if (!active.get()) return;

        GeoBounds bounds = mapView.getBounds();
        if (bounds == null) return;

        double n = bounds.getNorth();
        double s = bounds.getSouth();
        double e = bounds.getEast();
        double w = bounds.getWest();

        // Check if current data set covers the viewport
        if (currentDataSet != null && currentDataSet.coversBounds(n, s, e, w)) {
            renderAndDisplay();
            return;
        }

        // Check cache
        String sourceId = getActiveSourceId();
        HeatmapDataSet cached = cache.retrieve(sourceId, n, s, e, w);
        if (cached != null) {
            Log.d(TAG, "Cache hit for viewport");
            currentDataSet = cached;
            renderAndDisplay();
            notifyDataLoaded();
            return;
        }

        // Fetch new data
        fetchForViewport(n, s, e, w);
    }

    /**
     * Fetch heatmap data for the given viewport bounds.
     */
    private void fetchForViewport(double n, double s, double e, double w) {
        double modelRes = DEFAULT_MODEL_RES_KM;
        double accuracy = DEFAULT_ACCURACY_KM;

        // Try to get model metadata from the active source
        WeatherSourceManager srcMgr = WeatherSourceManager.getInstance(context);
        String sourceId = srcMgr.getActiveSourceId();
        // The source definition might have model metadata
        // For now, use defaults

        // Add 10% padding to viewport for smoother panning
        double latPad = (n - s) * 0.1;
        double lonPad = (e - w) * 0.1;
        double pn = n + latPad;
        double ps = s - latPad;
        double pe = e + lonPad;
        double pw = w - lonPad;

        GridSpec grid = HeatmapGridCalculator.computeGrid(
                pn, ps, pe, pw, modelRes, accuracy);

        Log.d(TAG, "Fetching grid: " + grid.getRows() + "x" + grid.getCols()
                + " = " + grid.getTotalPoints() + " pts"
                + (grid.isBeyondResolution() ? " [BEYOND RES]" : ""));

        fetcher.fetchGrid(grid, null, context, new HeatmapBatchFetcher.Callback() {
            @Override
            public void onResult(HeatmapDataSet dataSet) {
                if (!active.get()) return;
                currentDataSet = dataSet;

                // Cache the result
                String sid = getActiveSourceId();
                renderExec.submit(() -> cache.store(sid, dataSet));

                renderAndDisplay();
                notifyDataLoaded();
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Fetch error: " + error);
                if (listener != null) {
                    handler.post(() -> listener.onError(error));
                }
            }
        });
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    /**
     * Render the current parameter + hour from the data set and display on map.
     */
    private void renderAndDisplay() {
        if (!active.get() || currentDataSet == null) return;

        final String param = activeParameter;
        final int hour = currentHourIndex;
        final double[][] gridValues = currentDataSet.getGrid(param, hour);

        if (gridValues == null) {
            Log.w(TAG, "No data for " + param + " @ hour " + hour);
            return;
        }

        final ColourScale scale = ColourScale.forParameter(param);
        final boolean beyondRes = currentDataSet.getGrid().isBeyondResolution();
        final float alpha = opacity;
        final GridSpec grid = currentDataSet.getGrid();

        renderExec.submit(() -> {
            if (!active.get()) return;

            Bitmap bmp = HeatmapRenderer.render(
                    gridValues, scale,
                    BITMAP_SIZE, BITMAP_SIZE,
                    alpha, beyondRes);

            if (bmp == null) return;

            handler.post(() -> {
                if (!active.get() || overlayView == null) return;
                overlayView.setHeatmap(bmp,
                        grid.getNorth(), grid.getSouth(),
                        grid.getEast(), grid.getWest());

                // Notify time label
                String timeLabel = "";
                String[] labels = currentDataSet.getTimeLabels();
                if (hour >= 0 && hour < labels.length) {
                    timeLabel = labels[hour];
                }
                if (listener != null) {
                    listener.onFrameDisplayed(hour, timeLabel);
                }
            });
        });
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private String getActiveSourceId() {
        try {
            return WeatherSourceManager.getInstance(context).getActiveSourceId();
        } catch (Exception e) {
            return "open_meteo";
        }
    }

    private void notifyDataLoaded() {
        if (listener == null || currentDataSet == null) return;
        String[] params = currentDataSet.getParameters().toArray(new String[0]);
        int hours = currentDataSet.getHoursCount();
        handler.post(() -> {
            if (listener != null) listener.onDataLoaded(hours, params);
        });
    }

    private void notifyActiveChanged(boolean isActive) {
        handler.post(() -> {
            if (activeStateListener != null) {
                activeStateListener.onActiveChanged(isActive);
            }
        });
    }
}
