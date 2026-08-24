package com.atakmap.android.weather.overlay.marine;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.view.View;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.weather.overlay.wind.NativeWindParticle;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Marine current particle renderer — completely independent from wind particles.
 *
 * <p>Own native C++ engine instance, own alpha-decay bitmap, own color ramp.
 * Can run simultaneously with wind particles without interference.</p>
 *
 * <p>Color ramp optimized for ocean currents (0-2 m/s):</p>
 * <ul>
 *   <li>Dark navy (0-0.1 m/s) — still water</li>
 *   <li>Blue (0.1-0.3 m/s) — weak current</li>
 *   <li>Light blue (0.3-0.5 m/s) — moderate</li>
 *   <li>Cyan (0.5-1.0 m/s) — strong</li>
 *   <li>White (1.0+ m/s) — very strong</li>
 * </ul>
 */
public class MarineParticleBitmapView extends View {

    private static final String TAG = "MarineParticleBmp";

    private final MapView mapView;
    private final MarineParticleLayer dataLayer;
    private final Paint dotPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fadePaint   = new Paint();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    // Own native engine instance
    private long nativePtr = 0;
    private boolean nativeReady = false;
    private long lastDataGen = -1;
    private boolean nativeHasData = false;

    /** Coastline mask for particle land/water validation. */
    private CoastlineMask coastlineMask;

    // Alpha-decay trail bitmap
    private Bitmap trailBitmap;
    private Canvas trailCanvas;

    // Config tracking
    private int    lastCount = -1;
    private float  lastSpeed = -1;
    private int    lastTrail = -1;
    private float  lastIntensity = -1;
    private float  lastSaturation = -1;
    private float  lastBrightness = -1;

    // JNI buffer
    private FloatBuffer geoBuf;
    private static final int MAX_PARTICLES = 3000;

    private final GeoPoint tempGeo = GeoPoint.createMutable();
    /** Reusable screen point — mapView.forward() allocates new PointF each call, so we cache the values. */
    private float lastScreenX, lastScreenY;
    private int frameCount = 0;

    private MapView.OnMapMovedListener mapMovedListener;
    private double lastCenterLat = Double.NaN, lastCenterLon = Double.NaN;
    private double lastMapRes = Double.NaN;
    private boolean mapMoved = false;

    // Ocean current color ramp (0-2 m/s range)
    private static final float[][] CURRENT_COLORS = {
            {0.06f, 0.19f, 0.38f},  // 0-0.1 m/s: dark navy
            {0.19f, 0.44f, 0.75f},  // 0.1-0.3 m/s: blue
            {0.25f, 0.63f, 0.88f},  // 0.3-0.5 m/s: light blue
            {0.38f, 0.82f, 0.88f},  // 0.5-1.0 m/s: cyan
            {0.82f, 0.94f, 0.94f},  // 1.0+ m/s: near-white
    };
    private static final float[] CURRENT_THRESHOLDS = {0.1f, 0.3f, 0.5f, 1.0f};

    public MarineParticleBitmapView(MapView mapView, MarineParticleLayer dataLayer) {
        super(mapView.getContext());
        this.mapView = mapView;
        this.dataLayer = dataLayer;
        setWillNotDraw(false);
        setLayoutParams(new MapView.LayoutParams(
                MapView.LayoutParams.MATCH_PARENT,
                MapView.LayoutParams.MATCH_PARENT));

        // Set up alpha-decay paint
        fadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));

        // Initialize native engine — separate instance from wind
        if (NativeWindParticle.isAvailable()) {
            nativePtr = NativeWindParticle.nCreate();
            nativeReady = (nativePtr != 0);
            if (nativeReady) {
                Log.d(TAG, "Marine native engine created (ptr=" + nativePtr + ")");
                geoBuf = ByteBuffer.allocateDirect(MAX_PARTICLES * 3 * 4)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer();
                // Push marine defaults
                NativeWindParticle.nSetParticleCount(nativePtr, dataLayer.getParticleCount());
                NativeWindParticle.nSetParticleSpeed(nativePtr, dataLayer.getParticleSpeed());
                NativeWindParticle.nSetTrailLength(nativePtr, (int) dataLayer.getParticleLife());
            }
        }
    }

    /** Set the coastline mask for land/water particle validation. */
    public void setCoastlineMask(CoastlineMask mask) {
        this.coastlineMask = mask;
    }

    // ── Attach / Detach ──────────────────────────────────────────────────

    public void attach() {
        if (getParent() != null) return;
        mapView.addView(this);
        mapMovedListener = (v, animate) -> {
            mapMoved = true;
            postInvalidate();
        };
        mapView.addOnMapMovedListener(mapMovedListener);
        setVisibility(VISIBLE);
        Log.d(TAG, "Marine particles attached");
    }

    public void detach() {
        if (mapMovedListener != null) {
            mapView.removeOnMapMovedListener(mapMovedListener);
            mapMovedListener = null;
        }
        if (getParent() != null) {
            mapView.removeView(this);
        }
        // Recycle trail bitmap
        if (trailBitmap != null && !trailBitmap.isRecycled()) {
            trailBitmap.recycle();
            trailBitmap = null;
            trailCanvas = null;
        }
        Log.d(TAG, "Marine particles detached");
    }

    public void destroy() {
        detach();
        if (nativePtr != 0) {
            NativeWindParticle.nDestroy(nativePtr);
            nativePtr = 0;
            nativeReady = false;
        }
    }

    // ── Data push ────────────────────────────────────────────────────────

    /** Push marine current data to the native engine. */
    public void pushCurrentData() {
        if (!nativeReady || nativePtr == 0 || !dataLayer.hasData()) return;

        double[][] speed = dataLayer.getWindSpeed();
        double[][] dir = dataLayer.getWindDirection();
        if (speed == null || dir == null) return;

        int rows = dataLayer.getGridRows();
        int cols = dataLayer.getGridCols();
        if (rows <= 0 || cols <= 0) return;

        // Build flat arrays with DTED/SRTM land masking:
        // If elevation > 0m at a grid cell, zero out the speed → particle won't move there
        double gridN = dataLayer.getGridNorth(), gridS = dataLayer.getGridSouth();
        double gridW = dataLayer.getGridWest(), gridE = dataLayer.getGridEast();
        double latStep = (rows > 1) ? (gridN - gridS) / (rows - 1) : 0;
        double lonStep = (cols > 1) ? (gridE - gridW) / (cols - 1) : 0;

        int landMasked = 0;
        double[] flatSpeed = new double[rows * cols];
        double[] flatDir = new double[rows * cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                double s = speed[r][c];
                double d = dir[r][c];
                int idx = r * cols + c;

                if (Double.isNaN(s) || Double.isNaN(d)) {
                    flatSpeed[idx] = 0;
                    flatDir[idx] = 0;
                    landMasked++;
                    continue;
                }

                // DTED/SRTM land check: if elevation clearly above sea level, mask as land.
                // Use threshold of 10m to avoid masking coastal/tidal zones.
                // Only mask if the API also reports low speed (< 0.01 m/s) — if API
                // reports actual current velocity, trust it over DTED at grid resolution.
                double cellLat = gridS + r * latStep;
                double cellLon = gridW + c * lonStep;
                try {
                    double elev = com.atakmap.map.elevation.ElevationManager.getElevation(
                            cellLat, cellLon, null);
                    if (!Double.isNaN(elev) && elev > 10 && s < 0.01) {
                        // Clearly land AND no significant current — mask it
                        flatSpeed[idx] = 0;
                        flatDir[idx] = 0;
                        landMasked++;
                        continue;
                    }
                } catch (Exception ignored) {
                    // No DTED coverage — trust API data
                }

                flatSpeed[idx] = s;
                flatDir[idx] = d;
            }
        }
        Log.d(TAG, "Land mask: " + landMasked + "/" + (rows*cols)
                + " cells masked (DTED + NaN)");

        NativeWindParticle.nSetWindField(nativePtr, flatSpeed, flatDir,
                rows, cols,
                dataLayer.getGridNorth(), dataLayer.getGridSouth(),
                dataLayer.getGridWest(), dataLayer.getGridEast());
        nativeHasData = true;

        Log.d(TAG, "Marine current data pushed: " + rows + "×" + cols
                + ", streamlines=" + NativeWindParticle.nGetStreamlineCount(nativePtr));
    }

    // ── Drawing ──────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        if (!dataLayer.isShowParticles()) return;
        if (!nativeReady || nativePtr == 0) return;

        // Push config changes
        pushConfigIfChanged();

        // Push viewport bounds for particle seeding
        com.atakmap.coremap.maps.coords.GeoBounds bounds = mapView.getBounds();
        if (bounds != null) {
            NativeWindParticle.nSetViewportBounds(nativePtr,
                    bounds.getNorth(), bounds.getSouth(),
                    bounds.getWest(), bounds.getEast());
        }

        // Push data if generation changed
        if (dataLayer.hasData()) {
            long gen = dataLayer.getStreamlineGeneration();
            if (gen != lastDataGen) {
                pushCurrentData();
                lastDataGen = gen;
            }
        }

        if (!nativeHasData) return;

        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        // Create/resize trail bitmap
        if (trailBitmap == null || trailBitmap.getWidth() != w || trailBitmap.getHeight() != h) {
            if (trailBitmap != null) trailBitmap.recycle();
            trailBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            trailCanvas = new Canvas(trailBitmap);
        }

        // Alpha-decay fade
        int fadeAlpha = dataLayer.getTrailFadeAlpha();
        fadePaint.setColor(Color.argb(fadeAlpha, 0, 0, 0));
        trailCanvas.drawPaint(fadePaint);

        // Clear on map movement
        if (mapMoved) {
            double cLat = mapView.getLatitude(), cLon = mapView.getLongitude();
            double res = mapView.getMapResolution();
            if (cLat != lastCenterLat || cLon != lastCenterLon || res != lastMapRes) {
                trailBitmap.eraseColor(Color.TRANSPARENT);
                lastCenterLat = cLat;
                lastCenterLon = cLon;
                lastMapRes = res;
            }
            mapMoved = false;
        }

        // Advance particles in native (returns active count)
        int activeCount = NativeWindParticle.nAdvanceGeo(nativePtr);
        if (activeCount <= 0) {
            canvas.drawBitmap(trailBitmap, 0, 0, bitmapPaint);
            postInvalidateDelayed(33);
            return;
        }

        // Copy geo positions to Java buffer
        geoBuf.clear();
        NativeWindParticle.nCopyGeoBuffer(nativePtr, geoBuf);
        geoBuf.position(0);

        // Draw particles
        float lineW = dataLayer.getTrailLineWidth()
                * getContext().getResources().getDisplayMetrics().density;
        float intensity = dataLayer.getColorIntensity();
        float saturation = dataLayer.getColorSaturation();
        float brightness = dataLayer.getColorValue();

        int drawnCount = 0;
        dotPaint.setStrokeWidth(lineW);
        dotPaint.setStrokeCap(Paint.Cap.ROUND);

        for (int i = 0; i < activeCount && geoBuf.remaining() >= 3; i++) {
            float lat = geoBuf.get();
            float lon = geoBuf.get();
            float spd = geoBuf.get();

            if (lat == 0 && lon == 0) continue;
            if (spd <= 0.001f) continue; // land point — no current data
            // Coastline mask check — skip particles on land
            if (coastlineMask != null && !coastlineMask.isWater(lat, lon)) continue;

            tempGeo.set(lat, lon);
            PointF screenPt = mapView.forward(tempGeo);
            if (screenPt == null) continue;

            float sx = screenPt.x, sy = screenPt.y;
            if (sx < -50 || sx > w + 50 || sy < -50 || sy > h + 50) continue;

            float ageFrac = 1f; // full brightness for marine
            int color = speedToColor(spd, intensity * ageFrac, saturation, brightness);
            dotPaint.setColor(color);
            trailCanvas.drawCircle(sx, sy, lineW * 0.5f, dotPaint);
            drawnCount++;
        }

        // Composite trail onto map
        canvas.drawBitmap(trailBitmap, 0, 0, bitmapPaint);

        frameCount++;
        if (frameCount % 60 == 0) {
            Log.d(TAG, "Frame: active=" + activeCount + ", drawn=" + drawnCount
                    + "/" + dataLayer.getParticleCount()
                    + ", size=" + w + "×" + h);
        }

        postInvalidateDelayed(33);
    }

    // ── Config push ──────────────────────────────────────────────────────

    private void pushConfigIfChanged() {
        int count = dataLayer.getParticleCount();
        float speed = dataLayer.getParticleSpeed();
        int trail = (int) dataLayer.getParticleLife();
        float intensity = dataLayer.getColorIntensity();
        float sat = dataLayer.getColorSaturation();
        float bright = dataLayer.getColorValue();

        if (count != lastCount) { NativeWindParticle.nSetParticleCount(nativePtr, count); lastCount = count; }
        if (speed != lastSpeed) { NativeWindParticle.nSetParticleSpeed(nativePtr, speed); lastSpeed = speed; }
        if (trail != lastTrail) { NativeWindParticle.nSetTrailLength(nativePtr, trail); lastTrail = trail; }
        if (intensity != lastIntensity) { NativeWindParticle.nSetColorIntensity(nativePtr, intensity); lastIntensity = intensity; }
        if (sat != lastSaturation) { NativeWindParticle.nSetColorSaturation(nativePtr, sat); lastSaturation = sat; }
        if (bright != lastBrightness) { NativeWindParticle.nSetColorBrightness(nativePtr, bright); lastBrightness = bright; }
    }

    // ── Color ramp (ocean current: 0-2 m/s) ─────────────────────────────

    private static int speedToColor(float speed, float intensity, float saturation, float brightness) {
        float r, g, b;

        if (Float.isNaN(speed) || speed <= 0 || speed < CURRENT_THRESHOLDS[0]) {
            r = CURRENT_COLORS[0][0]; g = CURRENT_COLORS[0][1]; b = CURRENT_COLORS[0][2];
        } else if (speed >= CURRENT_THRESHOLDS[CURRENT_THRESHOLDS.length - 1]) {
            int last = CURRENT_COLORS.length - 1;
            r = CURRENT_COLORS[last][0]; g = CURRENT_COLORS[last][1]; b = CURRENT_COLORS[last][2];
        } else {
            int band = 1;
            for (int i = 1; i < CURRENT_THRESHOLDS.length; i++) {
                if (speed < CURRENT_THRESHOLDS[i]) { band = i; break; }
            }
            int lo = Math.max(0, band - 1);
            int hi = Math.min(CURRENT_COLORS.length - 1, band);
            int tLo = Math.max(0, band - 1);
            int tHi = Math.min(CURRENT_THRESHOLDS.length - 1, band);
            float range = CURRENT_THRESHOLDS[tHi] - CURRENT_THRESHOLDS[tLo];
            float t = (range > 0) ? (speed - CURRENT_THRESHOLDS[tLo]) / range : 0f;
            t = Math.max(0f, Math.min(1f, t));
            r = CURRENT_COLORS[lo][0] + t * (CURRENT_COLORS[hi][0] - CURRENT_COLORS[lo][0]);
            g = CURRENT_COLORS[lo][1] + t * (CURRENT_COLORS[hi][1] - CURRENT_COLORS[lo][1]);
            b = CURRENT_COLORS[lo][2] + t * (CURRENT_COLORS[hi][2] - CURRENT_COLORS[lo][2]);
        }

        if (saturation < 1f) {
            float grey = 0.299f * r + 0.587f * g + 0.114f * b;
            r = grey + saturation * (r - grey);
            g = grey + saturation * (g - grey);
            b = grey + saturation * (b - grey);
        }
        r = Math.min(1f, r * brightness);
        g = Math.min(1f, g * brightness);
        b = Math.min(1f, b * brightness);

        int alpha = Math.round(Math.min(1f, intensity) * 255);
        return Color.argb(alpha,
                Math.round(r * 255), Math.round(g * 255), Math.round(b * 255));
    }
}
