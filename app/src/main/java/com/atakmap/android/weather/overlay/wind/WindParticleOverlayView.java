package com.atakmap.android.weather.overlay.wind;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.view.View;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wind particle flow overlay — View-based approach.
 *
 * <p>Renders animated particles into a full-screen Bitmap using a dedicated
 * background thread, then draws that bitmap via Canvas overlay on the MapView.
 * This avoids ATAK's 512×512 GL texture pass limitation.</p>
 *
 * <p><b>Architecture:</b></p>
 * <ol>
 *   <li>Background thread runs at ~30fps: advances cursor positions along
 *       pre-computed streamlines, renders trails to a double-buffered Bitmap</li>
 *   <li>UI thread onDraw(): just draws the latest bitmap to screen (fast)</li>
 *   <li>Projection uses {@code mapView.forward(GeoPoint)} — full-screen coords</li>
 * </ol>
 *
 * <p>The native C++ engine is still used for streamline computation (RK4).
 * Particle advancement and rendering is done in Java on a background thread
 * because the main bottleneck was the 512×512 viewport, not Java speed.</p>
 */
public class WindParticleOverlayView extends View {

    private static final String TAG = "WindParticleOV";
    private static final int TARGET_FPS = 30;
    private static final long FRAME_MS = 1000 / TARGET_FPS;

    private final MapView mapView;
    private final WindParticleLayer dataLayer;
    private final Random rng = new Random();

    // Double-buffered bitmaps
    private volatile Bitmap frontBitmap;
    private volatile Bitmap backBitmap;
    private final Object bitmapLock = new Object();

    // Particle state
    private int[] cursorStreamline;   // which streamline
    private float[] cursorPosition;   // fractional index
    private float[] cursorAge;        // frames remaining
    private int numParticles = 0;
    private boolean particlesInited = false;
    private long lastStreamlineGen = -1;

    // Animation thread
    private Thread animThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Paints
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    // Map listener
    private MapView.OnMapMovedListener movedListener;

    // Speed color ramp (vivid)
    private static final int[] SPEED_COLORS = {
            Color.rgb(77, 230, 102),   // 0-2 m/s: green
            Color.rgb(255, 255, 77),   // 2-5 m/s: yellow
            Color.rgb(255, 166, 38),   // 5-10 m/s: orange
            Color.rgb(255, 51, 51),    // 10-15 m/s: red
            Color.rgb(230, 102, 255),  // 15+ m/s: magenta
    };
    private static final float[] SPEED_THRESHOLDS = {2f, 5f, 10f, 15f, 999f};

    public WindParticleOverlayView(MapView mapView, WindParticleLayer dataLayer) {
        super(mapView.getContext());
        this.mapView = mapView;
        this.dataLayer = dataLayer;
        setWillNotDraw(false);
        setLayoutParams(new MapView.LayoutParams(
                MapView.LayoutParams.MATCH_PARENT,
                MapView.LayoutParams.MATCH_PARENT));
        trailPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    public void attach() {
        if (getParent() != null) return;
        mapView.addView(this);
        movedListener = (v, animate) -> {
            // Map moved — force re-render on next frame (thread will pick up new projection)
        };
        mapView.addOnMapMovedListener(movedListener);
        startAnimation();
    }

    public void detach() {
        stopAnimation();
        if (movedListener != null) {
            mapView.removeOnMapMovedListener(movedListener);
            movedListener = null;
        }
        if (getParent() != null) {
            mapView.removeView(this);
        }
        synchronized (bitmapLock) {
            if (frontBitmap != null) { frontBitmap.recycle(); frontBitmap = null; }
            if (backBitmap != null) { backBitmap.recycle(); backBitmap = null; }
        }
    }

    public void startAnimation() {
        if (running.getAndSet(true)) return;
        animThread = new Thread(this::animationLoop, "WindParticleAnim");
        animThread.setDaemon(true);
        animThread.start();
    }

    public void stopAnimation() {
        running.set(false);
        if (animThread != null) {
            animThread.interrupt();
            try { animThread.join(200); } catch (InterruptedException ignored) {}
            animThread = null;
        }
    }

    // ── Animation loop (background thread) ────────────────────────────────

    private void animationLoop() {
        while (running.get()) {
            long t0 = System.nanoTime();

            if (dataLayer.isShowParticles() && dataLayer.hasStreamlines()) {
                renderFrame();
                post(this::invalidate);
            }

            long elapsed = (System.nanoTime() - t0) / 1_000_000;
            long sleep = FRAME_MS - elapsed;
            if (sleep > 0) {
                try { Thread.sleep(sleep); } catch (InterruptedException e) { break; }
            }
        }
    }

    private void renderFrame() {
        int w = mapView.getWidth();
        int h = mapView.getHeight();
        if (w <= 0 || h <= 0) return;

        // Ensure bitmaps match screen size
        synchronized (bitmapLock) {
            if (backBitmap == null || backBitmap.getWidth() != w || backBitmap.getHeight() != h) {
                if (backBitmap != null) backBitmap.recycle();
                backBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
        }

        List<WindParticleLayer.Streamline> paths = dataLayer.getStreamlines();
        if (paths == null || paths.isEmpty()) return;

        // Check if streamlines changed — reinit particles
        long gen = dataLayer.getStreamlineGeneration();
        int targetCount = dataLayer.getParticleCount();
        if (!particlesInited || gen != lastStreamlineGen || numParticles != targetCount) {
            initParticles(targetCount, paths);
            lastStreamlineGen = gen;
        }

        // Advance cursors
        advanceCursors(paths);

        // Clear back buffer with transparent
        Bitmap bmp;
        synchronized (bitmapLock) { bmp = backBitmap; }
        if (bmp == null) return;

        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);

        float lineWidth = dataLayer.getLineWidth();
        float intensity = dataLayer.getColorIntensity();
        float saturation = dataLayer.getColorSaturation();
        float brightness = dataLayer.getColorValue();
        int trailLen = (int) dataLayer.getParticleLife();

        // Render each particle's trail
        for (int i = 0; i < numParticles; i++) {
            int slIdx = cursorStreamline[i];
            if (slIdx < 0 || slIdx >= paths.size()) continue;

            WindParticleLayer.Streamline sl = paths.get(slIdx);
            int headIdx = (int) cursorPosition[i];
            if (headIdx < 2 || headIdx >= sl.length) continue;

            int trailStart = Math.max(0, headIdx - trailLen);
            if (headIdx - trailStart < 3) continue;

            // Age-based alpha
            float ageFrac = cursorAge[i] / dataLayer.getParticleLife();
            float baseAlpha = Math.min(1f, ageFrac * 2.5f) * intensity;

            // Color from speed
            float spd = (headIdx < sl.speed.length) ? sl.speed[headIdx] : 0;
            int color = speedToColor(spd, saturation, brightness);

            // Sub-sample for performance: draw every Nth point
            int trailPoints = headIdx - trailStart;
            int subsample = Math.max(1, trailPoints / 30); // max 30 segments per particle

            trailPaint.setStrokeWidth(lineWidth);

            // Project and draw trail segments
            float prevX = Float.NaN, prevY = Float.NaN;
            for (int t = trailStart; t <= headIdx; t += subsample) {
                PointF pt = mapView.forward(new GeoPoint(sl.lat[t], sl.lon[t]));
                if (pt == null) continue;

                if (!Float.isNaN(prevX)) {
                    float trailFrac = (float)(t - trailStart) / trailPoints;
                    int alpha = (int)(baseAlpha * (0.2f + 0.8f * trailFrac) * 255);
                    alpha = Math.max(0, Math.min(255, alpha));

                    trailPaint.setColor(color);
                    trailPaint.setAlpha(alpha);
                    canvas.drawLine(prevX, prevY, pt.x, pt.y, trailPaint);
                }
                prevX = pt.x;
                prevY = pt.y;
            }

            // Draw head dot
            if (!Float.isNaN(prevX)) {
                trailPaint.setColor(color);
                trailPaint.setAlpha((int)(baseAlpha * 255));
                canvas.drawCircle(prevX, prevY, lineWidth * 1.5f, trailPaint);
            }
        }

        // Swap buffers
        synchronized (bitmapLock) {
            Bitmap tmp = frontBitmap;
            frontBitmap = backBitmap;
            backBitmap = tmp;
        }
    }

    // ── Particle management ───────────────────────────────────────────────

    private void initParticles(int count, List<WindParticleLayer.Streamline> paths) {
        numParticles = count;
        cursorStreamline = new int[count];
        cursorPosition = new float[count];
        cursorAge = new float[count];

        for (int i = 0; i < count; i++) {
            respawnParticle(i, paths);
        }
        particlesInited = true;
        Log.d(TAG, "Particles initialized: " + count + " on " + paths.size() + " streamlines");
    }

    private void respawnParticle(int i, List<WindParticleLayer.Streamline> paths) {
        int slIdx = rng.nextInt(paths.size());
        WindParticleLayer.Streamline sl = paths.get(slIdx);
        cursorStreamline[i] = slIdx;
        cursorPosition[i] = rng.nextFloat() * sl.length * 0.33f;
        cursorAge[i] = dataLayer.getParticleLife() * (0.5f + 0.5f * rng.nextFloat());
    }

    private void advanceCursors(List<WindParticleLayer.Streamline> paths) {
        float speedMul = dataLayer.getParticleSpeed();
        float advanceRate = 3.0f * speedMul;

        for (int i = 0; i < numParticles; i++) {
            cursorAge[i] -= 1.0f;
            cursorPosition[i] += advanceRate;

            int slIdx = cursorStreamline[i];
            if (slIdx < 0 || slIdx >= paths.size()) {
                respawnParticle(i, paths);
                continue;
            }

            WindParticleLayer.Streamline sl = paths.get(slIdx);
            if (cursorPosition[i] >= sl.length - 1 || cursorAge[i] <= 0) {
                respawnParticle(i, paths);
            }
        }
    }

    // ── Drawing (UI thread — just blit the bitmap) ────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        Bitmap bmp;
        synchronized (bitmapLock) { bmp = frontBitmap; }
        if (bmp != null && !bmp.isRecycled()) {
            canvas.drawBitmap(bmp, 0, 0, bitmapPaint);
        }
    }

    // ── Color helpers ─────────────────────────────────────────────────────

    private static int speedToColor(float speed, float saturation, float brightness) {
        // Find which band
        int idx = 0;
        for (int i = 0; i < SPEED_THRESHOLDS.length; i++) {
            if (speed < SPEED_THRESHOLDS[i]) { idx = i; break; }
        }
        int base = SPEED_COLORS[Math.min(idx, SPEED_COLORS.length - 1)];

        int r = Color.red(base);
        int g = Color.green(base);
        int b = Color.blue(base);

        // Apply saturation
        if (saturation < 1f) {
            int grey = (int)(0.299 * r + 0.587 * g + 0.114 * b);
            r = (int)(grey + saturation * (r - grey));
            g = (int)(grey + saturation * (g - grey));
            b = (int)(grey + saturation * (b - grey));
        }

        // Apply brightness
        r = Math.min(255, (int)(r * brightness));
        g = Math.min(255, (int)(g * brightness));
        b = Math.min(255, (int)(b * brightness));

        return Color.rgb(r, g, b);
    }
}
