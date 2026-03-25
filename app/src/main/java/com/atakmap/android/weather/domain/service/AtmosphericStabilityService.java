package com.atakmap.android.weather.domain.service;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * AtmosphericStabilityService -- Pasquill-Gifford atmospheric stability
 * classification for CBRN dispersion modelling.
 *
 * <h3>Stability classes (Pasquill-Gifford)</h3>
 * <table>
 *   <tr><th>Class</th><th>Description</th><th>Conditions</th></tr>
 *   <tr><td>A</td><td>Very Unstable</td><td>Strong insolation, light wind</td></tr>
 *   <tr><td>B</td><td>Moderately Unstable</td><td>Moderate insolation</td></tr>
 *   <tr><td>C</td><td>Slightly Unstable</td><td>Slight insolation</td></tr>
 *   <tr><td>D</td><td>Neutral</td><td>Overcast, moderate wind</td></tr>
 *   <tr><td>E</td><td>Slightly Stable</td><td>Night, thin overcast</td></tr>
 *   <tr><td>F</td><td>Very Stable</td><td>Night, clear/partly cloudy</td></tr>
 * </table>
 *
 * <h3>Note</h3>
 * This is an <b>educational/estimate</b> implementation. Not a replacement for
 * ATP-45/HPAC or operational CBRN tools.
 *
 * Sprint 14 -- S14.3
 */
public final class AtmosphericStabilityService {

    private AtmosphericStabilityService() { /* non-instantiable */ }

    /**
     * Calculate Pasquill-Gifford atmospheric stability class.
     *
     * @param windSpeed10m       surface wind speed in m/s (10m height)
     * @param cloudCoverPercent  cloud cover percentage (0--100)
     * @param solarElevationDeg  solar elevation angle in degrees (negative = night)
     * @return stability class character A through F
     */
    public static char calculateStabilityClass(double windSpeed10m,
                                                double cloudCoverPercent,
                                                double solarElevationDeg) {
        // Determine insolation category
        int insolation = classifyInsolation(solarElevationDeg, cloudCoverPercent);

        // Pasquill-Gifford lookup table
        // insolation: 0=strong, 1=moderate, 2=slight, 3=cloudy, 4=night-thin, 5=night-thick
        //
        // Wind speed ranges and corresponding classes:
        if (windSpeed10m < 2.0) {
            switch (insolation) {
                case 0: return 'A';
                case 1: return 'A'; // A-B, use A for conservative estimate
                case 2: return 'B';
                case 3: return 'D';
                case 4: return 'F';
                case 5: return 'F';
                default: return 'D';
            }
        } else if (windSpeed10m < 3.0) {
            switch (insolation) {
                case 0: return 'A'; // A-B
                case 1: return 'B';
                case 2: return 'C';
                case 3: return 'D';
                case 4: return 'E';
                case 5: return 'F';
                default: return 'D';
            }
        } else if (windSpeed10m < 5.0) {
            switch (insolation) {
                case 0: return 'B';
                case 1: return 'B'; // B-C
                case 2: return 'C';
                case 3: return 'D';
                case 4: return 'D';
                case 5: return 'E';
                default: return 'D';
            }
        } else if (windSpeed10m < 6.0) {
            switch (insolation) {
                case 0: return 'C';
                case 1: return 'C'; // C-D
                case 2: return 'D';
                case 3: return 'D';
                case 4: return 'D';
                case 5: return 'D';
                default: return 'D';
            }
        } else {
            // > 6 m/s
            switch (insolation) {
                case 0: return 'C';
                case 1: return 'D';
                case 2: return 'D';
                case 3: return 'D';
                case 4: return 'D';
                case 5: return 'D';
                default: return 'D';
            }
        }
    }

    /**
     * Classify insolation category based on solar elevation and cloud cover.
     *
     * @return 0=strong, 1=moderate, 2=slight, 3=cloudy (day), 4=night thin cloud, 5=night thick cloud
     */
    private static int classifyInsolation(double solarElevDeg, double cloudPct) {
        boolean isNight = solarElevDeg < 0;

        if (isNight) {
            // Night: distinguish thin vs thick cloud cover
            return cloudPct >= 50 ? 5 : 4;
        }

        // Day: cloudy overrides solar elevation
        if (cloudPct >= 80) {
            return 3; // overcast / cloudy
        }

        // Day with varying insolation based on solar elevation
        if (solarElevDeg >= 60) {
            return cloudPct < 30 ? 0 : 1;  // strong or moderate
        } else if (solarElevDeg >= 35) {
            if (cloudPct < 30) return 0;    // strong
            if (cloudPct < 60) return 1;    // moderate
            return 2;                        // slight
        } else if (solarElevDeg >= 15) {
            if (cloudPct < 30) return 1;    // moderate
            return 2;                        // slight
        } else {
            return 2;                        // slight (low sun)
        }
    }

    /**
     * Get human-readable description for a stability class.
     *
     * @param stabilityClass character A through F
     * @return description string
     */
    public static String getStabilityDescription(char stabilityClass) {
        switch (stabilityClass) {
            case 'A': return "Very Unstable";
            case 'B': return "Moderately Unstable";
            case 'C': return "Slightly Unstable";
            case 'D': return "Neutral";
            case 'E': return "Slightly Stable";
            case 'F': return "Very Stable";
            default:  return "Unknown";
        }
    }

    /**
     * Calculate solar elevation angle for a given location and time.
     *
     * <p>Uses a simplified solar position algorithm based on day of year
     * and hour angle. Accuracy is approximately +/-1 degree, sufficient
     * for Pasquill-Gifford classification.</p>
     *
     * @param lat    latitude in degrees
     * @param lon    longitude in degrees
     * @param timeMs time in milliseconds since epoch (UTC)
     * @return solar elevation angle in degrees (negative = below horizon)
     */
    public static double calculateSolarElevation(double lat, double lon, long timeMs) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(timeMs);

        int dayOfYear = cal.get(Calendar.DAY_OF_YEAR);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);

        // Solar declination (Spencer, 1971 approximation)
        double gamma = 2.0 * Math.PI * (dayOfYear - 1) / 365.0;
        double declination = 0.006918
                - 0.399912 * Math.cos(gamma)
                + 0.070257 * Math.sin(gamma)
                - 0.006758 * Math.cos(2 * gamma)
                + 0.000907 * Math.sin(2 * gamma)
                - 0.002697 * Math.cos(3 * gamma)
                + 0.001480 * Math.sin(3 * gamma);

        // Equation of time (minutes)
        double eot = 229.18 * (0.000075
                + 0.001868 * Math.cos(gamma)
                - 0.032077 * Math.sin(gamma)
                - 0.014615 * Math.cos(2 * gamma)
                - 0.04089 * Math.sin(2 * gamma));

        // True solar time (minutes)
        double timeOffset = eot + 4.0 * lon; // 4 minutes per degree of longitude
        double trueSolarTime = hour * 60 + minute + timeOffset;

        // Hour angle (degrees)
        double hourAngle = Math.toRadians((trueSolarTime / 4.0) - 180.0);

        // Solar elevation
        double latRad = Math.toRadians(lat);
        double sinElevation = Math.sin(latRad) * Math.sin(declination)
                + Math.cos(latRad) * Math.cos(declination) * Math.cos(hourAngle);

        return Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, sinElevation))));
    }
}
