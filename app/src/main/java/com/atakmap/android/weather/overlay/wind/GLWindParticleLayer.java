package com.atakmap.android.weather.overlay.wind;

import android.opengl.GLES20;

import com.atakmap.coremap.log.Log;
import com.atakmap.map.MapRenderer;
import com.atakmap.map.layer.Layer;
import com.atakmap.map.layer.opengl.GLAbstractLayer2;
import com.atakmap.map.layer.opengl.GLLayer2;
import com.atakmap.map.layer.opengl.GLLayerFactory;
import com.atakmap.map.layer.opengl.GLLayerSpi2;
import com.atakmap.map.opengl.GLMapView;
import com.atakmap.opengl.GLES20FixedPipeline;

import android.util.Pair;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * GL renderer for wind particle flow — delegates to native C++ engine.
 *
 * <p><b>Architecture:</b> The native engine ({@code libwindparticle.so})
 * handles ALL computation: streamline integration (RK4), cursor advancement,
 * geo→screen projection (simplified Mercator), vertex buffer fill, and
 * GL draw. This Java class is a thin orchestrator that:</p>
 * <ol>
 *   <li>Creates/destroys the native engine on init/release</li>
 *   <li>Passes projection params each frame</li>
 *   <li>Calls the single native method that does everything</li>
 * </ol>
 *
 * <p>If the native library fails to load, falls back to a no-op
 * (particles disabled). No Java fallback — the native path IS the
 * implementation.</p>
 */
public class GLWindParticleLayer extends GLAbstractLayer2 {

    private static final String TAG = "GLWindParticle";

    // ── GLLayerSpi2 factory ───────────────────────────────────────────────
    public static final GLLayerSpi2 SPI = new GLLayerSpi2() {
        @Override public int getPriority() { return 1; }
        @Override public GLLayer2 create(Pair<MapRenderer, Layer> arg) {
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

    // ── State ─────────────────────────────────────────────────────────────
    private final WindParticleLayer dataLayer;
    private long nativePtr = 0;
    private boolean nativeAvailable = false;
    private long lastDataGeneration = -1;

    // GL buffers — filled by native C++, drawn by Java GLES20FixedPipeline
    private FloatBuffer vertexBuffer;
    private FloatBuffer colorBuffer;
    private static final int MAX_SEGMENTS = 10000 * 50; // particle × trail

    // Config change tracking (avoid reinitializing particles every frame)
    private int    lastPushedCount = -1;
    private float  lastPushedSpeed = -1;
    private int    lastPushedTrail = -1;
    private float  lastPushedIntensity = -1;
    private float  lastPushedSaturation = -1;
    private float  lastPushedBrightness = -1;

    /** Tracks whether native engine has received wind data. Independent of Java-side hasData(). */
    private boolean nativeHasData = false;

    // ── Constructor ───────────────────────────────────────────────────────

    public GLWindParticleLayer(MapRenderer renderer, WindParticleLayer layer) {
        super(renderer, layer, GLMapView.RENDER_PASS_SURFACE);
        this.dataLayer = layer;
    }

    // ── GLLayer2 lifecycle ────────────────────────────────────────────────

    @Override
    protected void init() {
        super.init();
        if (NativeWindParticle.isAvailable()) {
            nativePtr = NativeWindParticle.nCreate();
            nativeAvailable = (nativePtr != 0);
            if (nativeAvailable) {
                Log.d(TAG, "Native particle engine initialized");
                NativeWindParticle.nSetParticleCount(nativePtr, dataLayer.getParticleCount());
                NativeWindParticle.nSetParticleSpeed(nativePtr, dataLayer.getParticleSpeed());
                NativeWindParticle.nSetTrailLength(nativePtr, (int) dataLayer.getParticleLife());
                NativeWindParticle.nSetColorIntensity(nativePtr, dataLayer.getColorIntensity());
                NativeWindParticle.nSetColorSaturation(nativePtr, dataLayer.getColorSaturation());
                NativeWindParticle.nSetColorBrightness(nativePtr, dataLayer.getColorValue());
                // Allocate direct buffers for native→Java GL data transfer
                vertexBuffer = ByteBuffer.allocateDirect(MAX_SEGMENTS * 2 * 2 * 4)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer();
                colorBuffer = ByteBuffer.allocateDirect(MAX_SEGMENTS * 2 * 4 * 4)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer();
            }
        } else {
            Log.w(TAG, "Native library not available — particles disabled");
        }
    }

    @Override
    protected void drawImpl(GLMapView view, int renderPass) {
        if ((renderPass & GLMapView.RENDER_PASS_SURFACE) == 0) return;
        if (!dataLayer.isVisible() || !dataLayer.isShowParticles()) return;
        if (!nativeAvailable || nativePtr == 0) return;

        // Push wind data to native whenever Java side has new data
        // (don't gate on hasData — push unconditionally so native gets updates)
        if (dataLayer.hasData()) {
            long gen = dataLayer.getStreamlineGeneration();
            if (gen != lastDataGeneration) {
                pushWindData();
                lastDataGeneration = gen;
                nativeHasData = true;
                Log.d(TAG, "Wind data pushed to native, gen=" + gen);
            }
        }

        // Gate on NATIVE having data, not Java side
        if (!nativeHasData) return;

        // Push config changes only when they actually change
        // (setParticleCount reinits particles — must not call every frame!)
        pushConfigIfChanged();

        // Push projection params using scene model dimensions.
        // ATAK does multi-pass rendering — currentPass may be a 512×512 offscreen
        // texture pass. The scene model's forward() handles this correctly, so we
        // use the scene's focus and dimensions for the native Mercator projection.
        //
        // Strategy: project 2 reference points (center and center+1°N) through
        // the scene model to derive the correct pixels-per-degree for THIS pass.
        com.atakmap.map.MapSceneModel scene = view.currentPass.scene;

        double centerLat = view.currentPass.drawLat;
        double centerLon = view.currentPass.drawLng;
        double rotation = view.currentPass.drawRotation;
        double tilt = view.currentPass.drawTilt;

        // Get the ACTUAL render target size from the current pass
        float vpW = view.currentPass.right - view.currentPass.left;
        float vpH = view.currentPass.top - view.currentPass.bottom;

        // Derive PPD from the scene model by measuring how many pixels 1° covers
        // This is accurate regardless of render pass size, tilt, or projection.
        com.atakmap.math.PointD ptCenter = new com.atakmap.math.PointD(0, 0, 0);
        com.atakmap.math.PointD ptNorth = new com.atakmap.math.PointD(0, 0, 0);
        scene.forward(new com.atakmap.coremap.maps.coords.GeoPoint(centerLat, centerLon), ptCenter);
        scene.forward(new com.atakmap.coremap.maps.coords.GeoPoint(
                Math.min(centerLat + 0.5, 85.0), centerLon), ptNorth);

        double dxN = ptNorth.x - ptCenter.x;
        double dyN = ptNorth.y - ptCenter.y;
        double pixPerHalfDeg = Math.sqrt(dxN * dxN + dyN * dyN);
        double ppd = pixPerHalfDeg * 2.0;
        if (ppd < 1.0) ppd = 1.0;

        // Focus point = where the center of the map projects to in screen coords
        float focusX = (float) ptCenter.x;
        float focusY = (float) ptCenter.y;

        NativeWindParticle.nSetProjection(nativePtr,
                centerLat, centerLon, rotation, tilt, ppd,
                vpW, vpH, focusX, focusY);

        // Set up ortho projection matching this render pass
        GLES20FixedPipeline.glMatrixMode(GLES20FixedPipeline.GL_PROJECTION);
        GLES20FixedPipeline.glPushMatrix();
        GLES20FixedPipeline.glLoadIdentity();
        GLES20FixedPipeline.glOrthof(0, vpW, vpH, 0, -1, 1);

        GLES20FixedPipeline.glMatrixMode(GLES20FixedPipeline.GL_MODELVIEW);
        GLES20FixedPipeline.glPushMatrix();
        GLES20FixedPipeline.glLoadIdentity();

        // Native C++: advance cursors + project + fill buffers (1 JNI call)
        int numVerts = NativeWindParticle.nAdvance(nativePtr);
        if (numVerts >= 2 && vertexBuffer != null && colorBuffer != null) {
            // Copy native buffers to Java direct buffers (2 JNI calls, memcpy)
            vertexBuffer.clear();
            colorBuffer.clear();
            NativeWindParticle.nCopyVertexBuffer(nativePtr, vertexBuffer);
            NativeWindParticle.nCopyColorBuffer(nativePtr, colorBuffer);
            vertexBuffer.position(0);
            vertexBuffer.limit(numVerts * 2);
            colorBuffer.position(0);
            colorBuffer.limit(numVerts * 4);

            // GL draw using ATAK's fixed pipeline wrapper
            GLES20FixedPipeline.glEnableClientState(GLES20FixedPipeline.GL_VERTEX_ARRAY);
            GLES20FixedPipeline.glEnableClientState(GLES20FixedPipeline.GL_COLOR_ARRAY);
            GLES20FixedPipeline.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20FixedPipeline.glLineWidth(dataLayer.getLineWidth());

            GLES20FixedPipeline.glVertexPointer(2, GLES20.GL_FLOAT, 0, vertexBuffer);
            GLES20FixedPipeline.glColorPointer(4, GLES20.GL_FLOAT, 0, colorBuffer);
            GLES20FixedPipeline.glDrawArrays(GLES20.GL_LINES, 0, numVerts);

            GLES20FixedPipeline.glDisableClientState(GLES20FixedPipeline.GL_COLOR_ARRAY);
            GLES20FixedPipeline.glDisableClientState(GLES20FixedPipeline.GL_VERTEX_ARRAY);
            GLES20FixedPipeline.glDisable(GLES20.GL_BLEND);
        }

        // Restore GL state
        GLES20FixedPipeline.glMatrixMode(GLES20FixedPipeline.GL_MODELVIEW);
        GLES20FixedPipeline.glPopMatrix();
        GLES20FixedPipeline.glMatrixMode(GLES20FixedPipeline.GL_PROJECTION);
        GLES20FixedPipeline.glPopMatrix();
    }

    @Override
    public void release() {
        if (nativePtr != 0) {
            NativeWindParticle.nDestroy(nativePtr);
            nativePtr = 0;
        }
        nativeAvailable = false;
        nativeHasData = false;
        vertexBuffer = null;
        colorBuffer = null;
    }

    // ── Push config to native (only when changed) ──────────────────────────

    private void pushConfigIfChanged() {
        int count = dataLayer.getParticleCount();
        float speed = dataLayer.getParticleSpeed();
        int trail = (int) dataLayer.getParticleLife();
        float intensity = dataLayer.getColorIntensity();
        float sat = dataLayer.getColorSaturation();
        float bright = dataLayer.getColorValue();

        if (count != lastPushedCount) {
            NativeWindParticle.nSetParticleCount(nativePtr, count);
            lastPushedCount = count;
        }
        if (speed != lastPushedSpeed) {
            NativeWindParticle.nSetParticleSpeed(nativePtr, speed);
            lastPushedSpeed = speed;
        }
        if (trail != lastPushedTrail) {
            NativeWindParticle.nSetTrailLength(nativePtr, trail);
            lastPushedTrail = trail;
        }
        if (intensity != lastPushedIntensity) {
            NativeWindParticle.nSetColorIntensity(nativePtr, intensity);
            lastPushedIntensity = intensity;
        }
        if (sat != lastPushedSaturation) {
            NativeWindParticle.nSetColorSaturation(nativePtr, sat);
            lastPushedSaturation = sat;
        }
        if (bright != lastPushedBrightness) {
            NativeWindParticle.nSetColorBrightness(nativePtr, bright);
            lastPushedBrightness = bright;
        }
    }

    // ── Push wind grid to native ──────────────────────────────────────────

    private void pushWindData() {
        double[][] speed = dataLayer.getWindSpeed();
        double[][] dir = dataLayer.getWindDirection();
        if (speed == null || dir == null) return;

        int rows = dataLayer.getGridRows();
        int cols = dataLayer.getGridCols();
        if (rows <= 0 || cols <= 0) return;

        // Flatten 2D arrays to 1D for JNI
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

        Log.d(TAG, "pushWindData: " + rows + "x" + cols + " grid, bounds=["
                + String.format("%.4f", dataLayer.getGridSouth()) + ","
                + String.format("%.4f", dataLayer.getGridWest()) + "]-["
                + String.format("%.4f", dataLayer.getGridNorth()) + ","
                + String.format("%.4f", dataLayer.getGridEast()) + "]"
                + ", streamlines=" + NativeWindParticle.nGetStreamlineCount(nativePtr));
    }
}
