package com.atakmap.android.weather.domain.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GaussianPlumeModel -- Simplified Gaussian plume dispersion calculation
 * for point-source releases.
 *
 * <h3>Model</h3>
 * Uses the standard Gaussian plume equation:
 * <pre>
 *   C(x,y) = Q / (2*pi*sigma_y*sigma_z*u) * exp(-y^2/(2*sigma_y^2)) * exp(-z^2/(2*sigma_z^2))
 * </pre>
 *
 * Dispersion coefficients (sigma_y, sigma_z) use Pasquill-Gifford parameters
 * that vary with downwind distance and atmospheric stability class.
 *
 * <h3>Output</h3>
 * Returns a {@link PlumeResult} containing centerline and 2-sigma boundary
 * polygons that can be drawn on the ATAK map as hazard cones.
 *
 * <h3>Disclaimer</h3>
 * This is an <b>educational/estimate</b> implementation.
 * Not a replacement for ATP-45, HPAC, or operational CBRN tools.
 *
 * Sprint 14 -- S14.3
 */
public final class GaussianPlumeModel {

    private static final double R_EARTH = 6_371_000.0; // metres
    private static final String DISCLAIMER =
            "ESTIMATE ONLY \u2014 not a replacement for ATP-45/HPAC";

    private GaussianPlumeModel() { /* non-instantiable */ }

    /**
     * Calculate Gaussian plume dispersion for a point-source release.
     * Returns downwind hazard polygon vertices.
     *
     * @param releaseLat     source latitude
     * @param releaseLon     source longitude
     * @param windSpeed      wind speed at release height (m/s)
     * @param windDirection  wind direction in degrees (meteorological: direction FROM)
     * @param stabilityClass Pasquill-Gifford class (A-F)
     * @param sourceStrength estimated release rate (arbitrary units, affects concentration only)
     * @param maxDownwindKm  max downwind distance to calculate (km)
     * @return PlumeResult with polygon points and boundary contours
     */
    public static PlumeResult calculatePlume(double releaseLat, double releaseLon,
                                              double windSpeed, double windDirection,
                                              char stabilityClass, double sourceStrength,
                                              double maxDownwindKm) {
        PlumeResult result = new PlumeResult();
        result.stabilityClass = stabilityClass;
        result.maxDownwindKm = maxDownwindKm;
        result.disclaimer = DISCLAIMER;

        // Wind blows FROM windDirection, so plume travels in opposite direction
        // Downwind bearing = windDirection (the direction wind is going TO = windDir itself
        // in meteorological convention, wind FROM north means plume goes south)
        // Actually: met wind direction = direction wind comes FROM
        // So plume goes in the opposite direction: windDirection + 180
        double downwindBearing = (windDirection + 180.0) % 360.0;

        // Minimum wind speed to avoid division by zero
        double effectiveWind = Math.max(windSpeed, 0.5);

        // Calculate points along the plume centerline and boundaries
        int numSteps = 20;
        double stepM = (maxDownwindKm * 1000.0) / numSteps;

        double maxCrosswind = 0;

        for (int i = 0; i <= numSteps; i++) {
            double x = i * stepM;  // downwind distance in metres
            if (x < 1.0) x = 1.0; // avoid x=0

            // Dispersion coefficients
            double sy = sigmaY(x, stabilityClass);
            double sz = sigmaZ(x, stabilityClass);

            // 2-sigma boundary gives ~95% of mass
            double crosswindWidth = 2.0 * sy;
            if (crosswindWidth > maxCrosswind) maxCrosswind = crosswindWidth;

            // Centerline point
            double[] center = offsetBearing(releaseLat, releaseLon,
                    downwindBearing, x);
            result.centerline.add(center);

            // Left boundary (perpendicular left of downwind direction)
            double leftBearing = (downwindBearing - 90.0 + 360.0) % 360.0;
            double[] leftPt = offsetBearing(center[0], center[1],
                    leftBearing, crosswindWidth);
            result.leftBoundary.add(leftPt);

            // Right boundary (perpendicular right of downwind direction)
            double rightBearing = (downwindBearing + 90.0) % 360.0;
            double[] rightPt = offsetBearing(center[0], center[1],
                    rightBearing, crosswindWidth);
            result.rightBoundary.add(rightPt);
        }

        result.maxCrosswindKm = maxCrosswind / 1000.0;
        return result;
    }

    /**
     * Calculate a curved plume that follows changing wind direction over time,
     * clamped to the maximum downwind distance.
     *
     * <p>Instead of a straight cone, the plume path bends at each time step
     * according to the per-hour wind direction from the hourly forecast.
     * Dispersion widths still use Pasquill-Gifford sigma_y at cumulative
     * downwind distance. The path stops when cumulative distance reaches
     * {@code maxDownwindKm}.</p>
     *
     * @param releaseLat       release point latitude
     * @param releaseLon       release point longitude
     * @param hourlyWindSpeeds wind speed per hour (m/s)
     * @param hourlyWindDirs   wind direction per hour (degrees, meteorological)
     * @param stabilityClass   Pasquill stability class
     * @param maxHours         maximum hours to model
     * @param maxDownwindKm    maximum downwind distance in km (clamp)
     * @return PlumeResult with curved centerline and boundaries
     */
    public static PlumeResult calculateCurvedPlume(
            double releaseLat, double releaseLon,
            double[] hourlyWindSpeeds, double[] hourlyWindDirs,
            char stabilityClass, int maxHours, double maxDownwindKm) {

        PlumeResult result = new PlumeResult();
        result.stabilityClass = stabilityClass;
        result.maxDownwindKm = maxDownwindKm;
        result.disclaimer = DISCLAIMER;

        if (hourlyWindSpeeds == null || hourlyWindDirs == null
                || hourlyWindSpeeds.length == 0) {
            return result;
        }

        double maxDistM = maxDownwindKm * 1000.0;
        int hours = Math.min(maxHours, Math.min(hourlyWindSpeeds.length, hourlyWindDirs.length));
        double cumDistM = 0;
        double curLat = releaseLat;
        double curLon = releaseLon;

        // Add release point
        result.centerline.add(new double[]{curLat, curLon});
        result.leftBoundary.add(new double[]{curLat, curLon});
        result.rightBoundary.add(new double[]{curLat, curLon});

        // Sub-divide each hour into steps for smoother curves
        int stepsPerHour = 4;

        for (int h = 0; h < hours; h++) {
            double ws = Math.max(hourlyWindSpeeds[h], 0.5);
            double wd = hourlyWindDirs[h];
            double bearing = (wd + 180.0) % 360.0;

            // Distance per sub-step
            double hourDist = ws * 3600.0;
            double subStepM = hourDist / stepsPerHour;

            for (int s = 0; s < stepsPerHour; s++) {
                // Check if we'd exceed max range
                if (cumDistM + subStepM > maxDistM) {
                    // Partial step to exactly reach max distance
                    double remaining = maxDistM - cumDistM;
                    if (remaining > 1.0) {
                        cumDistM = maxDistM;
                        double[] next = offsetBearing(curLat, curLon, bearing, remaining);
                        curLat = next[0];
                        curLon = next[1];
                        result.centerline.add(next);

                        double sy = sigmaY(cumDistM, stabilityClass);
                        double cw = 2.0 * sy;
                        double leftB = (bearing - 90.0 + 360.0) % 360.0;
                        double rightB = (bearing + 90.0) % 360.0;
                        result.leftBoundary.add(offsetBearing(curLat, curLon, leftB, cw));
                        result.rightBoundary.add(offsetBearing(curLat, curLon, rightB, cw));
                    }
                    // Reached max — stop
                    result.maxDownwindKm = cumDistM / 1000.0;
                    double lastSy = sigmaY(cumDistM, stabilityClass);
                    result.maxCrosswindKm = 2.0 * lastSy / 1000.0;
                    return result;
                }

                cumDistM += subStepM;
                double[] next = offsetBearing(curLat, curLon, bearing, subStepM);
                curLat = next[0];
                curLon = next[1];
                result.centerline.add(next);

                double sy = sigmaY(cumDistM, stabilityClass);
                double cw = 2.0 * sy;
                double leftB = (bearing - 90.0 + 360.0) % 360.0;
                double rightB = (bearing + 90.0) % 360.0;
                result.leftBoundary.add(offsetBearing(curLat, curLon, leftB, cw));
                result.rightBoundary.add(offsetBearing(curLat, curLon, rightB, cw));
            }
        }

        result.maxDownwindKm = cumDistM / 1000.0;
        double lastSy = sigmaY(cumDistM, stabilityClass);
        result.maxCrosswindKm = 2.0 * lastSy / 1000.0;
        return result;
    }

    /** @deprecated Use {@link #calculateCurvedPlume(double, double, double[], double[], char, int, double)} */
    @Deprecated
    public static PlumeResult calculateCurvedPlume(
            double releaseLat, double releaseLon,
            double[] hourlyWindSpeeds, double[] hourlyWindDirs,
            char stabilityClass, int maxHours) {
        return calculateCurvedPlume(releaseLat, releaseLon, hourlyWindSpeeds, hourlyWindDirs,
                stabilityClass, maxHours, 100.0);
    }

    // ── Dispersion coefficients ───────────────────────────────────────────────

    /**
     * Pasquill-Gifford sigma_y (crosswind dispersion) coefficient.
     * sigma_y = a * x^b  where a,b depend on stability class.
     *
     * @param x         downwind distance in metres
     * @param stability stability class A-F
     * @return crosswind dispersion in metres
     */
    static double sigmaY(double x, char stability) {
        double a, b;
        switch (stability) {
            case 'A': a = 0.22;  b = 0.894; break;
            case 'B': a = 0.16;  b = 0.894; break;
            case 'C': a = 0.11;  b = 0.894; break;
            case 'D': a = 0.08;  b = 0.894; break;
            case 'E': a = 0.06;  b = 0.894; break;
            case 'F': a = 0.04;  b = 0.894; break;
            default:  a = 0.08;  b = 0.894; break; // Default to D
        }
        return a * Math.pow(x, b);
    }

    /**
     * Pasquill-Gifford sigma_z (vertical dispersion) coefficient.
     * Uses the Briggs parameterization.
     *
     * @param x         downwind distance in metres
     * @param stability stability class A-F
     * @return vertical dispersion in metres
     */
    static double sigmaZ(double x, char stability) {
        double a, b;
        switch (stability) {
            case 'A': a = 0.20;  b = 0.894; break;
            case 'B': a = 0.12;  b = 0.894; break;
            case 'C': a = 0.08;  b = 0.894; break;
            case 'D': a = 0.06;  b = 0.894; break;
            case 'E': a = 0.03;  b = 0.894; break;
            case 'F': a = 0.016; b = 0.894; break;
            default:  a = 0.06;  b = 0.894; break;
        }
        // Clamp sigma_z to prevent unrealistic values at large distances
        double sz = a * Math.pow(x, b);
        return Math.min(sz, 5000.0);
    }

    // ── Geometry helpers ──────────────────────────────────────────────────────

    /**
     * Flat-earth bearing offset. Returns [lat,lon] at the given bearing and distance.
     */
    private static double[] offsetBearing(double lat, double lon,
                                           double bearingDeg, double distM) {
        double rad = Math.toRadians(bearingDeg);
        double dLat = Math.toDegrees(distM / R_EARTH * Math.cos(rad));
        double dLon = Math.toDegrees(distM / R_EARTH * Math.sin(rad)
                / Math.cos(Math.toRadians(lat)));
        return new double[]{lat + dLat, lon + dLon};
    }

    // ── Result class ──────────────────────────────────────────────────────────

    /**
     * Result of a Gaussian plume calculation, containing polygon boundaries
     * for map display.
     */
    public static class PlumeResult {
        /** Points along the plume centerline [lat,lon]. */
        public final List<double[]> centerline = new ArrayList<>();

        /** 2-sigma left boundary points [lat,lon]. */
        public final List<double[]> leftBoundary = new ArrayList<>();

        /** 2-sigma right boundary points [lat,lon]. */
        public final List<double[]> rightBoundary = new ArrayList<>();

        /** Maximum downwind distance in km. */
        public double maxDownwindKm;

        /** Maximum crosswind spread in km (at furthest downwind point). */
        public double maxCrosswindKm;

        /** Pasquill-Gifford stability class used. */
        public char stabilityClass;

        /** Disclaimer text for display. */
        public String disclaimer;

        /**
         * Build a closed polygon combining left boundary, right boundary (reversed),
         * suitable for drawing as a single filled shape on the map.
         *
         * @return list of [lat,lon] points forming a closed polygon
         */
        public List<double[]> toPolygon() {
            List<double[]> polygon = new ArrayList<>();
            // Start with left boundary (downwind)
            polygon.addAll(leftBoundary);
            // Then right boundary in reverse (back upwind)
            for (int i = rightBoundary.size() - 1; i >= 0; i--) {
                polygon.add(rightBoundary.get(i));
            }
            return polygon;
        }

        /**
         * Build an inner contour polygon (1-sigma) for higher concentration zone.
         * Uses half the crosswind width of the outer (2-sigma) boundary.
         *
         * @return list of [lat,lon] points forming the inner polygon
         */
        public List<double[]> toInnerPolygon() {
            List<double[]> inner = new ArrayList<>();
            // Midpoints between centerline and each boundary = 1-sigma
            for (int i = 0; i < centerline.size(); i++) {
                double[] c = centerline.get(i);
                double[] l = leftBoundary.get(i);
                inner.add(new double[]{
                        (c[0] + l[0]) / 2.0,
                        (c[1] + l[1]) / 2.0
                });
            }
            for (int i = centerline.size() - 1; i >= 0; i--) {
                double[] c = centerline.get(i);
                double[] r = rightBoundary.get(i);
                inner.add(new double[]{
                        (c[0] + r[0]) / 2.0,
                        (c[1] + r[1]) / 2.0
                });
            }
            return inner;
        }
    }
}
