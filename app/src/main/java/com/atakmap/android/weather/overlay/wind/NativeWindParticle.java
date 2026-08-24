package com.atakmap.android.weather.overlay.wind;

import com.atakmap.coremap.log.Log;

/**
 * JNI bridge to the native C++ wind particle engine.
 *
 * <p>All particle simulation, streamline integration, geo→screen projection,
 * and GL vertex buffer construction happens in C++ — zero Java overhead
 * in the rendering hot path.</p>
 *
 * <p>Usage from GLWindParticleLayer.drawImpl():</p>
 * <pre>
 *   NativeWindParticle.nSetProjection(ptr, lat, lon, rot, tilt, scale, w, h, fx, fy);
 *   NativeWindParticle.nAdvanceAndDraw(ptr, lineWidth);
 * </pre>
 */
public final class NativeWindParticle {

    private static final String TAG = "NativeWindParticle";
    private static boolean loaded = false;

    static {
        try {
            System.loadLibrary("windparticle");
            loaded = true;
            Log.d(TAG, "libwindparticle.so loaded");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load libwindparticle.so — falling back to Java", e);
            loaded = false;
        }
    }

    /** Returns true if the native library loaded successfully. */
    public static boolean isAvailable() { return loaded; }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /** Create a native engine instance. Returns pointer (opaque long). */
    public static native long nCreate();

    /** Destroy a native engine instance. */
    public static native void nDestroy(long ptr);

    // ── Wind data ─────────────────────────────────────────────────────────

    /**
     * Set the wind field grid data. Triggers streamline recomputation.
     *
     * @param speed flat row-major double array [rows*cols] of wind speed (m/s)
     * @param dir   flat row-major double array [rows*cols] of wind direction (degrees)
     */
    public static native void nSetWindField(long ptr,
            double[] speed, double[] dir,
            int rows, int cols,
            double north, double south, double west, double east);

    public static native void nClearWindField(long ptr);

    // ── Projection (call each frame from GL thread) ───────────────────────

    /**
     * Update the map projection parameters. The native engine uses a simplified
     * Mercator projection — accurate enough for overlay particles.
     */
    public static native void nSetProjection(long ptr,
            double centerLat, double centerLon,
            double rotation, double tilt, double scale,
            float vpW, float vpH, float focusX, float focusY);

    // ── Frame update (call from GL thread) ─────────────────────────────────

    /**
     * Advance all particles, project trail points, fill vertex/color buffers
     * in native C++. Returns number of vertices to draw (GL_LINES pairs).
     * GL drawing is done on the Java side using GLES20FixedPipeline.
     */
    public static native int nAdvance(long ptr);

    /**
     * Copy native vertex buffer (x,y pairs) into a Java direct FloatBuffer.
     * Call after nAdvance(). Buffer must have capacity >= vertexCount * 2 floats.
     */
    public static native void nCopyVertexBuffer(long ptr, java.nio.FloatBuffer directBuffer);

    /**
     * Copy native color buffer (r,g,b,a quads) into a Java direct FloatBuffer.
     * Call after nAdvance(). Buffer must have capacity >= vertexCount * 4 floats.
     */
    public static native void nCopyColorBuffer(long ptr, java.nio.FloatBuffer directBuffer);

    // ── Configuration ─────────────────────────────────────────────────────

    public static native void nSetParticleCount(long ptr, int n);
    public static native void nSetParticleSpeed(long ptr, float s);
    public static native void nSetTrailLength(long ptr, int t);
    public static native void nSetColorIntensity(long ptr, float v);
    public static native void nSetColorSaturation(long ptr, float v);
    public static native void nSetColorBrightness(long ptr, float v);

    // ── V4 Hybrid: Viewport + Geo-space output ──────────────────────────

    /** Set viewport geo bounds so particles spawn within visible area. */
    public static native void nSetViewportBounds(long ptr,
            double north, double south, double west, double east);

    /**
     * Advance particles and return HEAD positions as geo coordinates.
     * Returns number of active particles (each = 6 floats in geo buffer).
     */
    public static native int nAdvanceGeo(long ptr);

    /**
     * Copy native geo buffer {lat, lon, speed} into a Java direct FloatBuffer.
     * Buffer must have capacity >= count * 3 floats.
     */
    public static native void nCopyGeoBuffer(long ptr, java.nio.FloatBuffer directBuffer);

    // ── Stats ─────────────────────────────────────────────────────────────

    /** Returns last frame computation time in microseconds. */
    public static native float nGetLastFrameTimeUs(long ptr);

    /** Returns number of pre-computed streamlines. */
    public static native int nGetStreamlineCount(long ptr);

    private NativeWindParticle() {} // utility class
}
