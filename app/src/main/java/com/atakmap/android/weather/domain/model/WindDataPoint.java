package com.atakmap.android.weather.domain.model;

/**
 * Simple data point for wind rose computation.
 * Represents a single wind observation (direction + speed) at a given time.
 *
 * <p>Sprint 9 (S9.3): Used as input to {@code WindRoseView}.</p>
 */
public class WindDataPoint {

    /** Wind direction in degrees (0-360, meteorological: direction wind comes FROM). */
    public final double direction;

    /** Wind speed in m/s (internal unit). */
    public final double speed;

    /** Observation timestamp in milliseconds since epoch. */
    public final long timestamp;

    public WindDataPoint(double direction, double speed, long timestamp) {
        this.direction = direction;
        this.speed     = speed;
        this.timestamp = timestamp;
    }

    /**
     * Returns the 16-sector index (0 = N, 1 = NNE, ..., 15 = NNW).
     * Each sector spans 22.5 degrees.
     */
    public int getSectorIndex() {
        double d = ((direction % 360) + 360) % 360;
        return ((int) Math.round(d / 22.5)) % 16;
    }

    /**
     * Returns the Beaufort band index for this wind speed.
     * <ul>
     *   <li>0 = 0-1 m/s (Calm)</li>
     *   <li>1 = 2-3 m/s (Light)</li>
     *   <li>2 = 4-5 m/s (Gentle)</li>
     *   <li>3 = 6-8 m/s (Moderate)</li>
     *   <li>4 = 9-11 m/s (Fresh)</li>
     *   <li>5 = 12+ m/s (Strong+)</li>
     * </ul>
     */
    public int getBeaufortBand() {
        if (speed < 2)  return 0;
        if (speed < 4)  return 1;
        if (speed < 6)  return 2;
        if (speed < 9)  return 3;
        if (speed < 12) return 4;
        return 5;
    }
}
