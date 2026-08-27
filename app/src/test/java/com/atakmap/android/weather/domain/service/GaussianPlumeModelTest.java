package com.atakmap.android.weather.domain.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 25 (S25.1): Unit tests for GaussianPlumeModel.
 * Tests dispersion coefficients, straight plume, and curved plume calculations.
 */
class GaussianPlumeModelTest {

    private static final double RELEASE_LAT = 50.6;
    private static final double RELEASE_LON = 5.5;

    // ── Dispersion coefficients ─────────────────────────────────────────

    @Nested @DisplayName("Pasquill-Gifford dispersion coefficients")
    class DispersionCoefficients {

        @Test @DisplayName("sigmaY increases with distance")
        void sigmaYIncreasesWithDistance() {
            double sy100 = GaussianPlumeModel.sigmaY(100, 'D');
            double sy1000 = GaussianPlumeModel.sigmaY(1000, 'D');
            double sy5000 = GaussianPlumeModel.sigmaY(5000, 'D');
            assertTrue(sy100 < sy1000, "sigmaY should increase with distance");
            assertTrue(sy1000 < sy5000, "sigmaY should increase with distance");
        }

        @Test @DisplayName("sigmaY is wider for unstable atmosphere (A) than stable (F)")
        void sigmaYWiderForUnstable() {
            double syA = GaussianPlumeModel.sigmaY(1000, 'A');
            double syD = GaussianPlumeModel.sigmaY(1000, 'D');
            double syF = GaussianPlumeModel.sigmaY(1000, 'F');
            assertTrue(syA > syD, "Unstable (A) should disperse wider than neutral (D)");
            assertTrue(syD > syF, "Neutral (D) should disperse wider than stable (F)");
        }

        @Test @DisplayName("sigmaZ clamped to max 5000m")
        void sigmaZClamped() {
            double sz = GaussianPlumeModel.sigmaZ(1_000_000, 'A');
            assertTrue(sz <= 5000, "sigmaZ should be clamped to 5000m max");
        }

        @Test @DisplayName("Unknown stability class defaults to D")
        void unknownStabilityDefaultsToD() {
            double syD = GaussianPlumeModel.sigmaY(1000, 'D');
            double syX = GaussianPlumeModel.sigmaY(1000, 'X');
            assertEquals(syD, syX, 0.001, "Unknown class should default to D");
        }
    }

    // ── Straight plume ──────────────────────────────────────────────────

    @Nested @DisplayName("Straight plume calculation")
    class StraightPlume {

        @Test @DisplayName("Non-null result with valid inputs")
        void validInputsReturnResult() {
            GaussianPlumeModel.PlumeResult result = GaussianPlumeModel.calculatePlume(
                    RELEASE_LAT, RELEASE_LON,
                    5.0,    // wind speed m/s
                    270.0,  // wind from west
                    'D',    // neutral stability
                    1.0,    // source strength (arbitrary)
                    5.0     // max 5 km downwind
            );
            assertNotNull(result, "PlumeResult should not be null");
            assertFalse(result.centerline.isEmpty(), "Centerline should have points");
        }

        @Test @DisplayName("Plume respects maxDownwindKm")
        void plumeRespectsMaxDistance() {
            GaussianPlumeModel.PlumeResult result = GaussianPlumeModel.calculatePlume(
                    RELEASE_LAT, RELEASE_LON, 10.0, 0.0, 'D', 1.0, 2.0);

            // Centerline should not extend beyond ~2km from release point
            for (double[] pt : result.centerline) {
                double distKm = haversineKm(RELEASE_LAT, RELEASE_LON, pt[0], pt[1]);
                assertTrue(distKm <= 2.5,
                        "Centerline point at " + distKm + " km exceeds 2.0 km max");
            }
        }

        @Test @DisplayName("Low wind speed clamped to 0.5 m/s (no division by zero)")
        void lowWindClamped() {
            // Should not throw
            GaussianPlumeModel.PlumeResult result = GaussianPlumeModel.calculatePlume(
                    RELEASE_LAT, RELEASE_LON, 0.0, 180.0, 'D', 1.0, 1.0);
            assertNotNull(result);
        }
    }

    // ── Curved plume ────────────────────────────────────────────────────

    @Nested @DisplayName("Curved plume calculation")
    class CurvedPlume {

        @Test @DisplayName("Curved plume with varying wind direction")
        void curvedWithVaryingWind() {
            double[] speeds = {5.0, 5.0, 5.0, 5.0};
            double[] dirs   = {0.0, 90.0, 180.0, 270.0}; // full rotation

            // maxDownwindKm must exceed the distance the plume actually travels,
            // or the model clamps and returns before the wind ever rotates. At
            // 5 m/s over 4 hours that is 72 km — the old fixture passed 10.0,
            // which clamped after 33 minutes, so this test had never once
            // exercised a curve despite its name. Finding F18.
            GaussianPlumeModel.PlumeResult result = GaussianPlumeModel.calculateCurvedPlume(
                    RELEASE_LAT, RELEASE_LON, speeds, dirs, 'D', 4, 100.0);

            assertNotNull(result);
            assertTrue(result.centerline.size() > 4,
                    "Curved plume should have multiple centerline points per hour");

            // The point of the test: the path must actually bend. A straight
            // plume would hold one bearing throughout.
            assertTrue(distinctBearings(result.centerline) > 1,
                    "Centerline should change bearing as the wind rotates, but every "
                            + "leg had the same bearing — the plume is straight");
        }

        @Test @DisplayName("Null wind arrays return empty result")
        void nullWindArrays() {
            GaussianPlumeModel.PlumeResult result = GaussianPlumeModel.calculateCurvedPlume(
                    RELEASE_LAT, RELEASE_LON, null, null, 'D', 4, 5.0);
            assertNotNull(result);
            assertTrue(result.centerline.isEmpty());
        }

        @Test @DisplayName("Curved plume distance bounded by maxDownwindKm")
        void curvedBounded() {
            double[] speeds = {15.0, 15.0, 15.0, 15.0, 15.0};
            double[] dirs   = {0.0, 0.0, 0.0, 0.0, 0.0}; // constant north wind

            GaussianPlumeModel.PlumeResult result = GaussianPlumeModel.calculateCurvedPlume(
                    RELEASE_LAT, RELEASE_LON, speeds, dirs, 'D', 5, 3.0);

            for (double[] pt : result.centerline) {
                double distKm = haversineKm(RELEASE_LAT, RELEASE_LON, pt[0], pt[1]);
                assertTrue(distKm <= 3.5,
                        "Curved plume at " + distKm + " km exceeds 3.0 km max");
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * Number of distinct leg bearings along a centerline, rounded to the nearest
     * degree. A straight plume yields 1; a plume that turns yields more.
     */
    private static int distinctBearings(java.util.List<double[]> centerline) {
        java.util.Set<Long> bearings = new java.util.HashSet<>();
        for (int i = 1; i < centerline.size(); i++) {
            double[] a = centerline.get(i - 1), b = centerline.get(i);
            double dLon = Math.toRadians(b[1] - a[1]);
            double lat1 = Math.toRadians(a[0]), lat2 = Math.toRadians(b[0]);
            double y = Math.sin(dLon) * Math.cos(lat2);
            double x = Math.cos(lat1) * Math.sin(lat2)
                    - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
            bearings.add(Math.round((Math.toDegrees(Math.atan2(y, x)) + 360) % 360));
        }
        return bearings.size();
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
