package com.atakmap.android.weather.domain.service;

import com.atakmap.android.weather.domain.model.WeatherAlert;
import com.atakmap.android.weather.domain.model.WeatherAlert.Category;
import com.atakmap.android.weather.domain.model.WeatherAlert.Severity;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.WindProfileModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * WeatherAlertService — evaluates weather data against configurable thresholds
 * and produces {@link WeatherAlert} objects.
 *
 * <h3>Sprint 5 — S5.4</h3>
 *
 * <h4>Alert categories and default thresholds</h4>
 * <table>
 *   <tr><th>Category</th><th>Advisory</th><th>Warning</th><th>Critical</th></tr>
 *   <tr><td>Wind (m/s)</td><td>&gt; 8</td><td>&gt; 15</td><td>&gt; 25</td></tr>
 *   <tr><td>Temp low (°C)</td><td>&lt; 2</td><td>&lt; 0</td><td>&lt; -10</td></tr>
 *   <tr><td>Temp high (°C)</td><td>&gt; 33</td><td>&gt; 38</td><td>&gt; 42</td></tr>
 *   <tr><td>Visibility (m)</td><td>&lt; 5000</td><td>&lt; 1000</td><td>&lt; 200</td></tr>
 *   <tr><td>Precip (mm)</td><td>&gt; 5</td><td>&gt; 20</td><td>&gt; 50</td></tr>
 *   <tr><td>Thunderstorm</td><td>—</td><td>WMO 95-96</td><td>WMO 97-99</td></tr>
 * </table>
 *
 * <h4>Design</h4>
 * <ul>
 *   <li>Stateless — all thresholds are passed via {@link AlertThresholds}.</li>
 *   <li>Pure Java, zero Android dependencies.</li>
 *   <li>Returns a list of alerts sorted by severity (CRITICAL first).</li>
 * </ul>
 */
public final class WeatherAlertService {

    private WeatherAlertService() { /* non-instantiable */ }

    // ═══════════════════════════════════════════════════════════════════════════
    // Configurable thresholds
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * User-configurable alert thresholds. Use {@link #defaults()} for standard values.
     */
    public static class AlertThresholds {
        // Wind (m/s)
        public double windAdvisory  = 8.0;
        public double windWarning   = 15.0;
        public double windCritical  = 25.0;

        // Temperature low (°C)
        public double tempLowAdvisory  = 2.0;
        public double tempLowWarning   = 0.0;
        public double tempLowCritical  = -10.0;

        // Temperature high (°C)
        public double tempHighAdvisory  = 33.0;
        public double tempHighWarning   = 38.0;
        public double tempHighCritical  = 42.0;

        // Visibility (m)
        public double visAdvisory  = 5000.0;
        public double visWarning   = 1000.0;
        public double visCritical  = 200.0;

        // Precipitation (mm)
        public double precipAdvisory  = 5.0;
        public double precipWarning   = 20.0;
        public double precipCritical  = 50.0;

        // Pressure rapid change (hPa delta over last reading)
        public double pressureWarningDelta  = 8.0;
        public double pressureCriticalDelta = 15.0;

        /** Returns a new instance with default thresholds. */
        public static AlertThresholds defaults() {
            return new AlertThresholds();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Core evaluation — WeatherModel
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Evaluate a {@link WeatherModel} against the given thresholds.
     *
     * @param weather    current weather observation
     * @param thresholds alert thresholds to check against
     * @return list of triggered alerts (empty if none), sorted CRITICAL-first
     */
    public static List<WeatherAlert> evaluate(WeatherModel weather,
                                               AlertThresholds thresholds) {
        if (weather == null || thresholds == null) return Collections.emptyList();
        List<WeatherAlert> alerts = new ArrayList<>();

        double lat = weather.getLatitude();
        double lon = weather.getLongitude();

        // ── Wind ────────────────────────────────────────────────────────────
        double wind = weather.getWindSpeed();
        if (wind > thresholds.windCritical) {
            alerts.add(windAlert(Severity.CRITICAL, wind, thresholds.windCritical, lat, lon));
        } else if (wind > thresholds.windWarning) {
            alerts.add(windAlert(Severity.WARNING, wind, thresholds.windWarning, lat, lon));
        } else if (wind > thresholds.windAdvisory) {
            alerts.add(windAlert(Severity.ADVISORY, wind, thresholds.windAdvisory, lat, lon));
        }

        // ── Temperature — low ───────────────────────────────────────────────
        double avgTemp = (weather.getTemperatureMin() + weather.getTemperatureMax()) / 2.0;
        if (avgTemp < thresholds.tempLowCritical) {
            alerts.add(tempLowAlert(Severity.CRITICAL, avgTemp, thresholds.tempLowCritical, lat, lon));
        } else if (avgTemp < thresholds.tempLowWarning) {
            alerts.add(tempLowAlert(Severity.WARNING, avgTemp, thresholds.tempLowWarning, lat, lon));
        } else if (avgTemp < thresholds.tempLowAdvisory) {
            alerts.add(tempLowAlert(Severity.ADVISORY, avgTemp, thresholds.tempLowAdvisory, lat, lon));
        }

        // ── Temperature — high ──────────────────────────────────────────────
        if (avgTemp > thresholds.tempHighCritical) {
            alerts.add(tempHighAlert(Severity.CRITICAL, avgTemp, thresholds.tempHighCritical, lat, lon));
        } else if (avgTemp > thresholds.tempHighWarning) {
            alerts.add(tempHighAlert(Severity.WARNING, avgTemp, thresholds.tempHighWarning, lat, lon));
        } else if (avgTemp > thresholds.tempHighAdvisory) {
            alerts.add(tempHighAlert(Severity.ADVISORY, avgTemp, thresholds.tempHighAdvisory, lat, lon));
        }

        // ── Visibility ──────────────────────────────────────────────────────
        double vis = weather.getVisibility();
        if (vis < thresholds.visCritical) {
            alerts.add(visAlert(Severity.CRITICAL, vis, thresholds.visCritical, lat, lon));
        } else if (vis < thresholds.visWarning) {
            alerts.add(visAlert(Severity.WARNING, vis, thresholds.visWarning, lat, lon));
        } else if (vis < thresholds.visAdvisory) {
            alerts.add(visAlert(Severity.ADVISORY, vis, thresholds.visAdvisory, lat, lon));
        }

        // ── Precipitation ───────────────────────────────────────────────────
        double precip = weather.getPrecipitationSum();
        if (precip > thresholds.precipCritical) {
            alerts.add(precipAlert(Severity.CRITICAL, precip, thresholds.precipCritical, lat, lon));
        } else if (precip > thresholds.precipWarning) {
            alerts.add(precipAlert(Severity.WARNING, precip, thresholds.precipWarning, lat, lon));
        } else if (precip > thresholds.precipAdvisory) {
            alerts.add(precipAlert(Severity.ADVISORY, precip, thresholds.precipAdvisory, lat, lon));
        }

        // ── Thunderstorm (WMO code) ────────────────────────────────────────
        int wmo = weather.getWeatherCode();
        if (wmo >= 97) {
            alerts.add(new WeatherAlert.Builder(Category.THUNDERSTORM)
                    .severity(Severity.CRITICAL)
                    .title("Severe Thunderstorm")
                    .detail("WMO code " + wmo + " — heavy thunderstorm with hail")
                    .observedValue(wmo).thresholdValue(97).unit("WMO")
                    .latitude(lat).longitude(lon).build());
        } else if (wmo >= 95) {
            alerts.add(new WeatherAlert.Builder(Category.THUNDERSTORM)
                    .severity(Severity.WARNING)
                    .title("Thunderstorm")
                    .detail("WMO code " + wmo + " — thunderstorm activity")
                    .observedValue(wmo).thresholdValue(95).unit("WMO")
                    .latitude(lat).longitude(lon).build());
        }

        // ── Flight category (non-VFR) ───────────────────────────────────────
        String fltCat = weather.computeFlightCategory();
        if ("LIFR".equals(fltCat)) {
            alerts.add(new WeatherAlert.Builder(Category.FLIGHT_CATEGORY)
                    .severity(Severity.CRITICAL)
                    .title("LIFR Conditions")
                    .detail("Low IFR — ceiling/vis below minimums")
                    .observedValue(vis).thresholdValue(1609.34).unit("m vis")
                    .latitude(lat).longitude(lon).build());
        } else if ("IFR".equals(fltCat)) {
            alerts.add(new WeatherAlert.Builder(Category.FLIGHT_CATEGORY)
                    .severity(Severity.WARNING)
                    .title("IFR Conditions")
                    .detail("Instrument Flight Rules — reduced ceiling/visibility")
                    .observedValue(vis).thresholdValue(4828.0).unit("m vis")
                    .latitude(lat).longitude(lon).build());
        }

        // Sort: CRITICAL first, then WARNING, then ADVISORY
        Collections.sort(alerts, (a, b) -> b.getSeverity().ordinal() - a.getSeverity().ordinal());
        return alerts;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Hourly forecast scan — find upcoming alerts
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Scan an hourly forecast for the first hour that triggers a warning or critical alert.
     *
     * @param hourly     hourly forecast entries
     * @param thresholds alert thresholds
     * @param maxHours   how many hours ahead to scan (e.g. 24)
     * @return index of the first alerting hour, or -1 if none
     */
    public static int findFirstAlertHour(List<HourlyEntryModel> hourly,
                                          AlertThresholds thresholds,
                                          int maxHours) {
        if (hourly == null || thresholds == null) return -1;
        int limit = Math.min(hourly.size(), maxHours);
        for (int i = 0; i < limit; i++) {
            HourlyEntryModel e = hourly.get(i);
            if (e.getWindSpeed() > thresholds.windWarning) return i;
            if (e.getTemperature() < thresholds.tempLowWarning) return i;
            if (e.getTemperature() > thresholds.tempHighWarning) return i;
            if (e.getVisibility() < thresholds.visWarning) return i;
            if (e.getWeatherCode() >= 95) return i;
        }
        return -1;
    }

    /**
     * Scan a wind profile for dangerous conditions.
     *
     * @param profile    wind profile model
     * @param thresholds alert thresholds
     * @return list of wind alerts from the profile
     */
    public static List<WeatherAlert> evaluateWindProfile(WindProfileModel profile,
                                                          AlertThresholds thresholds) {
        if (profile == null || thresholds == null) return Collections.emptyList();
        List<WeatherAlert> alerts = new ArrayList<>();

        // Check peak wind across altitudes
        double peak = profile.peakWindSpeed();
        if (peak > thresholds.windCritical) {
            WindProfileModel.AltitudeEntry worst = profile.getMaxGustAltitude();
            alerts.add(new WeatherAlert.Builder(Category.WIND)
                    .severity(Severity.CRITICAL)
                    .title("Extreme Wind Aloft")
                    .detail("Peak " + String.format("%.1f m/s at %dm", peak,
                            worst != null ? worst.altitudeMeters : 0))
                    .observedValue(peak).thresholdValue(thresholds.windCritical).unit("m/s")
                    .build());
        } else if (peak > thresholds.windWarning) {
            alerts.add(new WeatherAlert.Builder(Category.WIND)
                    .severity(Severity.WARNING)
                    .title("Strong Wind Aloft")
                    .detail("Peak " + String.format("%.1f m/s", peak))
                    .observedValue(peak).thresholdValue(thresholds.windWarning).unit("m/s")
                    .build());
        }

        // Check vertical shear
        double shear = profile.verticalShear();
        if (shear > 10.0) {
            alerts.add(new WeatherAlert.Builder(Category.WIND)
                    .severity(Severity.WARNING)
                    .title("Wind Shear")
                    .detail(String.format("Vertical shear %.1f m/s — hazardous for aviation", shear))
                    .observedValue(shear).thresholdValue(10.0).unit("m/s delta")
                    .build());
        }

        return alerts;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Alert history (in-memory ring buffer)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Simple in-memory alert history with a fixed capacity.
     * Thread-safe for single-writer / multi-reader pattern.
     */
    public static class AlertHistory {
        private final List<WeatherAlert> history;
        private final int capacity;

        public AlertHistory(int capacity) {
            this.capacity = capacity;
            this.history  = new ArrayList<>(capacity);
        }

        /** Add alerts; trims oldest if over capacity. */
        public synchronized void addAll(List<WeatherAlert> alerts) {
            history.addAll(alerts);
            while (history.size() > capacity) {
                history.remove(0);
            }
        }

        /** Returns an unmodifiable snapshot of the history (newest last). */
        public synchronized List<WeatherAlert> getAll() {
            return Collections.unmodifiableList(new ArrayList<>(history));
        }

        /** Number of alerts in history. */
        public synchronized int size() { return history.size(); }

        /** Clear all history. */
        public synchronized void clear() { history.clear(); }

        /** Returns alerts filtered by minimum severity. */
        public synchronized List<WeatherAlert> getBySeverity(Severity minSeverity) {
            List<WeatherAlert> result = new ArrayList<>();
            for (WeatherAlert a : history) {
                if (a.getSeverity().ordinal() >= minSeverity.ordinal()) {
                    result.add(a);
                }
            }
            return result;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Private factory methods
    // ═══════════════════════════════════════════════════════════════════════════

    private static WeatherAlert windAlert(Severity s, double val, double thresh, double lat, double lon) {
        return new WeatherAlert.Builder(Category.WIND)
                .severity(s).title(s == Severity.CRITICAL ? "Extreme Wind" : "High Wind")
                .detail(String.format("Wind %.1f m/s exceeds %.1f m/s", val, thresh))
                .observedValue(val).thresholdValue(thresh).unit("m/s")
                .latitude(lat).longitude(lon).build();
    }

    private static WeatherAlert tempLowAlert(Severity s, double val, double thresh, double lat, double lon) {
        return new WeatherAlert.Builder(Category.TEMPERATURE)
                .severity(s).title(s == Severity.CRITICAL ? "Extreme Cold" : "Low Temperature")
                .detail(String.format("Temp %.1f°C below %.1f°C", val, thresh))
                .observedValue(val).thresholdValue(thresh).unit("°C")
                .latitude(lat).longitude(lon).build();
    }

    private static WeatherAlert tempHighAlert(Severity s, double val, double thresh, double lat, double lon) {
        return new WeatherAlert.Builder(Category.TEMPERATURE)
                .severity(s).title(s == Severity.CRITICAL ? "Extreme Heat" : "High Temperature")
                .detail(String.format("Temp %.1f°C exceeds %.1f°C", val, thresh))
                .observedValue(val).thresholdValue(thresh).unit("°C")
                .latitude(lat).longitude(lon).build();
    }

    private static WeatherAlert visAlert(Severity s, double val, double thresh, double lat, double lon) {
        return new WeatherAlert.Builder(Category.VISIBILITY)
                .severity(s).title(s == Severity.CRITICAL ? "Near-Zero Visibility" : "Low Visibility")
                .detail(String.format("Visibility %.0fm below %.0fm", val, thresh))
                .observedValue(val).thresholdValue(thresh).unit("m")
                .latitude(lat).longitude(lon).build();
    }

    private static WeatherAlert precipAlert(Severity s, double val, double thresh, double lat, double lon) {
        return new WeatherAlert.Builder(Category.PRECIPITATION)
                .severity(s).title(s == Severity.CRITICAL ? "Extreme Precipitation" : "Heavy Precipitation")
                .detail(String.format("Precip %.1fmm exceeds %.1fmm", val, thresh))
                .observedValue(val).thresholdValue(thresh).unit("mm")
                .latitude(lat).longitude(lon).build();
    }
}
