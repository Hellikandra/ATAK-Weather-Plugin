package com.atakmap.android.weather.domain.service;

import com.atakmap.android.weather.util.WeatherUnitConverter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 25 (S25.1): Unit tests for WeatherUnitConverter.
 * Covers temperature, wind, pressure, distance conversions and cardinal direction.
 */
class WeatherUnitConverterTest {

    @BeforeEach
    void resetUnits() {
        // Reset to metric defaults before each test
        WeatherUnitConverter.setUnitSystem(
                com.atakmap.android.weather.util.UnitSystem.METRIC);
    }

    // ── Temperature ───────────────────────────────────────────────────────

    @Nested @DisplayName("Temperature conversions")
    class Temperature {
        @ParameterizedTest(name = "{0}°C → {1}°F")
        @CsvSource({"0, 32", "100, 212", "-40, -40", "37, 98.6"})
        void celsiusToFahrenheit(double c, double expectedF) {
            assertEquals(expectedF, WeatherUnitConverter.celsiusToFahrenheit(c), 0.1);
        }

        @ParameterizedTest(name = "{0}°F → {1}°C")
        @CsvSource({"32, 0", "212, 100", "-40, -40", "98.6, 37"})
        void fahrenheitToCelsius(double f, double expectedC) {
            assertEquals(expectedC, WeatherUnitConverter.fahrenheitToCelsius(f), 0.1);
        }

        @Test @DisplayName("0°C = 273.15 K")
        void celsiusToKelvin() {
            assertEquals(273.15, WeatherUnitConverter.celsiusToKelvin(0), 0.01);
        }

        @Test @DisplayName("Roundtrip C→F→C preserves value")
        void roundtripCF() {
            double original = 21.5;
            double roundtrip = WeatherUnitConverter.fahrenheitToCelsius(
                    WeatherUnitConverter.celsiusToFahrenheit(original));
            assertEquals(original, roundtrip, 0.01);
        }
    }

    // ── Wind ──────────────────────────────────────────────────────────────

    @Nested @DisplayName("Wind conversions")
    class Wind {
        @Test @DisplayName("1 knot = 0.514444 m/s")
        void knotsToMs() {
            assertEquals(0.514444, WeatherUnitConverter.knotsToMs(1.0), 0.001);
        }

        @Test @DisplayName("10 m/s = 36 km/h")
        void msToKmh() {
            assertEquals(36.0, WeatherUnitConverter.msToKmh(10.0), 0.1);
        }

        @Test @DisplayName("Roundtrip m/s→kt→m/s")
        void roundtripMsKt() {
            double original = 15.0;
            double roundtrip = WeatherUnitConverter.knotsToMs(
                    WeatherUnitConverter.msToKnots(original));
            assertEquals(original, roundtrip, 0.001);
        }
    }

    // ── Pressure ──────────────────────────────────────────────────────────

    @Nested @DisplayName("Pressure conversions")
    class Pressure {
        @Test @DisplayName("1013.25 hPa ≈ 29.92 inHg")
        void hpaToInhg() {
            assertEquals(29.92, WeatherUnitConverter.hpaToInhg(1013.25), 0.01);
        }

        @Test @DisplayName("Roundtrip hPa→inHg→hPa")
        void roundtrip() {
            double original = 1013.25;
            double roundtrip = WeatherUnitConverter.inhgToHpa(
                    WeatherUnitConverter.hpaToInhg(original));
            assertEquals(original, roundtrip, 0.1);
        }
    }

    // ── Distance ──────────────────────────────────────────────────────────

    @Nested @DisplayName("Distance conversions")
    class Distance {
        @Test @DisplayName("1 statute mile = 1609.34 m")
        void milesToMetres() {
            assertEquals(1609.34, WeatherUnitConverter.statuteMilesToMetres(1.0), 0.01);
        }

        @Test @DisplayName("1 NM = 1852 m")
        void nmToMetres() {
            assertEquals(1852.0, WeatherUnitConverter.nauticalMilesToMetres(1.0), 0.01);
        }

        @Test @DisplayName("1 m = 3.28084 ft")
        void metresToFeet() {
            assertEquals(3.28084, WeatherUnitConverter.metresToFeet(1.0), 0.001);
        }
    }

    // ── Cardinal direction ────────────────────────────────────────────────

    @Nested @DisplayName("Degrees to cardinal")
    class Cardinal {
        @ParameterizedTest(name = "{0}° → {1}")
        @CsvSource({
            "0, N", "22.5, NNE", "45, NE", "90, E",
            "180, S", "270, W", "315, NW", "359, N",
            "360, N", "-1, N", "11.25, N", "11.26, NNE"
        })
        void degreesToCardinal(double degrees, String expected) {
            assertEquals(expected, WeatherUnitConverter.degreesToCardinal(degrees));
        }
    }

    // ── Unit system ──────────────────────────────────────────────────────

    @Nested @DisplayName("Unit system management")
    class UnitSystem {
        @Test @DisplayName("Default is METRIC")
        void defaultMetric() {
            assertEquals("C", WeatherUnitConverter.getTempUnit());
            assertEquals("MS", WeatherUnitConverter.getWindUnit());
        }

        @Test @DisplayName("Switch to IMPERIAL updates all units")
        void switchToImperial() {
            WeatherUnitConverter.setUnitSystem(
                    com.atakmap.android.weather.util.UnitSystem.IMPERIAL);
            assertEquals("F", WeatherUnitConverter.getTempUnit());
            assertEquals("MPH", WeatherUnitConverter.getWindUnit());
            assertEquals("INHG", WeatherUnitConverter.getPressureUnit());
        }

        @Test @DisplayName("Individual override persists")
        void individualOverride() {
            WeatherUnitConverter.setTempUnit("F");
            assertEquals("F", WeatherUnitConverter.getTempUnit());
            // Wind should still be metric
            assertEquals("MS", WeatherUnitConverter.getWindUnit());
        }

        @Test @DisplayName("Null unit setter is safe")
        void nullSafe() {
            WeatherUnitConverter.setTempUnit(null);
            // Should not throw, should not change
            assertEquals("C", WeatherUnitConverter.getTempUnit());
        }
    }
}
