package com.atakmap.android.weather.overlay.wind;

import android.opengl.GLES20;

import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.MapRenderer;
import com.atakmap.map.layer.Layer;
import com.atakmap.map.layer.opengl.GLAbstractLayer2;
import com.atakmap.map.layer.opengl.GLLayer2;
import com.atakmap.map.layer.opengl.GLLayerFactory;
import com.atakmap.map.layer.opengl.GLLayerSpi2;
import com.atakmap.map.opengl.GLMapView;
import com.atakmap.math.PointD;
import com.atakmap.opengl.GLES20FixedPipeline;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

import android.util.Pair;

/**
 * OpenGL particle flow renderer for Windy.com-style wind visualization.
 *
 * <p>Renders thousands of particles that flow along the wind vector field,
 * creating animated streamlines. Each particle:</p>
 * <ol>
 *   <li>Has a geo-position (lat/lon) in the wind field</li>
 *   <li>Moves each frame according to interpolated wind at its position</li>
 *   <li>Draws a short line segment from previous to current position</li>
 *   <li>Respawns at a random position after its lifetime expires</li>
 * </ol>
 *
 * <p>Uses ATAK's {@code GLAbstractLayer2} + {@code GLLayerSpi2} pattern to
 * render on {@code RenderStack.MAP_SURFACE_OVERLAYS}.</p>
 *
 * <p>Color is mapped from wind speed using the same scale as the heatmap.</p>
 */
public class GLWindParticleLayer extends GLAbstractLayer2 {

    private static final String TAG = "GLWindParticle";

    // ── GLLayerSpi2 factory registration ─────────────────────────────────────
    public static final GLLayerSpi2 SPI = new GLLayerSpi2() {
        @Override
        public int getPriority() { return 1; }

        @Override
        public GLLayer2 create(Pair<MapRenderer, Layer> arg) {
            if (arg.second instanceof WindParticleLayer) {
                return GLLayerFactory.adapt(
                        new GLWindParticleLayer(arg.first, (WindParticleLayer) arg.second));
            }
            return null;
        }
    };

    static {
        GLLayerFactory.register(SPI);
    }

    // ── State ────────────────────────────────────────────────────────────────
    private final WindParticleLayer dataLayer;

    /** Max trail segments per particle (position history). */
    private static final int TRAIL_LENGTH = 12;

    // Particle arrays
    private double[][] trailLat;      // [particle][historyIndex] — ring buffer
    private double[][] trailLon;      // [particle][historyIndex]
    private int[]      trailHead;     // current write index per particle
    private int[]      trailCount;    // how many positions stored (0..TRAIL_LENGTH)
    private float[]    particleAge;   // frames remaining
    private float[]    particleSpeed; // cached speed for coloring
    private int        numParticles = 0;
    private boolean    initialized = false;

    private final Random rng = new Random();

    // GL buffers — sized for worst case: numParticles * TRAIL_LENGTH segments * 2 verts
    private FloatBuffer lineVertexBuffer;
    private FloatBuffer lineColorBuffer;

    // Speed color ramp: green → yellow → orange → red → magenta
    private static final float[][] SPEED_COLORS = {
            {0.50f, 0.78f, 0.50f},  // 0-2 m/s: green
            {1.00f, 0.94f, 0.46f},  // 2-5 m/s: yellow
            {1.00f, 0.72f, 0.30f},  // 5-10 m/s: orange
            {0.90f, 0.32f, 0.32f},  // 10-15 m/s: red
            {0.80f, 0.58f, 0.85f},  // 15+ m/s: magenta
    };
    private static final float[] SPEED_THRESHOLDS = {2f, 5f, 10f, 15f};

    // ── Constructor ──────────────────────────────────────────────────────────

    public GLWindParticleLayer(MapRenderer renderer, WindParticleLayer layer) {
        super(renderer, layer, GLMapView.RENDER_PASS_SURFACE);
        this.dataLayer = layer;
    }

    // ── GLLayer2 lifecycle ───────────────────────────────────────────────────

    @Override
    protected void init() {
        super.init();
    }

    @Override
    protected void drawImpl(GLMapView view, int renderPass) {
        if ((renderPass & GLMapView.RENDER_PASS_SURFACE) == 0) return;
        if (!dataLayer.isVisible() || !dataLayer.isShowParticles()) return;
        if (!dataLayer.hasData()) return;

        // Initialize particles on first frame or if count changed
        int targetCount = dataLayer.getParticleCount();
        if (!initialized || numParticles != targetCount) {
            initParticles(targetCount);
        }

        // Simulate one step
        simulateStep(view);

        // Build GL vertex arrays from particle positions
        buildVertexBuffers(view);

        // Render lines
        renderParticleLines(view);
    }

    @Override
    public void release() {
        initialized = false;
        lineVertexBuffer = null;
        lineColorBuffer = null;
    }

    // ── Particle simulation ──────────────────────────────────────────────────

    private void initParticles(int count) {
        numParticles = count;
        trailLat     = new double[count][TRAIL_LENGTH];
        trailLon     = new double[count][TRAIL_LENGTH];
        trailHead    = new int[count];
        trailCount   = new int[count];
        particleAge  = new float[count];
        particleSpeed = new float[count];

        double n = dataLayer.getGridNorth();
        double s = dataLayer.getGridSouth();
        double w = dataLayer.getGridWest();
        double e = dataLayer.getGridEast();

        for (int i = 0; i < count; i++) {
            respawnParticle(i, n, s, w, e);
        }

        // Allocate GL buffers: each particle can have up to TRAIL_LENGTH-1 line
        // segments, each with 2 vertices * 2 floats (x,y) + 4 floats (rgba)
        int maxSegments = count * (TRAIL_LENGTH - 1);
        lineVertexBuffer = ByteBuffer.allocateDirect(maxSegments * 2 * 2 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        lineColorBuffer = ByteBuffer.allocateDirect(maxSegments * 2 * 4 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();

        initialized = true;
    }

    private void respawnParticle(int i, double n, double s, double w, double e) {
        double lat = s + rng.nextDouble() * (n - s);
        double lon = w + rng.nextDouble() * (e - w);
        // Reset trail — fill first position
        trailHead[i]  = 0;
        trailCount[i] = 1;
        trailLat[i][0] = lat;
        trailLon[i][0] = lon;
        particleAge[i] = dataLayer.getParticleLife() * (0.5f + 0.5f * rng.nextFloat());
        particleSpeed[i] = 0;
    }

    /** Get current lat of particle i. */
    private double curLat(int i) { return trailLat[i][trailHead[i]]; }
    /** Get current lon of particle i. */
    private double curLon(int i) { return trailLon[i][trailHead[i]]; }

    /** Append a new position to particle i's trail ring buffer. */
    private void appendTrail(int i, double lat, double lon) {
        trailHead[i] = (trailHead[i] + 1) % TRAIL_LENGTH;
        trailLat[i][trailHead[i]] = lat;
        trailLon[i][trailHead[i]] = lon;
        if (trailCount[i] < TRAIL_LENGTH) trailCount[i]++;
    }

    private void simulateStep(GLMapView view) {
        double n = dataLayer.getGridNorth();
        double s = dataLayer.getGridSouth();
        double w = dataLayer.getGridWest();
        double e = dataLayer.getGridEast();
        float speedMul = dataLayer.getParticleSpeed();

        double midLat = (n + s) * 0.5;
        double degPerMeterLat = 1.0 / 111320.0;
        double degPerMeterLon = 1.0 / (111320.0 * Math.cos(Math.toRadians(midLat)));

        // Visual time step — makes particles move visibly (~120s of real wind per frame)
        double dt = 120.0 * speedMul;

        for (int i = 0; i < numParticles; i++) {
            particleAge[i] -= 1.0f;

            double lat = curLat(i);
            double lon = curLon(i);

            // Respawn if expired or out of bounds
            if (particleAge[i] <= 0 || lat < s || lat > n || lon < w || lon > e) {
                respawnParticle(i, n, s, w, e);
                continue;
            }

            // Interpolate wind at current position
            double[] wind = dataLayer.interpolateWind(lat, lon);
            if (wind == null || wind[0] < 0.1) {
                particleAge[i] = 0;
                continue;
            }

            double spd = wind[0];
            double dir = wind[1];
            particleSpeed[i] = (float) spd;

            // Wind FROM dir → particle moves opposite
            double bearingRad = Math.toRadians((dir + 180.0) % 360.0);
            double dx = spd * dt;
            double dLat = dx * Math.cos(bearingRad) * degPerMeterLat;
            double dLon = dx * Math.sin(bearingRad) * degPerMeterLon;

            double newLat = lat + dLat;
            double newLon = lon + dLon;

            // Append new position to trail ring buffer
            appendTrail(i, newLat, newLon);
        }
    }

    // ── GL rendering ─────────────────────────────────────────────────────────

    private void buildVertexBuffers(GLMapView view) {
        lineVertexBuffer.clear();
        lineColorBuffer.clear();

        // Use the current render pass's MapSceneModel for geo→screen projection.
        // Use the MapSceneModel for geo→screen projection on GL thread
        com.atakmap.map.MapSceneModel scene = view.currentPass.scene;
        if (scene == null) return;

        PointD ptA = new PointD(0, 0, 0);
        PointD ptB = new PointD(0, 0, 0);

        float sat = dataLayer.getColorSaturation();
        float val = dataLayer.getColorValue();
        float intensityMul = dataLayer.getColorIntensity();

        for (int i = 0; i < numParticles; i++) {
            if (particleAge[i] <= 0 || trailCount[i] < 2) continue;

            // Pre-compute color for this particle (based on current speed)
            float[] rgb = speedToColor(particleSpeed[i]);

            // Apply saturation: lerp toward luminance grey
            if (sat < 1f) {
                float grey = 0.299f * rgb[0] + 0.587f * rgb[1] + 0.114f * rgb[2];
                rgb[0] = grey + sat * (rgb[0] - grey);
                rgb[1] = grey + sat * (rgb[1] - grey);
                rgb[2] = grey + sat * (rgb[2] - grey);
            }
            // Apply brightness
            rgb[0] = Math.min(1f, rgb[0] * val);
            rgb[1] = Math.min(1f, rgb[1] * val);
            rgb[2] = Math.min(1f, rgb[2] * val);

            // Base alpha from age
            float ageFrac = particleAge[i] / dataLayer.getParticleLife();
            float baseAlpha = Math.min(1f, ageFrac * 2f) * 0.85f * intensityMul;

            // Draw trail segments: iterate from oldest to newest in the ring buffer
            int count = trailCount[i];
            int head  = trailHead[i];

            for (int seg = 0; seg < count - 1; seg++) {
                // Ring buffer index: oldest = head - count + 1, newest = head
                int idxA = (head - count + 1 + seg + TRAIL_LENGTH) % TRAIL_LENGTH;
                int idxB = (idxA + 1) % TRAIL_LENGTH;

                scene.forward(new GeoPoint(trailLat[i][idxA], trailLon[i][idxA]), ptA);
                scene.forward(new GeoPoint(trailLat[i][idxB], trailLon[i][idxB]), ptB);

                float ax = (float) ptA.x, ay = (float) ptA.y;
                float bx = (float) ptB.x, by = (float) ptB.y;

                // Frustum cull
                if (ax < -500 && bx < -500) continue;
                if (ay < -500 && by < -500) continue;

                lineVertexBuffer.put(ax); lineVertexBuffer.put(ay);
                lineVertexBuffer.put(bx); lineVertexBuffer.put(by);

                // Trail fade: older segments → more transparent
                // seg 0 = oldest (faded), seg count-2 = newest (bright)
                float trailFrac = (float) (seg + 1) / (count - 1);
                float alpha = baseAlpha * trailFrac;

                lineColorBuffer.put(rgb[0]); lineColorBuffer.put(rgb[1]);
                lineColorBuffer.put(rgb[2]); lineColorBuffer.put(alpha);
                lineColorBuffer.put(rgb[0]); lineColorBuffer.put(rgb[1]);
                lineColorBuffer.put(rgb[2]); lineColorBuffer.put(alpha);
            }
        }

        lineVertexBuffer.flip();
        lineColorBuffer.flip();
    }

    private void renderParticleLines(GLMapView view) {
        int numVertices = lineVertexBuffer.remaining() / 2;
        if (numVertices < 2) return;

        // Use fixed-function pipeline for compatibility
        GLES20FixedPipeline.glEnableClientState(GLES20FixedPipeline.GL_VERTEX_ARRAY);
        GLES20FixedPipeline.glEnableClientState(GLES20FixedPipeline.GL_COLOR_ARRAY);

        GLES20FixedPipeline.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        GLES20FixedPipeline.glLineWidth(dataLayer.getLineWidth());

        GLES20FixedPipeline.glVertexPointer(2, GLES20.GL_FLOAT, 0, lineVertexBuffer);
        GLES20FixedPipeline.glColorPointer(4, GLES20.GL_FLOAT, 0, lineColorBuffer);

        GLES20FixedPipeline.glDrawArrays(GLES20.GL_LINES, 0, numVertices);

        GLES20FixedPipeline.glDisableClientState(GLES20FixedPipeline.GL_COLOR_ARRAY);
        GLES20FixedPipeline.glDisableClientState(GLES20FixedPipeline.GL_VERTEX_ARRAY);
        GLES20FixedPipeline.glDisable(GLES20.GL_BLEND);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static float[] speedToColor(float speed) {
        // Interpolate through the color ramp
        for (int i = 0; i < SPEED_THRESHOLDS.length; i++) {
            if (speed < SPEED_THRESHOLDS[i]) {
                if (i == 0) return SPEED_COLORS[0];
                float t = (speed - (i > 0 ? SPEED_THRESHOLDS[i - 1] : 0))
                        / (SPEED_THRESHOLDS[i] - (i > 0 ? SPEED_THRESHOLDS[i - 1] : 0));
                return new float[]{
                        SPEED_COLORS[i - 1][0] + t * (SPEED_COLORS[i][0] - SPEED_COLORS[i - 1][0]),
                        SPEED_COLORS[i - 1][1] + t * (SPEED_COLORS[i][1] - SPEED_COLORS[i - 1][1]),
                        SPEED_COLORS[i - 1][2] + t * (SPEED_COLORS[i][2] - SPEED_COLORS[i - 1][2]),
                };
            }
        }
        return SPEED_COLORS[SPEED_COLORS.length - 1];
    }
}
