package com.atakmap.android.weather.domain.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Holds two WeatherModel instances for side-by-side comparison:
 * the user's own position (self marker) vs. the current map centre.
 *
 * Delta values are computed lazily for each numeric field.
 *
 * <h3>Sprint 4 — S4.2 additions</h3>
 * <ul>
 *   <li>{@link #getSignificantDeltas()} — highlight notable differences</li>
 *   <li>{@link #isConditionBetter()} — whether map center has better conditions</li>
 * </ul>
 */
public class ComparisonModel {

    public final WeatherModel selfMarker;
    public final WeatherModel mapCenter;

    public ComparisonModel(WeatherModel selfMarker, WeatherModel mapCenter) {
        this.selfMarker = selfMarker;
        this.mapCenter  = mapCenter;
    }

    // ── Delta helpers (mapCenter − selfMarker) ────────────────────────────

    public double deltaTemperatureMax()       { return mapCenter.getTemperatureMax()      - selfMarker.getTemperatureMax(); }
    public double deltaTemperatureMin()       { return mapCenter.getTemperatureMin()      - selfMarker.getTemperatureMin(); }
    public double deltaApparentTemperature()  { return mapCenter.getApparentTemperature() - selfMarker.getApparentTemperature(); }
    public double deltaHumidity()             { return mapCenter.getHumidity()            - selfMarker.getHumidity(); }
    public double deltaPressure()             { return mapCenter.getPressure()            - selfMarker.getPressure(); }
    public double deltaVisibility()           { return mapCenter.getVisibility()          - selfMarker.getVisibility(); }
    public double deltaWindSpeed()            { return mapCenter.getWindSpeed()           - selfMarker.getWindSpeed(); }
    public double deltaPrecipitationSum()     { return mapCenter.getPrecipitationSum()    - selfMarker.getPrecipitationSum(); }

    /** Format a delta with a leading +/− sign. */
    public static String formatDelta(double delta, String unit) {
        return String.format("%+.1f %s", delta, unit);
    }

    // ── Behavioral methods (Sprint 4 — S4.2) ──────────────────────────────────

    /** A notable delta entry with field name, formatted value, and severity. */
    public static class SignificantDelta {
        public final String field;
        public final String formattedDelta;
        /** "info", "warning", or "critical" */
        public final String severity;

        public SignificantDelta(String field, String formattedDelta, String severity) {
            this.field          = field;
            this.formattedDelta = formattedDelta;
            this.severity       = severity;
        }

        @Override
        public String toString() {
            return field + ": " + formattedDelta + " [" + severity + "]";
        }
    }

    /**
     * Returns a list of significant deltas between the two locations.
     *
     * <p>Thresholds:
     * <ul>
     *   <li>Temperature: &ge; 3°C = info, &ge; 8°C = warning, &ge; 15°C = critical</li>
     *   <li>Wind speed: &ge; 3 m/s = info, &ge; 7 m/s = warning, &ge; 12 m/s = critical</li>
     *   <li>Visibility: &ge; 2000m = info, &ge; 5000m = warning, &ge; 8000m = critical</li>
     *   <li>Pressure: &ge; 5 hPa = info, &ge; 15 hPa = warning, &ge; 30 hPa = critical</li>
     *   <li>Humidity: &ge; 20% = info, &ge; 40% = warning</li>
     * </ul>
     *
     * @return list of notable deltas, sorted by severity (critical first)
     */
    public List<SignificantDelta> getSignificantDeltas() {
        List<SignificantDelta> result = new ArrayList<>();

        // Temperature
        double dTemp = Math.abs(deltaTemperatureMax());
        if (dTemp >= 15.0) {
            result.add(new SignificantDelta("Temp", formatDelta(deltaTemperatureMax(), "°C"), "critical"));
        } else if (dTemp >= 8.0) {
            result.add(new SignificantDelta("Temp", formatDelta(deltaTemperatureMax(), "°C"), "warning"));
        } else if (dTemp >= 3.0) {
            result.add(new SignificantDelta("Temp", formatDelta(deltaTemperatureMax(), "°C"), "info"));
        }

        // Wind speed
        double dWind = Math.abs(deltaWindSpeed());
        if (dWind >= 12.0) {
            result.add(new SignificantDelta("Wind", formatDelta(deltaWindSpeed(), "m/s"), "critical"));
        } else if (dWind >= 7.0) {
            result.add(new SignificantDelta("Wind", formatDelta(deltaWindSpeed(), "m/s"), "warning"));
        } else if (dWind >= 3.0) {
            result.add(new SignificantDelta("Wind", formatDelta(deltaWindSpeed(), "m/s"), "info"));
        }

        // Visibility
        double dVis = Math.abs(deltaVisibility());
        if (dVis >= 8000.0) {
            result.add(new SignificantDelta("Visibility", formatDelta(deltaVisibility() / 1000.0, "km"), "critical"));
        } else if (dVis >= 5000.0) {
            result.add(new SignificantDelta("Visibility", formatDelta(deltaVisibility() / 1000.0, "km"), "warning"));
        } else if (dVis >= 2000.0) {
            result.add(new SignificantDelta("Visibility", formatDelta(deltaVisibility() / 1000.0, "km"), "info"));
        }

        // Pressure
        double dPres = Math.abs(deltaPressure());
        if (dPres >= 30.0) {
            result.add(new SignificantDelta("Pressure", formatDelta(deltaPressure(), "hPa"), "critical"));
        } else if (dPres >= 15.0) {
            result.add(new SignificantDelta("Pressure", formatDelta(deltaPressure(), "hPa"), "warning"));
        } else if (dPres >= 5.0) {
            result.add(new SignificantDelta("Pressure", formatDelta(deltaPressure(), "hPa"), "info"));
        }

        // Humidity
        double dHum = Math.abs(deltaHumidity());
        if (dHum >= 40.0) {
            result.add(new SignificantDelta("Humidity", formatDelta(deltaHumidity(), "%"), "warning"));
        } else if (dHum >= 20.0) {
            result.add(new SignificantDelta("Humidity", formatDelta(deltaHumidity(), "%"), "info"));
        }

        return result;
    }

    /**
     * Returns {@code true} if the map-center location has overall better conditions
     * than the self location (better visibility, lower wind, less precipitation).
     *
     * @return true if map center has tactically superior conditions
     */
    public boolean isConditionBetter() {
        int score = 0;
        if (deltaVisibility() > 0) score++;       // better vis at target
        if (deltaWindSpeed() < 0)  score++;        // less wind at target
        if (deltaPrecipitationSum() < 0) score++;  // less precip at target
        return score >= 2;
    }
}
