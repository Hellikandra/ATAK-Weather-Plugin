package com.atakmap.android.weather.domain.service;

import com.atakmap.android.weather.domain.model.WeatherAlert;
import com.atakmap.android.weather.domain.model.WeatherModel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 25 (S25.1): Unit tests for WeatherAlertService.
 * Tests threshold evaluation, severity sorting, and null safety.
 */
class WeatherAlertServiceTest {

    private final WeatherAlertService.AlertThresholds thresholds =
            WeatherAlertService.AlertThresholds.defaults();

    // ── Null safety ─────────────────────────────────────────────────────

    @Test @DisplayName("Null weather → empty list")
    void nullWeather() {
        List<WeatherAlert> alerts = WeatherAlertService.evaluate(null, thresholds);
        assertNotNull(alerts);
        assertTrue(alerts.isEmpty());
    }

    @Test @DisplayName("Null thresholds → empty list")
    void nullThresholds() {
        WeatherModel wx = new WeatherModel.Builder(50.0, 5.0)
                .windSpeed(5.0).build();
        List<WeatherAlert> alerts = WeatherAlertService.evaluate(wx, null);
        assertNotNull(alerts);
        assertTrue(alerts.isEmpty());
    }

    // ── Wind alerts ─────────────────────────────────────────────────────

    @Nested @DisplayName("Wind speed thresholds")
    class WindAlerts {

        @Test @DisplayName("Wind 10 m/s → ADVISORY (> 8.0)")
        void windAdvisory() {
            WeatherModel wx = new WeatherModel.Builder(50.0, 5.0)
                    .windSpeed(10.0).build();
            List<WeatherAlert> alerts = WeatherAlertService.evaluate(wx, thresholds);
            assertTrue(alerts.stream().anyMatch(a ->
                    a.getCategory().contains("WIND")
                            && a.getSeverity() == WeatherAlert.Severity.ADVISORY),
                    "Expected WIND ADVISORY for 10 m/s");
        }

        @Test @DisplayName("Wind 20 m/s → WARNING (> 15.0)")
        void windWarning() {
            WeatherModel wx = new WeatherModel.Builder(50.0, 5.0)
                    .windSpeed(20.0).build();
            List<WeatherAlert> alerts = WeatherAlertService.evaluate(wx, thresholds);
            assertTrue(alerts.stream().anyMatch(a ->
                    a.getCategory().contains("WIND")
                            && a.getSeverity() == WeatherAlert.Severity.WARNING),
                    "Expected WIND WARNING for 20 m/s");
        }

        @Test @DisplayName("Wind 30 m/s → CRITICAL (> 25.0)")
        void windCritical() {
            WeatherModel wx = new WeatherModel.Builder(50.0, 5.0)
                    .windSpeed(30.0).build();
            List<WeatherAlert> alerts = WeatherAlertService.evaluate(wx, thresholds);
            assertTrue(alerts.stream().anyMatch(a ->
                    a.getCategory().contains("WIND")
                            && a.getSeverity() == WeatherAlert.Severity.CRITICAL),
                    "Expected WIND CRITICAL for 30 m/s");
        }

        @Test @DisplayName("Wind 5 m/s → no alert")
        void noWindAlert() {
            WeatherModel wx = new WeatherModel.Builder(50.0, 5.0)
                    .windSpeed(5.0).build();
            List<WeatherAlert> alerts = WeatherAlertService.evaluate(wx, thresholds);
            assertTrue(alerts.stream().noneMatch(a -> a.getCategory().contains("WIND")),
                    "No wind alert expected for 5 m/s");
        }
    }

    // ── Temperature alerts ──────────────────────────────────────────────

    @Nested @DisplayName("Temperature thresholds")
    class TempAlerts {

        @Test @DisplayName("Extreme cold (-15°C) → CRITICAL")
        void extremeCold() {
            WeatherModel wx = new WeatherModel.Builder(50.0, 5.0)
                    .temperatureMin(-15.0).temperatureMax(-10.0).build();
            List<WeatherAlert> alerts = WeatherAlertService.evaluate(wx, thresholds);
            assertTrue(alerts.stream().anyMatch(a ->
                    a.getCategory().contains("TEMP")
                            && a.getSeverity() == WeatherAlert.Severity.CRITICAL),
                    "Expected TEMP CRITICAL for -12.5°C avg");
        }

        @Test @DisplayName("Extreme heat (45°C) → CRITICAL")
        void extremeHeat() {
            WeatherModel wx = new WeatherModel.Builder(50.0, 5.0)
                    .temperatureMin(43.0).temperatureMax(47.0).build();
            List<WeatherAlert> alerts = WeatherAlertService.evaluate(wx, thresholds);
            assertTrue(alerts.stream().anyMatch(a ->
                    a.getCategory().contains("TEMP")
                            && a.getSeverity() == WeatherAlert.Severity.CRITICAL),
                    "Expected TEMP CRITICAL for 45°C avg");
        }
    }

    // ── Sorting ─────────────────────────────────────────────────────────

    @Test @DisplayName("Alerts sorted CRITICAL first")
    void criticalFirst() {
        WeatherModel wx = new WeatherModel.Builder(50.0, 5.0)
                .windSpeed(30.0)          // CRITICAL wind
                .visibility(3000)         // ADVISORY visibility
                .temperatureMin(-15.0)    // CRITICAL cold
                .temperatureMax(-10.0)
                .build();
        List<WeatherAlert> alerts = WeatherAlertService.evaluate(wx, thresholds);
        assertFalse(alerts.isEmpty());
        // First alert should be CRITICAL
        assertEquals(WeatherAlert.Severity.CRITICAL, alerts.get(0).getSeverity());
    }

    // ── Thunderstorm ────────────────────────────────────────────────────

    @Test @DisplayName("WMO code 95 → thunderstorm WARNING")
    void thunderstormWarning() {
        WeatherModel wx = new WeatherModel.Builder(50.0, 5.0)
                .weatherCode(95).build();
        List<WeatherAlert> alerts = WeatherAlertService.evaluate(wx, thresholds);
        assertTrue(alerts.stream().anyMatch(a ->
                a.getCategory().contains("THUNDER")),
                "Expected THUNDERSTORM alert for WMO 95");
    }
}
