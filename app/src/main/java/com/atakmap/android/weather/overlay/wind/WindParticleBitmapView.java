package com.atakmap.android.weather.overlay.wind;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.view.View;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * V4 Hybrid wind particle overlay — full-screen View with alpha-decay bitmap trails.
 *
 * <h3>Architecture (Windy/Mapbox technique)</h3>
 * <ol>
 *   <li>C++ engine advances particle cursors along pre-computed streamlines</li>
 *   <li>JNI returns only HEAD positions: float[N×3] = {lat, lon, speed}</li>
 *   <li>Java projects 1500 heads via {@code mapView.forward()} (~1ms)</li>
 *   <li>Draws speed-colored dots onto a persistent bitmap</li>
 *   <li>Each frame, the bitmap fades by ~4% (alpha decay = trail effect)</li>
 *   <li>No trail history, no ring buffers, no per-trail-point projection</li>
 * </ol>
 *
 * <p>Uses {@code mapView.addView()} for full-screen rendering,
 * avoiding ATAK's 512×512 GL texture pass limitation.</p>
 */
public class WindParticleBitmapView extends View {

    private static final String TAG = "WindParticleBmp";

    private final MapView mapView;
    private final WindParticleLayer dataLayer;
    private final Paint dotPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fadePaint   = new Paint();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    // Native engine handle
    private long nativePtr = 0;
    private boolean nativeReady = false;
    private long lastDataGen = -1;
    private boolean nativeHasData = false;

    // Alpha-decay trail bitmap (persistent between frames)
    private Bitmap trailBitmap;
    private Canvas trailCanvas;

    // Config tracking
    private int    lastCount = -1;
    private float  lastSpeed = -1;
    private int    lastTrail = -1;
    private float  lastIntensity = -1;
    private float  lastSaturation = -1;
    private float  lastBrightness = -1;

    // JNI buffer for geo positions
    private FloatBuffer geoBuf;
    private static final int MAX_PARTICLES = 5000;

    // Reusable GeoPoint to avoid allocations in hot path
    private final GeoPoint tempGeo = GeoPoint.createMutable();

    // Stats
    private int frameCount = 0;

    // Map movement detection
    private MapView.OnMapMovedListener mapMovedListener;
    private double lastCenterLat = Double.NaN, lastCenterLon = Double.NaN;
    private double lastMapRes = Double.NaN;
    private boolean mapMoved = false;

    // Speed→color ramp — configurable for wind vs marine current
    private static final float[][] WIND_COLORS = {
            {0.30f, 0.90f, 0.40f},  // 0-2 m/s: green
            {1.00f, 1.00f, 0.30f},  // 2-5 m/s: yellow
            {1.00f, 0.65f, 0.15f},  // 5-10 m/s: orange
            {1.00f, 0.20f, 0.20f},  // 10-15 m/s: red
            {0.90f, 0.40f, 1.00f},  // 15+ m/s: magenta
    };
    private static final float[] WIND_THRESHOLDS = {2f, 5f, 10f, 15f};

    // Wind-only color ramp (marine has its own MarineParticleBitmapView)
    private static final float[][] SPEED_COLORS = WIND_COLORS;
    private static final float[] SPEED_THRESHOLDS = WIND_THRESHOLDS;

    // ── Constructor ───────────────────────────────────────────────────

    public WindParticleBitmapView(MapView mapView, WindParticleLayer dataLayer) {
        super(mapView.getContext());
        this.mapView = mapView;
        this.dataLayer = dataLayer;
        setWillNotDraw(false);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        setLayoutParams(new MapView.LayoutParams(
                MapView.LayoutParams.MATCH_PARENT,
                MapView.LayoutParams.MATCH_PARENT));

        dotPaint.setStyle(Paint.Style.FILL);
        fadePaint.setColor(Color.TRANSPARENT);
        fadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));

        // 6 floats per particle: prevLat, prevLon, curLat, curLon, speed, ageFrac
        geoBuf = ByteBuffer.allocateDirect(MAX_PARTICLES * 6 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    public void attach() {
        if (getParent() != null) return;
        mapView.addView(this);

        // Init native engine
        if (NativeWindParticle.isAvailable() && nativePtr == 0) {
            nativePtr = NativeWindParticle.nCreate();
            nativeReady = (nativePtr != 0);
            if (nativeReady) {
                Log.d(TAG, "Native engine created for bitmap view");
            }
        }

        // Listen for map movements to detect significant pan/zoom
        mapMovedListener = (v, animate) -> {
            mapMoved = true;
            post(WindParticleBitmapView.this::invalidate);
        };
        mapView.addOnMapMovedListener(mapMovedListener);

        // Trigger first frame — without this, onDraw() never fires
        // because the View is transparent and the system won't schedule it
        setVisibility(VISIBLE);
        postInvalidate();
        Log.d(TAG, "Attached to MapView, size=" + mapView.getWidth() + "×" + mapView.getHeight());
    }

    public void detach() {
        if (mapMovedListener != null) {
            mapView.removeOnMapMovedListener(mapMovedListener);
            mapMovedListener = null;
        }
        if (getParent() != null) {
            mapView.removeView(this);
        }
        if (nativePtr != 0) {
            NativeWindParticle.nDestroy(nativePtr);
            nativePtr = 0;
            nativeReady = false;
        }
        if (trailBitmap != null) {
            trailBitmap.recycle();
            trailBitmap = null;
            trailCanvas = null;
        }
    }

    // ── Drawing (THE HOT PATH) ────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        if (!dataLayer.isShowParticles()) {
            return;
        }
        if (!nativeReady || nativePtr == 0) {
            Log.w(TAG, "onDraw: native not ready (ptr=" + nativePtr + ", ready=" + nativeReady + ")");
            scheduleNext();
            return;
        }

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            Log.w(TAG, "onDraw: zero size " + w + "×" + h);
            scheduleNext();
            return;
        }

        // Push wind data to native if changed
        pushWindDataIfNeeded();
        if (!nativeHasData) {
            Log.d(TAG, "onDraw: no wind data yet (hasData=" + dataLayer.hasData() + ")");
            scheduleNext();
            return;
        }

        // Push config changes
        pushConfigIfChanged();

        // Ensure trail bitmap exists at current view size
        ensureTrailBitmap(w, h);

        // Detect significant map movement → clear trails
        detectMapMovement();

        // ── 1. Fade existing trails (alpha decay — the Windy technique) ──
        // Lower value = slower fade = longer trails (1=longest, 20=shortest)
        int fadeAlpha = dataLayer.getTrailFadeAlpha();
        fadePaint.setAlpha(fadeAlpha);
        trailCanvas.drawPaint(fadePaint);

        // ── 2. Push viewport bounds to C++ so particles spawn in visible area ──
        try {
            com.atakmap.coremap.maps.coords.GeoBounds vb = mapView.getBounds();
            NativeWindParticle.nSetViewportBounds(nativePtr,
                    vb.getNorth(), vb.getSouth(), vb.getWest(), vb.getEast());
        } catch (Exception ignored) {}

        // ── 3. Advance particles in C++ (cursor movement only, no projection) ──
        int activeCount = NativeWindParticle.nAdvanceGeo(nativePtr);
        if (activeCount <= 0) {
            scheduleNext();
            return;
        }

        // ── 3. Copy geo positions from native ──
        geoBuf.clear();
        NativeWindParticle.nCopyGeoBuffer(nativePtr, geoBuf);
        geoBuf.position(0);

        // ── 4. Pre-filter: get viewport geo bounds for fast culling ──
        // This avoids calling forward() on particles far outside the visible area.
        double vpNorth, vpSouth, vpWest, vpEast;
        try {
            com.atakmap.coremap.maps.coords.GeoBounds vb = mapView.getBounds();
            vpNorth = vb.getNorth(); vpSouth = vb.getSouth();
            vpWest = vb.getWest(); vpEast = vb.getEast();
            // Expand by 10% to catch particles just entering the viewport
            double latPad = (vpNorth - vpSouth) * 0.1;
            double lonPad = (vpEast - vpWest) * 0.1;
            vpNorth += latPad; vpSouth -= latPad;
            vpWest -= lonPad; vpEast += lonPad;
        } catch (Exception e) {
            vpNorth = 90; vpSouth = -90; vpWest = -180; vpEast = 180;
        }

        // ── 5. Project visible particles + draw line segments ──
        // Line width from user control
        float lineW = dataLayer.getTrailLineWidth() * getContext().getResources().getDisplayMetrics().density;
        float intensity = dataLayer.getColorIntensity();
        float saturation = dataLayer.getColorSaturation();
        float brightness = dataLayer.getColorValue();
        dotPaint.setStrokeWidth(lineW);
        dotPaint.setStrokeCap(Paint.Cap.ROUND);

        GeoPoint prevGeo = GeoPoint.createMutable();
        int drawn = 0;

        for (int i = 0; i < activeCount && i < MAX_PARTICLES; i++) {
            float prevLat = geoBuf.get();
            float prevLon = geoBuf.get();
            float curLat  = geoBuf.get();
            float curLon  = geoBuf.get();
            float spd     = geoBuf.get();
            float ageFrac = geoBuf.get();

            // Fast geo-bounds cull — skip forward() entirely for off-viewport particles
            if (curLat < vpSouth || curLat > vpNorth ||
                curLon < vpWest || curLon > vpEast) continue;

            // Project current position only (not prev — use delta for short segment)
            tempGeo.set(curLat, curLon);
            PointF p2 = mapView.forward(tempGeo);
            if (p2 == null) continue;

            // For the prev→cur line, project prev too
            // (optimization: for very short segments, we could compute p1 from p2 + screen delta,
            //  but forward() is fast enough at the culled count)
            prevGeo.set(prevLat, prevLon);
            PointF p1 = mapView.forward(prevGeo);
            if (p1 == null) continue;

            // Speed → color with age-based alpha fade
            int color = speedToColor(spd, intensity * Math.min(1f, ageFrac * 2f), saturation, brightness);
            dotPaint.setColor(color);

            // Draw line segment prev→cur on trail bitmap
            trailCanvas.drawLine(p1.x, p1.y, p2.x, p2.y, dotPaint);
            drawn++;
        }

        // ── 5. Draw trail bitmap to screen canvas ──
        canvas.drawBitmap(trailBitmap, 0, 0, bitmapPaint);

        // ── 6. Log stats periodically ──
        frameCount++;
        if (frameCount % 90 == 0) { // every ~3 seconds at 30fps
            float nativeTime = NativeWindParticle.nGetLastFrameTimeUs(nativePtr);
            int streamlines = NativeWindParticle.nGetStreamlineCount(nativePtr);
            Log.d(TAG, "Frame: active=" + activeCount + ", drawn=" + drawn
                    + "/" + dataLayer.getParticleCount()
                    + ", streamlines=" + streamlines
                    + ", size=" + w + "×" + h
                    + ", native=" + String.format("%.0f", nativeTime) + "us");
        }

        // ── 7. Schedule next frame (30fps) ──
        scheduleNext();
    }

    private void scheduleNext() {
        postInvalidateDelayed(33); // ~30fps
    }

    // ── Trail bitmap management ───────────────────────────────────────

    private void ensureTrailBitmap(int w, int h) {
        if (trailBitmap != null && trailBitmap.getWidth() == w && trailBitmap.getHeight() == h) {
            return;
        }
        // Recycle old
        if (trailBitmap != null) trailBitmap.recycle();

        // Create new ARGB bitmap at full screen resolution
        trailBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        trailCanvas = new Canvas(trailBitmap);
        Log.d(TAG, "Trail bitmap created: " + w + "×" + h
                + " (" + (w * h * 4 / 1024) + " KB)");
    }

    private void detectMapMovement() {
        if (!mapMoved) return;
        mapMoved = false;

        double lat = mapView.getLatitude();
        double lon = mapView.getLongitude();
        double res = mapView.getMapResolution();

        // Check if movement is significant (>5% of viewport)
        if (!Double.isNaN(lastCenterLat)) {
            double dLat = Math.abs(lat - lastCenterLat);
            double dLon = Math.abs(lon - lastCenterLon);
            double dRes = Math.abs(res - lastMapRes) / (lastMapRes + 0.001);

            // Only clear on significant pan or zoom change
            if (dLat < 0.01 && dLon < 0.01 && dRes < 0.1) {
                return; // minor movement — keep trails
            }
        }

        // Significant movement → clear trails (they'll rebuild in ~1 second)
        if (trailBitmap != null) {
            trailBitmap.eraseColor(Color.TRANSPARENT);
        }

        lastCenterLat = lat;
        lastCenterLon = lon;
        lastMapRes = res;
    }

    // ── Wind data push to native ──────────────────────────────────────

    private void pushWindDataIfNeeded() {
        if (!dataLayer.hasData()) return;
        long gen = dataLayer.getStreamlineGeneration();
        if (gen == lastDataGen) return;

        double[][] speed = dataLayer.getWindSpeed();
        double[][] dir = dataLayer.getWindDirection();
        if (speed == null || dir == null) return;

        int rows = dataLayer.getGridRows();
        int cols = dataLayer.getGridCols();
        if (rows <= 0 || cols <= 0) return;

        // Flatten for JNI
        double[] flatSpeed = new double[rows * cols];
        double[] flatDir = new double[rows * cols];
        for (int r = 0; r < rows; r++) {
            System.arraycopy(speed[r], 0, flatSpeed, r * cols, cols);
            System.arraycopy(dir[r], 0, flatDir, r * cols, cols);
        }

        NativeWindParticle.nSetWindField(nativePtr, flatSpeed, flatDir,
                rows, cols,
                dataLayer.getGridNorth(), dataLayer.getGridSouth(),
                dataLayer.getGridWest(), dataLayer.getGridEast());

        lastDataGen = gen;
        nativeHasData = true;

        // Clear trails when new wind data arrives
        if (trailBitmap != null) {
            trailBitmap.eraseColor(Color.TRANSPARENT);
        }

        Log.d(TAG, "Wind data pushed: " + rows + "×" + cols
                + ", streamlines=" + NativeWindParticle.nGetStreamlineCount(nativePtr));
    }

    // ── Config push ───────────────────────────────────────────────────

    private void pushConfigIfChanged() {
        int count = dataLayer.getParticleCount();
        float speed = dataLayer.getParticleSpeed();
        int trail = (int) dataLayer.getParticleLife();
        float intensity = dataLayer.getColorIntensity();
        float sat = dataLayer.getColorSaturation();
        float bright = dataLayer.getColorValue();

        if (count != lastCount) {
            NativeWindParticle.nSetParticleCount(nativePtr, count);
            lastCount = count;
        }
        if (speed != lastSpeed) {
            NativeWindParticle.nSetParticleSpeed(nativePtr, speed);
            lastSpeed = speed;
        }
        if (trail != lastTrail) {
            NativeWindParticle.nSetTrailLength(nativePtr, trail);
            lastTrail = trail;
        }
        if (intensity != lastIntensity) {
            NativeWindParticle.nSetColorIntensity(nativePtr, intensity);
            lastIntensity = intensity;
        }
        if (sat != lastSaturation) {
            NativeWindParticle.nSetColorSaturation(nativePtr, sat);
            lastSaturation = sat;
        }
        if (bright != lastBrightness) {
            NativeWindParticle.nSetColorBrightness(nativePtr, bright);
            lastBrightness = bright;
        }
    }

    // ── Color helpers ─────────────────────────────────────────────────

    private static int speedToColor(float speed, float intensity, float saturation, float brightness) {
        float r, g, b;

        // Guard: NaN, negative, or zero → use first color
        if (Float.isNaN(speed) || speed <= 0 || speed < SPEED_THRESHOLDS[0]) {
            r = SPEED_COLORS[0][0]; g = SPEED_COLORS[0][1]; b = SPEED_COLORS[0][2];
        } else if (speed >= SPEED_THRESHOLDS[SPEED_THRESHOLDS.length - 1]) {
            int last = SPEED_COLORS.length - 1;
            r = SPEED_COLORS[last][0]; g = SPEED_COLORS[last][1]; b = SPEED_COLORS[last][2];
        } else {
            // Find bracketing band: speed is between SPEED_THRESHOLDS[band-1] and [band]
            int band = 1; // safe default — at minimum interpolate between colors[0] and [1]
            for (int i = 1; i < SPEED_THRESHOLDS.length; i++) {
                if (speed < SPEED_THRESHOLDS[i]) { band = i; break; }
            }
            // Clamp indices to valid range
            int lo = Math.max(0, band - 1);
            int hi = Math.min(SPEED_COLORS.length - 1, band);
            int tLo = Math.max(0, band - 1);
            int tHi = Math.min(SPEED_THRESHOLDS.length - 1, band);
            float range = SPEED_THRESHOLDS[tHi] - SPEED_THRESHOLDS[tLo];
            float t = (range > 0) ? (speed - SPEED_THRESHOLDS[tLo]) / range : 0f;
            t = Math.max(0f, Math.min(1f, t));
            r = SPEED_COLORS[lo][0] + t * (SPEED_COLORS[hi][0] - SPEED_COLORS[lo][0]);
            g = SPEED_COLORS[lo][1] + t * (SPEED_COLORS[hi][1] - SPEED_COLORS[lo][1]);
            b = SPEED_COLORS[lo][2] + t * (SPEED_COLORS[hi][2] - SPEED_COLORS[lo][2]);
        }

        // Apply saturation
        if (saturation < 1f) {
            float grey = 0.299f * r + 0.587f * g + 0.114f * b;
            r = grey + saturation * (r - grey);
            g = grey + saturation * (g - grey);
            b = grey + saturation * (b - grey);
        }

        // Apply brightness
        r = Math.min(1f, r * brightness);
        g = Math.min(1f, g * brightness);
        b = Math.min(1f, b * brightness);

        int alpha = Math.round(intensity * 230); // slightly less than full for blending
        return Color.argb(alpha,
                Math.round(r * 255), Math.round(g * 255), Math.round(b * 255));
    }
}
