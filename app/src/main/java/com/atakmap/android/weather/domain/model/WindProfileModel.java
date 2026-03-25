package com.atakmap.android.weather.domain.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Domain model for multi-altitude wind profile data.
 *
 * <p>Sprint 9 (S9.1): Extended to support both surface-level altitudes
 * (10m, 80m, 120m, 180m) and pressure-level altitudes (1000, 925, 850,
 * 700, 500, 300 hPa mapped to ISA altitudes).</p>
 */
public class WindProfileModel {

    /** Data origin for an altitude entry. */
    public static final String SOURCE_SURFACE  = "surface";
    public static final String SOURCE_PRESSURE = "pressure";

    public static class AltitudeEntry {
        public final int    altitudeMeters;
        public final double windSpeed;     // m/s
        public final double windDirection; // degrees
        public final double temperature;   // °C (may be NaN if unavailable)
        public final double windGusts;     // m/s (only at 10m surface)
        public final String source;        // "surface" or "pressure"
        public final Integer pressureHPa;  // pressure level in hPa, null for surface entries

        /** Full constructor with source and pressure info (Sprint 9). */
        public AltitudeEntry(int altitudeMeters, double windSpeed,
                             double windDirection, double temperature,
                             double windGusts, String source,
                             Integer pressureHPa) {
            this.altitudeMeters = altitudeMeters;
            this.windSpeed      = windSpeed;
            this.windDirection  = windDirection;
            this.temperature    = temperature;
            this.windGusts      = windGusts;
            this.source         = source != null ? source : SOURCE_SURFACE;
            this.pressureHPa   = pressureHPa;
        }

        /** Legacy constructor — surface entries, backward compatible. */
        public AltitudeEntry(int altitudeMeters, double windSpeed,
                             double windDirection, double temperature,
                             double windGusts) {
            this(altitudeMeters, windSpeed, windDirection, temperature,
                 windGusts, SOURCE_SURFACE, null);
        }

        /** @return true if this entry originated from a pressure-level data source */
        public boolean isPressureLevel() {
            return SOURCE_PRESSURE.equals(source);
        }
    }

    private final String           isoTime;
    private final List<AltitudeEntry> altitudes;

    public WindProfileModel(String isoTime, List<AltitudeEntry> altitudes) {
        this.isoTime   = isoTime;
        this.altitudes = altitudes;
    }

    public String              getIsoTime()  { return isoTime; }
    public List<AltitudeEntry> getAltitudes(){ return altitudes; }

    // ── Behavioral methods (Sprint 4 — S4.2) ──────────────────────────────────

    /**
     * Returns {@code true} if wind is calm at all altitudes.
     * "Calm" is defined as wind speed &lt; 2.0 m/s (~4 kt) at every level.
     *
     * @return true if all altitude entries have wind speed below threshold
     */
    public boolean isCalm() {
        if (altitudes == null || altitudes.isEmpty()) return true;
        for (AltitudeEntry entry : altitudes) {
            if (entry.windSpeed >= 2.0) return false;
        }
        return true;
    }

    /**
     * Find the altitude with the strongest wind gusts.
     * If no gust data is available (all zero), returns the entry with highest wind speed.
     *
     * @return the AltitudeEntry with maximum gusts, or null if the profile is empty
     */
    public AltitudeEntry getMaxGustAltitude() {
        if (altitudes == null || altitudes.isEmpty()) return null;
        AltitudeEntry maxGust  = altitudes.get(0);
        AltitudeEntry maxSpeed = altitudes.get(0);
        boolean hasGusts = false;
        for (AltitudeEntry entry : altitudes) {
            if (entry.windGusts > 0) hasGusts = true;
            if (entry.windGusts > maxGust.windGusts)  maxGust  = entry;
            if (entry.windSpeed > maxSpeed.windSpeed)  maxSpeed = entry;
        }
        return hasGusts ? maxGust : maxSpeed;
    }

    /**
     * Returns the maximum wind speed across all altitudes (m/s).
     *
     * @return peak wind speed, or 0 if profile is empty
     */
    public double peakWindSpeed() {
        if (altitudes == null || altitudes.isEmpty()) return 0;
        double max = 0;
        for (AltitudeEntry entry : altitudes) {
            if (entry.windSpeed > max) max = entry.windSpeed;
        }
        return max;
    }

    /**
     * Returns the wind shear between the lowest and highest altitude in m/s.
     * A high value indicates dangerous wind shear conditions.
     *
     * @return absolute difference in wind speed between lowest and highest altitudes
     */
    public double verticalShear() {
        if (altitudes == null || altitudes.size() < 2) return 0;
        AltitudeEntry lowest  = altitudes.get(0);
        AltitudeEntry highest = altitudes.get(altitudes.size() - 1);
        return Math.abs(highest.windSpeed - lowest.windSpeed);
    }

    /**
     * Returns the surface-level entry (lowest altitude) or null if empty.
     *
     * @return surface AltitudeEntry, typically 10m
     */
    public AltitudeEntry getSurface() {
        if (altitudes == null || altitudes.isEmpty()) return null;
        AltitudeEntry surface = altitudes.get(0);
        for (AltitudeEntry entry : altitudes) {
            if (entry.altitudeMeters < surface.altitudeMeters) surface = entry;
        }
        return surface;
    }

    // ── Sprint 9 helpers ──────────────────────────────────────────────────────

    /**
     * Returns the maximum altitude across all entries (metres).
     * Useful for scaling the Y-axis when pressure-level data extends to 9200m+.
     */
    public int getMaxAltitude() {
        if (altitudes == null || altitudes.isEmpty()) return 0;
        int max = 0;
        for (AltitudeEntry entry : altitudes) {
            if (entry.altitudeMeters > max) max = entry.altitudeMeters;
        }
        return max;
    }

    /**
     * Returns only surface-origin entries, filtered from the full list.
     */
    public List<AltitudeEntry> getSurfaceEntries() {
        List<AltitudeEntry> result = new ArrayList<>();
        if (altitudes == null) return result;
        for (AltitudeEntry entry : altitudes) {
            if (SOURCE_SURFACE.equals(entry.source)) result.add(entry);
        }
        return result;
    }

    /**
     * Returns only pressure-level-origin entries, filtered from the full list.
     */
    public List<AltitudeEntry> getPressureEntries() {
        List<AltitudeEntry> result = new ArrayList<>();
        if (altitudes == null) return result;
        for (AltitudeEntry entry : altitudes) {
            if (SOURCE_PRESSURE.equals(entry.source)) result.add(entry);
        }
        return result;
    }

    /**
     * @return true if this profile contains any pressure-level entries
     */
    public boolean hasPressureData() {
        if (altitudes == null) return false;
        for (AltitudeEntry entry : altitudes) {
            if (SOURCE_PRESSURE.equals(entry.source)) return true;
        }
        return false;
    }
}
