package com.atakmap.android.weather.overlay.wind;

import com.atakmap.map.layer.AbstractLayer;

/**
 * Layer2 data wrapper for the Windy-style wind particle flow overlay.
 *
 * <p>Holds the wind vector field (speed + direction grids) and configuration.
 * The GL renderer ({@code GLWindParticleLayer}) reads this data on each frame
 * and updates particle positions accordingly.</p>
 *
 * <p>Registered on {@code RenderStack.MAP_SURFACE_OVERLAYS} so particles
 * render flat on the map surface, below widgets but above base tiles.</p>
 */
public class WindParticleLayer extends AbstractLayer {

    // ── Wind field data (set from heatmap grid) ──────────────────────────────
    private double[][] windSpeed;     // [row][col] m/s
    private double[][] windDirection; // [row][col] degrees (meteorological)
    private double gridNorth, gridSouth, gridWest, gridEast;
    private int gridRows, gridCols;
    private boolean hasData = false;

    // ── Configuration ────────────────────────────────────────────────────────
    private int   particleCount = 3000;
    private float particleSpeed = 1.0f;    // speed multiplier
    private float particleLife  = 80f;     // frames before respawn
    private float lineWidth     = 1.5f;
    private float fadeOpacity   = 0.96f;   // trail fade factor (0.9-0.99)
    private boolean showParticles = true;

    public WindParticleLayer(String name) {
        super(name);
    }

    // ── Wind data setters (called from OverlayTabCoordinator) ────────────────

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
    }

    public synchronized void clearWindField() {
        this.windSpeed = null;
        this.windDirection = null;
        this.hasData = false;
    }

    // ── Accessors (called from GL thread) ────────────────────────────────────

    public synchronized boolean hasData() { return hasData; }

    public synchronized double[][] getWindSpeed() { return windSpeed; }
    public synchronized double[][] getWindDirection() { return windDirection; }
    public synchronized double getGridNorth() { return gridNorth; }
    public synchronized double getGridSouth() { return gridSouth; }
    public synchronized double getGridWest()  { return gridWest; }
    public synchronized double getGridEast()  { return gridEast; }
    public synchronized int getGridRows() { return gridRows; }
    public synchronized int getGridCols() { return gridCols; }

    // ── Configuration accessors ──────────────────────────────────────────────

    public int getParticleCount()       { return particleCount; }
    public float getParticleSpeed()     { return particleSpeed; }
    public float getParticleLife()       { return particleLife; }
    public float getLineWidth()         { return lineWidth; }
    public float getFadeOpacity()       { return fadeOpacity; }
    public boolean isShowParticles()    { return showParticles; }

    public void setParticleCount(int n)      { this.particleCount = Math.max(100, Math.min(10000, n)); }
    public void setParticleSpeed(float s)    { this.particleSpeed = Math.max(0.1f, Math.min(5f, s)); }
    public void setParticleLife(float l)      { this.particleLife = Math.max(20f, Math.min(200f, l)); }
    public void setLineWidth(float w)        { this.lineWidth = Math.max(0.5f, Math.min(4f, w)); }
    public void setFadeOpacity(float o)      { this.fadeOpacity = Math.max(0.85f, Math.min(0.99f, o)); }
    public void setShowParticles(boolean v)  { this.showParticles = v; }

    // ── Color controls (intensity / saturation / value) ──────────────────────
    private float colorIntensity  = 1.0f;   // alpha multiplier (0.0–1.0)
    private float colorSaturation = 1.0f;   // saturation multiplier (0.0–1.0)
    private float colorValue      = 1.0f;   // brightness multiplier (0.0–1.5)

    public float getColorIntensity()  { return colorIntensity; }
    public float getColorSaturation() { return colorSaturation; }
    public float getColorValue()      { return colorValue; }

    public void setColorIntensity(float v)  { this.colorIntensity  = Math.max(0f, Math.min(1f, v)); }
    public void setColorSaturation(float v) { this.colorSaturation = Math.max(0f, Math.min(1f, v)); }
    public void setColorValue(float v)      { this.colorValue      = Math.max(0f, Math.min(1.5f, v)); }

    /**
     * Interpolate wind at a given lat/lon from the grid.
     * Returns [speed_m_s, direction_degrees] or null if outside grid.
     */
    public synchronized double[] interpolateWind(double lat, double lon) {
        if (!hasData || windSpeed == null || windDirection == null) return null;
        if (lat < gridSouth || lat > gridNorth || lon < gridWest || lon > gridEast) return null;

        // Bilinear interpolation
        double fracRow = (lat - gridSouth) / (gridNorth - gridSouth) * (gridRows - 1);
        double fracCol = (lon - gridWest) / (gridEast - gridWest) * (gridCols - 1);

        int r0 = Math.max(0, Math.min(gridRows - 2, (int) fracRow));
        int c0 = Math.max(0, Math.min(gridCols - 2, (int) fracCol));
        double fr = fracRow - r0;
        double fc = fracCol - c0;

        // Speed interpolation
        double s00 = windSpeed[r0][c0];
        double s01 = windSpeed[r0][c0 + 1];
        double s10 = windSpeed[r0 + 1][c0];
        double s11 = windSpeed[r0 + 1][c0 + 1];
        double spd = s00 * (1 - fr) * (1 - fc) + s01 * (1 - fr) * fc
                + s10 * fr * (1 - fc) + s11 * fr * fc;

        // Direction interpolation (must handle 360° wrap)
        double d00 = Math.toRadians(windDirection[r0][c0]);
        double d01 = Math.toRadians(windDirection[r0][c0 + 1]);
        double d10 = Math.toRadians(windDirection[r0 + 1][c0]);
        double d11 = Math.toRadians(windDirection[r0 + 1][c0 + 1]);

        double sinSum = Math.sin(d00) * (1 - fr) * (1 - fc) + Math.sin(d01) * (1 - fr) * fc
                + Math.sin(d10) * fr * (1 - fc) + Math.sin(d11) * fr * fc;
        double cosSum = Math.cos(d00) * (1 - fr) * (1 - fc) + Math.cos(d01) * (1 - fr) * fc
                + Math.cos(d10) * fr * (1 - fc) + Math.cos(d11) * fr * fc;
        double dir = Math.toDegrees(Math.atan2(sinSum, cosSum));
        if (dir < 0) dir += 360;

        return new double[]{spd, dir};
    }
}
