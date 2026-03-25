package com.atakmap.android.weather.domain.service;

import com.atakmap.android.weather.domain.model.WeatherModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MultiSourceConfidenceService — compares weather data from multiple sources
 * and computes an agreement/confidence score.
 *
 * <h3>Sprint 5 — S5.5</h3>
 *
 * <h4>How it works</h4>
 * <ol>
 *   <li>For each weather parameter, compute the spread (max − min) across sources.</li>
 *   <li>Normalize each spread against a "significant disagreement" threshold.</li>
 *   <li>Average the normalized scores → overall confidence (0.0 = total disagreement, 1.0 = perfect agreement).</li>
 * </ol>
 *
 * <h4>Disagreement thresholds</h4>
 * <ul>
 *   <li>Temperature: ±5°C = total disagreement</li>
 *   <li>Wind speed: ±5 m/s = total disagreement</li>
 *   <li>Humidity: ±30% = total disagreement</li>
 *   <li>Pressure: ±10 hPa = total disagreement</li>
 *   <li>Visibility: ±5000m = total disagreement</li>
 * </ul>
 *
 * All methods are pure functions with zero Android dependencies.
 */
public final class MultiSourceConfidenceService {

    private MultiSourceConfidenceService() { /* non-instantiable */ }

    // ── Disagreement thresholds ─────────────────────────────────────────────
    private static final double TEMP_DISAGREE_C    = 5.0;
    private static final double WIND_DISAGREE_MS   = 5.0;
    private static final double HUMIDITY_DISAGREE   = 30.0;
    private static final double PRESSURE_DISAGREE   = 10.0;
    private static final double VISIBILITY_DISAGREE = 5000.0;

    // ═══════════════════════════════════════════════════════════════════════════
    // Confidence result
    // ═══════════════════════════════════════════════════════════════════════════

    /** Result of a multi-source confidence evaluation. */
    public static class ConfidenceResult {
        /** Overall confidence 0.0–1.0 (1.0 = perfect agreement). */
        public final double overallConfidence;
        /** Per-parameter confidence scores. */
        public final double tempConfidence;
        public final double windConfidence;
        public final double humidityConfidence;
        public final double pressureConfidence;
        public final double visibilityConfidence;
        /** Parameters where sources significantly disagree. */
        public final List<String> disagreements;
        /** Number of sources compared. */
        public final int sourceCount;

        ConfidenceResult(double overall, double temp, double wind, double humidity,
                         double pressure, double visibility,
                         List<String> disagreements, int sourceCount) {
            this.overallConfidence    = overall;
            this.tempConfidence       = temp;
            this.windConfidence       = wind;
            this.humidityConfidence   = humidity;
            this.pressureConfidence   = pressure;
            this.visibilityConfidence = visibility;
            this.disagreements        = disagreements;
            this.sourceCount          = sourceCount;
        }

        /** Confidence label: "High", "Moderate", "Low", or "Very Low". */
        public String getLabel() {
            if (overallConfidence >= 0.85) return "High";
            if (overallConfidence >= 0.65) return "Moderate";
            if (overallConfidence >= 0.40) return "Low";
            return "Very Low";
        }

        /** Format as percentage, e.g. "87%". */
        public String getPercentage() {
            return String.format(Locale.US, "%.0f%%", overallConfidence * 100);
        }

        /**
         * Human-readable summary.
         * @return e.g. "Confidence: 87% (High) — 3 sources agree. Disagreement: wind speed"
         */
        public String format() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.US, "Confidence: %s (%s) — %d sources",
                    getPercentage(), getLabel(), sourceCount));
            if (!disagreements.isEmpty()) {
                sb.append("\nDisagreement: ").append(String.join(", ", disagreements));
            }
            return sb.toString();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Core evaluation
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Compare multiple weather observations for the same location and compute
     * a confidence score based on inter-source agreement.
     *
     * @param models list of WeatherModel from different sources (same lat/lon)
     * @return ConfidenceResult, or null if fewer than 2 sources provided
     */
    public static ConfidenceResult evaluate(List<WeatherModel> models) {
        if (models == null || models.size() < 2) return null;

        int n = models.size();
        List<String> disagreements = new ArrayList<>();

        // Extract parameter arrays
        double[] temps       = new double[n];
        double[] winds       = new double[n];
        double[] humidities  = new double[n];
        double[] pressures   = new double[n];
        double[] visibilities = new double[n];

        for (int i = 0; i < n; i++) {
            WeatherModel m = models.get(i);
            temps[i]       = (m.getTemperatureMin() + m.getTemperatureMax()) / 2.0;
            winds[i]       = m.getWindSpeed();
            humidities[i]  = m.getHumidity();
            pressures[i]   = m.getPressure();
            visibilities[i] = m.getVisibility();
        }

        // Compute per-parameter confidence
        double tempConf = paramConfidence(temps, TEMP_DISAGREE_C);
        double windConf = paramConfidence(winds, WIND_DISAGREE_MS);
        double humConf  = paramConfidence(humidities, HUMIDITY_DISAGREE);
        double presConf = paramConfidence(pressures, PRESSURE_DISAGREE);
        double visConf  = paramConfidence(visibilities, VISIBILITY_DISAGREE);

        // Flag disagreements
        if (tempConf < 0.5) disagreements.add("temperature");
        if (windConf < 0.5) disagreements.add("wind speed");
        if (humConf  < 0.5) disagreements.add("humidity");
        if (presConf < 0.5) disagreements.add("pressure");
        if (visConf  < 0.5) disagreements.add("visibility");

        // Weighted average (temperature and wind are more tactically important)
        double overall = (tempConf * 2.0 + windConf * 2.0 + humConf + presConf + visConf) / 7.0;

        return new ConfidenceResult(overall, tempConf, windConf, humConf,
                presConf, visConf, disagreements, n);
    }

    /**
     * Compare two specific WeatherModels and return per-parameter agreement.
     *
     * @param a first model
     * @param b second model
     * @return ConfidenceResult comparing the two sources
     */
    public static ConfidenceResult compare(WeatherModel a, WeatherModel b) {
        List<WeatherModel> pair = new ArrayList<>(2);
        pair.add(a);
        pair.add(b);
        return evaluate(pair);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Internal helpers
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Compute confidence for a single parameter across sources.
     *
     * @param values    parameter values from each source
     * @param maxSpread maximum spread that represents total disagreement
     * @return confidence 0.0–1.0
     */
    private static double paramConfidence(double[] values, double maxSpread) {
        if (values.length < 2) return 1.0;
        double min = values[0], max = values[0];
        for (double v : values) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double spread = max - min;
        double confidence = 1.0 - Math.min(spread / maxSpread, 1.0);
        return Math.max(confidence, 0.0);
    }
}
