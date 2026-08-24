package com.atakmap.android.weather.overlay.marine;

import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.AbstractLayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Marine current particle data layer — completely independent from wind particles.
 *
 * <p>Holds ocean current velocity + direction grids and pre-computed streamlines.
 * Each instance gets its own native C++ engine via {@code NativeWindParticle.nCreate()}.
 * This ensures marine and wind particles can be active simultaneously without interference.</p>
 *
 * <p>Differences from {@link com.atakmap.android.weather.overlay.wind.WindParticleLayer}:</p>
 * <ul>
 *   <li>Default particle count: 800 (ocean currents are slower, need fewer particles)</li>
 *   <li>Default speed: 2.0 (currents are ~0.1-2 m/s, need higher multiplier for visibility)</li>
 *   <li>Step size: 100m (currents cover larger distances)</li>
 *   <li>Own color ramp: navy→cyan→white (0-2 m/s) instead of green→magenta (0-25 m/s)</li>
 * </ul>
 */
public class MarineParticleLayer extends AbstractLayer {

    private static final String TAG = "MarineParticleLayer";

    // ── Current field data ────────────────────────────────────────────────
    private double[][] currentSpeed;
    private double[][] currentDirection;
    private double gridNorth, gridSouth, gridWest, gridEast;
    private int gridRows, gridCols;
    private boolean hasData = false;

    // ── Pre-computed streamlines ──────────────────────────────────────────
    private volatile List<Streamline> streamlines = new ArrayList<>();
    private volatile boolean streamlinesReady = false;
    private volatile long streamlineGeneration = 0;

    private static final int NUM_STREAMLINES = 150;
    private static final int STEPS_PER_STREAMLINE = 600;
    private static final double STEP_SIZE_M = 100.0; // larger steps for ocean scale

    private final ExecutorService computeExecutor = Executors.newSingleThreadExecutor();
    private final Random rng = new Random();

    // ── Configuration (marine defaults) ──────────────────────────────────
    private int   particleCount = 800;
    private float particleSpeed = 2.0f;   // higher multiplier — currents are slow
    private float particleLife  = 120f;
    private float lineWidth     = 2.5f;
    private float fadeOpacity   = 0.96f;
    private boolean showParticles = false; // off by default

    // ── Color controls ───────────────────────────────────────────────────
    private float colorIntensity  = 1.0f;
    private float colorSaturation = 1.0f;
    private float colorValue      = 1.2f;

    // ── Trail fade + line width ──────────────────────────────────────────
    private int   trailFadeAlpha = 4;
    private float trailLineWidth = 2.5f;

    public MarineParticleLayer(String name) {
        super(name);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Streamline data class (same structure as wind — reusable)
    // ══════════════════════════════════════════════════════════════════════

    public static class Streamline {
        public final double[] lat;
        public final double[] lon;
        public final float[] speed;
        public final int length;

        public Streamline(double[] lat, double[] lon, float[] speed, int length) {
            this.lat = lat;
            this.lon = lon;
            this.speed = speed;
            this.length = length;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Current data setters
    // ══════════════════════════════════════════════════════════════════════

    public synchronized void setCurrentField(double[][] speed, double[][] direction,
                                              double north, double south,
                                              double west, double east) {
        this.currentSpeed = speed;
        this.currentDirection = direction;
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

        if (hasData) {
            recomputeStreamlines();
        } else {
            streamlinesReady = false;
            streamlines = new ArrayList<>();
        }
    }

    public synchronized void clearCurrentField() {
        this.currentSpeed = null;
        this.currentDirection = null;
        this.hasData = false;
        this.streamlinesReady = false;
        this.streamlines = new ArrayList<>();
    }

    // ══════════════════════════════════════════════════════════════════════
    // Streamline computation (RK4 — same algorithm as wind, different params)
    // ══════════════════════════════════════════════════════════════════════

    private void recomputeStreamlines() {
        final long gen = ++streamlineGeneration;

        final double[][] snapSpeed;
        final double[][] snapDir;
        final double sn, ss, sw, se;
        final int sRows, sCols;
        synchronized (this) {
            if (!hasData || currentSpeed == null || currentDirection == null) return;
            sRows = gridRows;
            sCols = gridCols;
            snapSpeed = new double[sRows][];
            snapDir = new double[sRows][];
            for (int r = 0; r < sRows; r++) {
                snapSpeed[r] = currentSpeed[r].clone();
                snapDir[r] = currentDirection[r].clone();
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

                int seedRows = (int) Math.sqrt(NUM_STREAMLINES * latRange / Math.max(0.01, lonRange));
                int seedCols = NUM_STREAMLINES / Math.max(1, seedRows);
                if (seedRows < 2) seedRows = 2;
                if (seedCols < 2) seedCols = 2;

                for (int sr = 0; sr < seedRows; sr++) {
                    for (int sc = 0; sc < seedCols; sc++) {
                        if (gen != streamlineGeneration) return;

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
                    Log.d(TAG, "Marine streamlines: " + result.size()
                            + " paths in " + dt + "ms");
                }
            } catch (Exception e) {
                Log.e(TAG, "Marine streamline error", e);
            }
        });
    }

    private Streamline integrateStreamline(
            double startLat, double startLon,
            double[][] speed, double[][] dir,
            double north, double south, double west, double east,
            int rows, int cols,
            double degPerMeterLat, double degPerMeterLon) {

        double[] lats = new double[STEPS_PER_STREAMLINE];
        double[] lons = new double[STEPS_PER_STREAMLINE];
        float[] speeds = new float[STEPS_PER_STREAMLINE];

        double lat = startLat, lon = startLon;
        int validSteps = 0;

        for (int step = 0; step < STEPS_PER_STREAMLINE; step++) {
            if (lat < south || lat > north || lon < west || lon > east) break;

            double[] w = bilinearInterp(lat, lon, speed, dir,
                    north, south, west, east, rows, cols);
            if (w == null || Double.isNaN(w[0]) || w[0] < 0.001) break; // NaN = land

            lats[step] = lat;
            lons[step] = lon;
            speeds[step] = (float) w[0];
            validSteps = step + 1;

            // RK4
            double h = STEP_SIZE_M;
            double[] k1 = velocity(w[0], w[1], degPerMeterLat, degPerMeterLon);

            double[] w2 = bilinearInterp(lat + 0.5*h*k1[0], lon + 0.5*h*k1[1],
                    speed, dir, north, south, west, east, rows, cols);
            if (w2 == null || Double.isNaN(w2[0])) break;
            double[] k2 = velocity(w2[0], w2[1], degPerMeterLat, degPerMeterLon);

            double[] w3 = bilinearInterp(lat + 0.5*h*k2[0], lon + 0.5*h*k2[1],
                    speed, dir, north, south, west, east, rows, cols);
            if (w3 == null || Double.isNaN(w3[0])) break;
            double[] k3 = velocity(w3[0], w3[1], degPerMeterLat, degPerMeterLon);

            double[] w4 = bilinearInterp(lat + h*k3[0], lon + h*k3[1],
                    speed, dir, north, south, west, east, rows, cols);
            if (w4 == null || Double.isNaN(w4[0])) break;
            double[] k4 = velocity(w4[0], w4[1], degPerMeterLat, degPerMeterLon);

            lat += h/6.0 * (k1[0] + 2*k2[0] + 2*k3[0] + k4[0]);
            lon += h/6.0 * (k1[1] + 2*k2[1] + 2*k3[1] + k4[1]);
        }

        if (validSteps < 10) return null;
        return new Streamline(lats, lons, speeds, validSteps);
    }

    private static double[] velocity(double speed, double dirDeg,
                                      double degPerMeterLat, double degPerMeterLon) {
        // Ocean current direction = direction current flows TOWARDS (unlike wind which is FROM)
        double bearingRad = Math.toRadians(dirDeg);
        double dLat = Math.cos(bearingRad) * degPerMeterLat;
        double dLon = Math.sin(bearingRad) * degPerMeterLon;
        return new double[]{dLat, dLon};
    }

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

        double s00 = speed[r0][c0], s01 = speed[r0][c0+1];
        double s10 = speed[r0+1][c0], s11 = speed[r0+1][c0+1];

        // Any NaN = land point → abort
        if (Double.isNaN(s00) || Double.isNaN(s01) || Double.isNaN(s10) || Double.isNaN(s11))
            return null;

        double spd = s00*(1-fr)*(1-fc) + s01*(1-fr)*fc + s10*fr*(1-fc) + s11*fr*fc;

        double d00 = Math.toRadians(dir[r0][c0]);
        double d01 = Math.toRadians(dir[r0][c0+1]);
        double d10 = Math.toRadians(dir[r0+1][c0]);
        double d11 = Math.toRadians(dir[r0+1][c0+1]);

        if (Double.isNaN(d00) || Double.isNaN(d01) || Double.isNaN(d10) || Double.isNaN(d11))
            return null;

        double sinS = Math.sin(d00)*(1-fr)*(1-fc) + Math.sin(d01)*(1-fr)*fc
                + Math.sin(d10)*fr*(1-fc) + Math.sin(d11)*fr*fc;
        double cosS = Math.cos(d00)*(1-fr)*(1-fc) + Math.cos(d01)*(1-fr)*fc
                + Math.cos(d10)*fr*(1-fc) + Math.cos(d11)*fr*fc;
        double d = Math.toDegrees(Math.atan2(sinS, cosS));
        if (d < 0) d += 360;

        return new double[]{spd, d};
    }

    // ══════════════════════════════════════════════════════════════════════
    // Accessors — used by MarineParticleBitmapView and native engine
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
    public synchronized double[][] getWindSpeed() { return currentSpeed; }
    public synchronized double[][] getWindDirection() { return currentDirection; }

    // ── Configuration ────────────────────────────────────────────────────

    public int getParticleCount()       { return particleCount; }
    public float getParticleSpeed()     { return particleSpeed; }
    public float getParticleLife()       { return particleLife; }
    public float getLineWidth()         { return lineWidth; }
    public float getFadeOpacity()       { return fadeOpacity; }
    public boolean isShowParticles()    { return showParticles; }

    public void setParticleCount(int n)      { this.particleCount = Math.max(10, Math.min(5000, n)); }
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

    public int getTrailFadeAlpha()     { return trailFadeAlpha; }
    public float getTrailLineWidth()   { return trailLineWidth; }
    public void setTrailFadeAlpha(int v)    { this.trailFadeAlpha = Math.max(1, Math.min(20, v)); }
    public void setTrailLineWidth(float v)  { this.trailLineWidth = Math.max(0.5f, Math.min(6f, v)); }
}
