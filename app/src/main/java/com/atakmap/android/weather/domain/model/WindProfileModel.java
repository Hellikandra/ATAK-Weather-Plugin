package com.atakmap.android.weather.domain.model;

import java.util.List;

/**
 * Domain model for multi-altitude wind profile data.
 * Altitudes: 10m, 80m, 120m, 180m.
 */
public class WindProfileModel {

    public static class AltitudeEntry {
        public final int    altitudeMeters;
        public final double windSpeed;     // m/s
        public final double windDirection; // degrees
        public final double temperature;   // °C
        public final double windGusts;     // m/s (only at 10m)

        public AltitudeEntry(int altitudeMeters, double windSpeed,
                             double windDirection, double temperature,
                             double windGusts) {
            this.altitudeMeters = altitudeMeters;
            this.windSpeed      = windSpeed;
            this.windDirection  = windDirection;
            this.temperature    = temperature;
            this.windGusts      = windGusts;
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
}
