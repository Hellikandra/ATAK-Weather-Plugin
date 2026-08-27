package com.atakmap.android.weather.overlay.wind;

import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.AbstractLayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wind particle overlay — pre-computed streamline approach.
 *
 * <p><b>Architecture (Approach B — Sprint 29):</b></p>
 * <ol>
 *   <li>When wind data changes ({@link #setWindField}), streamlines are computed
 *       ONCE on a background thread using RK4 integration through the wind field.</li>
 *   <li>Each streamline is a dense array of (lat,lon) positions at ~50m spacing.</li>
 *   <li>The renderer ({@link WindParticleBitmapView}) animates "cursors" along
 *       these pre-computed paths — no per-frame physics needed.</li>
 * </ol>
 *
 * <p>This eliminates the CPU bottleneck of per-frame wind interpolation and
 * produces perfectly smooth streamlines at any zoom level.</p>
 */
public class WindParticleLayer extends AbstractLayer {

    private static final String TAG = "WindParticleLayer";

    // ── Wind field data ───────────────────────────────────────────────────
    private double[][] windSpeed;
    private double[][] windDirection;
    private double gridNorth, gridSouth, gridWest, gridEast;
    private int gridRows, gridCols;
    private boolean hasData = false;

    // ── Pre-computed streamlines (Approach B) ─────────────────────────────
    /**
     * Each streamline is double[steps][2] where [i][0]=lat, [i][1]=lon.
     * Also stores speed at each point for color mapping.
     */
    private volatile List<Streamline> streamlines = new ArrayList<>();
    private volatile boolean streamlinesReady = false;
    private volatile long streamlineGeneration = 0; // incremented on each recompute

    /** Number of streamlines to seed across the grid. */
    private static final int NUM_STREAMLINES = 200;
    /** Integration steps per streamline (at ~50m each = ~25km max length). */
    private static final int STEPS_PER_STREAMLINE = 500;
    /** Integration step size in meters. */
    private static final double STEP_SIZE_M = 50.0;

    private final ExecutorService computeExecutor = Executors.newSingleThreadExecutor();
    private final Random rng = new Random();

    // ── Configuration ─────────────────────────────────────────────────────
    private int   particleCount = 1500;
    private float particleSpeed = 1.0f;
    private float particleLife  = 100f;
    private float lineWidth     = 2.5f;
    private float fadeOpacity   = 0.96f;
    private boolean showParticles = true;

    // ── Color controls ────────────────────────────────────────────────────
    private float colorIntensity  = 1.0f;
    private float colorSaturation = 1.0f;
    private float colorValue      = 1.2f;

    public WindParticleLayer(String name) {
        super(name);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Streamline data class
    // ══════════════════════════════════════════════════════════════════════

    /** A single pre-computed streamline path through the wind field. */
    public static class Streamline {
        /** Latitude at each step. */
        public final double[] lat;
        /** Longitude at each step. */
        public final double[] lon;
        /** Wind speed at each step (for color mapping). */
        public final float[] speed;
        /** Number of valid steps (may be < array length if streamline left grid). */
        public final int length;

        public Streamline(double[] lat, double[] lon, float[] speed, int length) {
            this.lat = lat;
            this.lon = lon;
            this.speed = speed;
            this.length = length;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Wind data setters
    // ══════════════════════════════════════════════════════════════════════

    public synchronized void setWindField(double[][] speed, double[][] direction,
                                           double north, double south,
                                           double west, double east) {
        this.windSpeed = speed;
        this.windDirection = direction;
        this.gridNorth = north;
        this.gridSouth = south;
        this.gridWest  = west;
        this.gridEast  = east;
        if (speed != null) {
            this.gridRows = speed.length;
            this.gridCols = gridRows > 0 ? speed[0].length : 0;
        }
        this.hasData = (speed != null && direction != null
                && gridRows > 0 && gridCols > 0);

        // Trigger streamline recomputation on background thread
        if (hasData) {
            recomputeStreamlines();
        } else {
            streamlinesReady = false;
            streamlines = new ArrayList<>();
        }
    }

    public synchronized void clearWindField() {
        this.windSpeed = null;
        this.windDirection = null;
        this.hasData = false;
        this.streamlinesReady = false;
        this.streamlines = new ArrayList<>();
    }

    // ══════════════════════════════════════════════════════════════════════
    // Streamline computation (RK4 integration on background thread)
    // ══════════════════════════════════════════════════════════════════════

    private void recomputeStreamlines() {
        final long gen = ++streamlineGeneration;

        // Snapshot wind data for background thread (avoid synchronized access during compute)
        final double[][] snapSpeed;
        final double[][] snapDir;
        final double sn, ss, sw, se;
        final int sRows, sCols;
        synchronized (this) {
            if (!hasData || windSpeed == null || windDirection == null) return;
            sRows = gridRows;
            sCols = gridCols;
            snapSpeed = new double[sRows][];
            snapDir = new double[sRows][];
            for (int r = 0; r < sRows; r++) {
                snapSpeed[r] = windSpeed[r].clone();
                snapDir[r] = windDirection[r].clone();
            }
            sn = gridNorth; ss = gridSouth; sw = gridWest; se = gridEast;
        }

        computeExecutor.submit(() -> {
            try {
                long t0 = System.currentTimeMillis();
                List<Streamline> result = new ArrayList<>(NUM_STREAMLINES);

                double latRange = sn - ss;
                double lonRange = se - sw;
                double midLat = (sn + ss) * 0.5;
                double degPerMeterLat = 1.0 / 111320.0;
                double degPerMeterLon = 1.0 / (111320.0 * Math.cos(Math.toRadians(midLat)));

                // Seed points: grid pattern with jitter
                int seedRows = (int) Math.sqrt(NUM_STREAMLINES * latRange / lonRange);
                int seedCols = NUM_STREAMLINES / Math.max(1, seedRows);
                if (seedRows < 2) seedRows = 2;
                if (seedCols < 2) seedCols = 2;

                for (int sr = 0; sr < seedRows; sr++) {
                    for (int sc = 0; sc < seedCols; sc++) {
                        if (gen != streamlineGeneration) return; // cancelled

                        // Seed position with jitter
                        double seedLat = ss + (sr + 0.1 + 0.8 * rng.nextDouble()) * latRange / seedRows;
                        double seedLon = sw + (sc + 0.1 + 0.8 * rng.nextDouble()) * lonRange / seedCols;

                        Streamline sl = integrateStreamline(
                                seedLat, seedLon,
                                snapSpeed, snapDir,
                                sn, ss, sw, se, sRows, sCols,
                                degPerMeterLat, degPerMeterLon);
                        if (sl != null && sl.length > 10) {
                            result.add(sl);
                        }
                    }
                }

                if (gen == streamlineGeneration) {
                    streamlines = result;
                    streamlinesReady = true;
                    long dt = System.currentTimeMillis() - t0;
                    Log.d(TAG, "Streamlines computed: " + result.size()
                            + " paths in " + dt + "ms");
                }
            } catch (Exception e) {
                Log.e(TAG, "Streamline compute error", e);
            }
        });
    }

    /**
     * Integrate a single streamline using RK4 through the wind field.
     */
    private Streamline integrateStreamline(
            double startLat, double startLon,
            double[][] speed, double[][] dir,
            double north, double south, double west, double east,
            int rows, int cols,
            double degPerMeterLat, double degPerMeterLon) {

        double[] lats = new double[STEPS_PER_STREAMLINE];
        double[] lons = new double[STEPS_PER_STREAMLINE];
        float[] speeds = new float[STEPS_PER_STREAMLINE];

        double lat = startLat;
        double lon = startLon;
        int validSteps = 0;

        for (int step = 0; step < STEPS_PER_STREAMLINE; step++) {
            // Bounds check
            if (lat < south || lat > north || lon < west || lon > east) break;

            // Interpolate wind at current position
            double[] w = bilinearInterp(lat, lon, speed, dir,
                    north, south, west, east, rows, cols);
            if (w == null || w[0] < 0.1) break;

            lats[step] = lat;
            lons[step] = lon;
            speeds[step] = (float) w[0];
            validSteps = step + 1;

            // RK4 integration
            double h = STEP_SIZE_M;

            // k1
            double[] k1 = windVelocity(w[0], w[1], degPerMeterLat, degPerMeterLon);

            // k2 — half step using k1
            double lat2 = lat + 0.5 * h * k1[0];
            double lon2 = lon + 0.5 * h * k1[1];
            double[] w2 = bilinearInterp(lat2, lon2, speed, dir,
                    north, south, west, east, rows, cols);
            if (w2 == null) break;
            double[] k2 = windVelocity(w2[0], w2[1], degPerMeterLat, degPerMeterLon);

            // k3 — half step using k2
            double lat3 = lat + 0.5 * h * k2[0];
            double lon3 = lon + 0.5 * h * k2[1];
            double[] w3 = bilinearInterp(lat3, lon3, speed, dir,
                    north, south, west, east, rows, cols);
            if (w3 == null) break;
            double[] k3 = windVelocity(w3[0], w3[1], degPerMeterLat, degPerMeterLon);

            // k4 — full step using k3
            double lat4 = lat + h * k3[0];
            double lon4 = lon + h * k3[1];
            double[] w4 = bilinearInterp(lat4, lon4, speed, dir,
                    north, south, west, east, rows, cols);
            if (w4 == null) break;
            double[] k4 = windVelocity(w4[0], w4[1], degPerMeterLat, degPerMeterLon);

            // Combine
            lat += h / 6.0 * (k1[0] + 2 * k2[0] + 2 * k3[0] + k4[0]);
            lon += h / 6.0 * (k1[1] + 2 * k2[1] + 2 * k3[1] + k4[1]);
        }

        if (validSteps < 10) return null;
        return new Streamline(lats, lons, speeds, validSteps);
    }

    /**
     * Convert wind speed + meteorological direction to velocity in degrees/meter.
     * Wind FROM dir → particle moves opposite direction.
     */
    private static double[] windVelocity(double speed, double dirDeg,
                                          double degPerMeterLat, double degPerMeterLon) {
        double bearingRad = Math.toRadians((dirDeg + 180.0) % 360.0);
        double dLat = Math.cos(bearingRad) * degPerMeterLat;
        double dLon = Math.sin(bearingRad) * degPerMeterLon;
        return new double[]{dLat, dLon};
    }

    /**
     * Bilinear interpolation of wind speed and direction from the grid.
     * Non-synchronized — operates on snapshot arrays.
     */
    private static double[] bilinearInterp(
            double lat, double lon,
            double[][] speed, double[][] dir,
            double north, double south, double west, double east,
            int rows, int cols) {
        if (lat < south || lat > north || lon < west || lon > east) return null;

        double fracRow = (lat - south) / (north - south) * (rows - 1);
        double fracCol = (lon - west) / (east - west) * (cols - 1);

        int r0 = Math.max(0, Math.min(rows - 2, (int) fracRow));
        int c0 = Math.max(0, Math.min(cols - 2, (int) fracCol));
        double fr = fracRow - r0;
        double fc = fracCol - c0;

        double spd = speed[r0][c0] * (1-fr)*(1-fc) + speed[r0][c0+1] * (1-fr)*fc
                + speed[r0+1][c0] * fr*(1-fc) + speed[r0+1][c0+1] * fr*fc;

        // Direction interpolation via sin/cos for 360° wrap
        double d00 = Math.toRadians(dir[r0][c0]);
        double d01 = Math.toRadians(dir[r0][c0+1]);
        double d10 = Math.toRadians(dir[r0+1][c0]);
        double d11 = Math.toRadians(dir[r0+1][c0+1]);

        double sinS = Math.sin(d00)*(1-fr)*(1-fc) + Math.sin(d01)*(1-fr)*fc
                + Math.sin(d10)*fr*(1-fc) + Math.sin(d11)*fr*fc;
        double cosS = Math.cos(d00)*(1-fr)*(1-fc) + Math.cos(d01)*(1-fr)*fc
                + Math.cos(d10)*fr*(1-fc) + Math.cos(d11)*fr*fc;
        double d = Math.toDegrees(Math.atan2(sinS, cosS));
        if (d < 0) d += 360;

        return new double[]{spd, d};
    }

    // Keep interpolateWind for backward compatibility (wind arrows still use it)
    public synchronized double[] interpolateWind(double lat, double lon) {
        if (!hasData || windSpeed == null || windDirection == null) return null;
        return bilinearInterp(lat, lon, windSpeed, windDirection,
                gridNorth, gridSouth, gridWest, gridEast, gridRows, gridCols);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Accessors (GL thread reads these)
    // ══════════════════════════════════════════════════════════════════════

    public boolean hasData() { return hasData; }
    public boolean hasStreamlines() { return streamlinesReady && !streamlines.isEmpty(); }
    public List<Streamline> getStreamlines() { return streamlines; }
    public long getStreamlineGeneration() { return streamlineGeneration; }

    public synchronized double getGridNorth() { return gridNorth; }
    public synchronized double getGridSouth() { return gridSouth; }
    public synchronized double getGridWest()  { return gridWest; }
    public synchronized double getGridEast()  { return gridEast; }
    public synchronized int getGridRows() { return gridRows; }
    public synchronized int getGridCols() { return gridCols; }
    public synchronized double[][] getWindSpeed() { return windSpeed; }
    public synchronized double[][] getWindDirection() { return windDirection; }

    // ── Configuration accessors ───────────────────────────────────────────

    public int getParticleCount()       { return particleCount; }
    public float getParticleSpeed()     { return particleSpeed; }
    public float getParticleLife()       { return particleLife; }
    public float getLineWidth()         { return lineWidth; }
    public float getFadeOpacity()       { return fadeOpacity; }
    public boolean isShowParticles()    { return showParticles; }

    public void setParticleCount(int n)      { this.particleCount = Math.max(10, Math.min(10000, n)); }
    public void setParticleSpeed(float s)    { this.particleSpeed = Math.max(0.1f, Math.min(5f, s)); }
    public void setParticleLife(float l)      { this.particleLife = Math.max(20f, Math.min(200f, l)); }
    public void setLineWidth(float w)        { this.lineWidth = Math.max(0.5f, Math.min(4f, w)); }
    public void setFadeOpacity(float o)      { this.fadeOpacity = Math.max(0.85f, Math.min(0.99f, o)); }
    public void setShowParticles(boolean v)  { this.showParticles = v; }

    public float getColorIntensity()  { return colorIntensity; }
    public float getColorSaturation() { return colorSaturation; }
    public float getColorValue()      { return colorValue; }

    public void setColorIntensity(float v)  { this.colorIntensity  = Math.max(0f, Math.min(1f, v)); }
    public void setColorSaturation(float v) { this.colorSaturation = Math.max(0f, Math.min(1f, v)); }
    public void setColorValue(float v)      { this.colorValue      = Math.max(0f, Math.min(1.5f, v)); }

    // ── Trail fade + line width (V4 bitmap view controls) ─────────────────
    private int   trailFadeAlpha = 4;     // DST_OUT alpha per frame (1-20, lower=longer trails)
    private float trailLineWidth = 2.5f;  // dp

    public int getTrailFadeAlpha()     { return trailFadeAlpha; }
    public float getTrailLineWidth()   { return trailLineWidth; }
    public void setTrailFadeAlpha(int v)    { this.trailFadeAlpha = Math.max(1, Math.min(20, v)); }
    public void setTrailLineWidth(float v)  { this.trailLineWidth = Math.max(0.5f, Math.min(6f, v)); }
}
