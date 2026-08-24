package com.atakmap.android.weather.domain.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 25 (S25.1): Unit tests for AtmosphericStabilityService.
 * Tests Pasquill-Gifford stability classification and solar elevation.
 */
class AtmosphericStabilityServiceTest {

    // ── Stability classification ────────────────────────────────────────

    @Nested @DisplayName("Stability classification")
    class Stability {

        @Test @DisplayName("Strong insolation + light wind → A (very unstable)")
        void strongSunLightWind() {
            char sc = AtmosphericStabilityService.calculateStabilityClass(
                    1.5,    // wind < 2 m/s
                    10.0,   // clear sky
                    70.0    // high sun
            );
            assertEquals('A', sc);
        }

        @Test @DisplayName("Overcast day + moderate wind → D (neutral)")
        void overcastModerateWind() {
            char sc = AtmosphericStabilityService.calculateStabilityClass(
                    5.5,    // wind 5-6 m/s
                    90.0,   // overcast
                    30.0    // moderate sun
            );
            assertEquals('D', sc);
        }

        @Test @DisplayName("Night + light wind + clear → F (very stable)")
        void clearNightLightWind() {
            char sc = AtmosphericStabilityService.calculateStabilityClass(
                    1.5,    // light wind
                    10.0,   // clear
                    -20.0   // night
            );
            assertEquals('F', sc);
        }

        @Test @DisplayName("Night + strong wind → D (neutral)")
        void nightStrongWind() {
            char sc = AtmosphericStabilityService.calculateStabilityClass(
                    8.0,    // strong wind
                    50.0,   // some clouds
                    -10.0   // night
            );
            assertEquals('D', sc);
        }
    }

    // ── Solar elevation ─────────────────────────────────────────────────

    @Nested @DisplayName("Solar elevation calculation")
    class SolarElevation {

        @Test @DisplayName("Noon in summer at equator → high elevation (>60°)")
        void equatorNoonSummer() {
            // June 21 12:00 UTC, equator (0°, 0°)
            // 2024-06-21 12:00 UTC = day 173 of year
            long timeMs = 1718971200000L; // approx 2024-06-21 12:00 UTC
            double elev = AtmosphericStabilityService.calculateSolarElevation(
                    0.0, 0.0, timeMs);
            assertTrue(elev > 50.0,
                    "Equator noon in summer should have high elevation, got " + elev);
        }

        @Test @DisplayName("Midnight → negative elevation")
        void midnightNegative() {
            // Midnight UTC at lon=0 should have negative solar elevation
            long timeMs = 1718928000000L; // approx 2024-06-21 00:00 UTC
            double elev = AtmosphericStabilityService.calculateSolarElevation(
                    50.0, 0.0, timeMs);
            assertTrue(elev < 0,
                    "Midnight should have negative elevation, got " + elev);
        }

        @Test @DisplayName("Elevation is bounded [-90, 90]")
        void elevationBounded() {
            double elev = AtmosphericStabilityService.calculateSolarElevation(
                    0.0, 0.0, System.currentTimeMillis());
            assertTrue(elev >= -90 && elev <= 90,
                    "Elevation should be in [-90, 90], got " + elev);
        }
    }

    // ── Stability description ───────────────────────────────────────────

    @ParameterizedTest(name = "Class {0} → \"{1}\"")
    @CsvSource({
        "A, Very Unstable",
        "B, Moderately Unstable",
        "C, Slightly Unstable",
        "D, Neutral",
        "E, Slightly Stable",
        "F, Very Stable",
        "X, Unknown"
    })
    @DisplayName("Stability class descriptions")
    void stabilityDescriptions(char cls, String expectedDesc) {
        assertEquals(expectedDesc,
                AtmosphericStabilityService.getStabilityDescription(cls));
    }
}
