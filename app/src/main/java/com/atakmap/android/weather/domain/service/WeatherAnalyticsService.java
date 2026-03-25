package com.atakmap.android.weather.domain.service;

import java.util.Locale;

/**
 * WeatherAnalyticsService — stateless utility for derived weather metrics.
 *
 * <h3>Sprint 4 — S4.1</h3>
 *
 * All methods are pure functions with zero Android dependencies.
 * Internal units follow the plugin convention:
 * <ul>
 *   <li>Temperature: °C</li>
 *   <li>Wind speed: m/s</li>
 *   <li>Pressure: hPa</li>
 *   <li>Visibility / altitude: metres</li>
 *   <li>Humidity: % (0–100)</li>
 * </ul>
 *
 * <h3>Formula references</h3>
 * <ul>
 *   <li><strong>Heat Index</strong> — NWS Rothfusz regression (NOAA Technical Attachment SR 90-23)</li>
 *   <li><strong>Wind Chill</strong> — NWS Wind Chill Temperature Index (2001 revision)</li>
 *   <li><strong>Dew Point</strong> — Magnus–Tetens approximation (Alduchov &amp; Eskridge, 1996)</li>
 *   <li><strong>Density Altitude</strong> — ICAO ISA model: DA = PA + 120·(OAT − ISA_Temp)</li>
 *   <li><strong>Beaufort Scale</strong> — WMO standard, wind speed in m/s</li>
 *   <li><strong>Crosswind/Headwind</strong> — simple trigonometric decomposition</li>
 * </ul>
 */
public final class WeatherAnalyticsService {

    private WeatherAnalyticsService() { /* non-instantiable */ }

    // ═══════════════════════════════════════════════════════════════════════════
    // Heat Index (NWS Rothfusz regression)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Compute the NWS Heat Index.
     *
     * <p>The Heat Index is only meaningful when temperature &ge; 27°C (80°F) and
     * relative humidity &ge; 40%. Below those thresholds the dry-bulb temperature
     * is returned unchanged.</p>
     *
     * @param tempC      air temperature in °C
     * @param humidityPct relative humidity 0–100
     * @return heat index in °C
     */
    public static double heatIndex(double tempC, double humidityPct) {
        // NWS formula operates in Fahrenheit
        double tF = tempC * 9.0 / 5.0 + 32.0;
        double rh = humidityPct;

        // Below 80 °F the simple Steadman formula is sufficient
        if (tF < 80.0) return tempC;

        // Rothfusz regression
        double hi = -42.379
                + 2.04901523  * tF
                + 10.14333127 * rh
                - 0.22475541  * tF * rh
                - 0.00683783  * tF * tF
                - 0.05481717  * rh * rh
                + 0.00122874  * tF * tF * rh
                + 0.00085282  * tF * rh * rh
                - 0.00000199  * tF * tF * rh * rh;

        // Low-humidity adjustment
        if (rh < 13.0 && tF >= 80.0 && tF <= 112.0) {
            hi -= ((13.0 - rh) / 4.0) * Math.sqrt((17.0 - Math.abs(tF - 95.0)) / 17.0);
        }
        // High-humidity adjustment
        else if (rh > 85.0 && tF >= 80.0 && tF <= 87.0) {
            hi += ((rh - 85.0) / 10.0) * ((87.0 - tF) / 5.0);
        }

        // Convert back to Celsius
        return (hi - 32.0) * 5.0 / 9.0;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Wind Chill (NWS 2001 revision)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Compute the NWS Wind Chill Temperature Index.
     *
     * <p>Valid when air temperature &le; 10°C (50°F) and wind speed &gt; 1.34 m/s
     * (3 mph). Outside those bounds, returns the air temperature unchanged.</p>
     *
     * @param tempC   air temperature in °C
     * @param windMs  wind speed in m/s
     * @return wind chill in °C
     */
    public static double windChill(double tempC, double windMs) {
        // NWS formula uses Fahrenheit and mph
        double tF = tempC * 9.0 / 5.0 + 32.0;
        double mph = windMs * 2.23694;

        if (tF > 50.0 || mph <= 3.0) return tempC;

        double wc = 35.74
                + 0.6215 * tF
                - 35.75  * Math.pow(mph, 0.16)
                + 0.4275 * tF * Math.pow(mph, 0.16);

        return (wc - 32.0) * 5.0 / 9.0;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Feels Like (composite)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Composite "feels like" temperature.
     *
     * <ul>
     *   <li>If temp &ge; 27°C and humidity &ge; 40% → heat index</li>
     *   <li>If temp &le; 10°C and wind &gt; 1.34 m/s → wind chill</li>
     *   <li>Otherwise → air temperature</li>
     * </ul>
     *
     * @param tempC      air temperature in °C
     * @param humidityPct relative humidity 0–100
     * @param windMs     wind speed in m/s
     * @return feels-like temperature in °C
     */
    public static double feelsLike(double tempC, double humidityPct, double windMs) {
        if (tempC >= 27.0 && humidityPct >= 40.0) {
            return heatIndex(tempC, humidityPct);
        }
        if (tempC <= 10.0 && windMs > 1.34) {
            return windChill(tempC, windMs);
        }
        return tempC;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Dew Point (Magnus–Tetens approximation)
    // ═══════════════════════════════════════════════════════════════════════════

    /** Magnus constant a. */
    private static final double MAGNUS_A = 17.625;
    /** Magnus constant b (°C). */
    private static final double MAGNUS_B = 243.04;

    /**
     * Compute dew point using the Magnus–Tetens formula.
     *
     * @param tempC      air temperature in °C
     * @param humidityPct relative humidity 0–100
     * @return dew point in °C
     */
    public static double dewPoint(double tempC, double humidityPct) {
        if (humidityPct <= 0) return tempC; // degenerate
        double rh = Math.min(humidityPct, 100.0);
        double gamma = Math.log(rh / 100.0) + (MAGNUS_A * tempC) / (MAGNUS_B + tempC);
        return (MAGNUS_B * gamma) / (MAGNUS_A - gamma);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Beaufort Scale
    // ═══════════════════════════════════════════════════════════════════════════

    /** Beaufort scale result: number (0–12) and description. */
    public static class BeaufortResult {
        public final int    force;
        public final String description;

        BeaufortResult(int force, String description) {
            this.force       = force;
            this.description = description;
        }

        @Override
        public String toString() {
            return "Bft " + force + " — " + description;
        }
    }

    /** Upper bound (m/s) for each Beaufort force 0–11. Force 12 has no upper bound. */
    private static final double[] BFT_UPPER = {
        0.5, 1.6, 3.4, 5.5, 8.0, 10.8, 13.9, 17.2, 20.8, 24.5, 28.5, 32.7
    };

    private static final String[] BFT_NAMES = {
        "Calm", "Light air", "Light breeze", "Gentle breeze",
        "Moderate breeze", "Fresh breeze", "Strong breeze",
        "Near gale", "Gale", "Strong gale", "Storm",
        "Violent storm", "Hurricane force"
    };

    /**
     * Convert wind speed in m/s to the Beaufort scale (0–12).
     *
     * @param windMs wind speed in m/s
     * @return BeaufortResult with force number and English description
     */
    public static BeaufortResult beaufort(double windMs) {
        double absWind = Math.abs(windMs);
        for (int i = 0; i < BFT_UPPER.length; i++) {
            if (absWind < BFT_UPPER[i]) {
                return new BeaufortResult(i, BFT_NAMES[i]);
            }
        }
        return new BeaufortResult(12, BFT_NAMES[12]);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Density Altitude (ICAO ISA model)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Compute density altitude using the ICAO simplified formula.
     *
     * <pre>
     *   ISA temp at altitude = 15 − 0.001981 × elevation_ft  (°C)
     *   Pressure altitude PA ≈ elevation_ft + (1013.25 − QNH) × 30
     *   Density altitude  DA = PA + 120 × (OAT − ISA_Temp)
     * </pre>
     *
     * @param tempC          outside air temperature in °C
     * @param pressureHpa    station pressure (QNH) in hPa
     * @param elevationMetres field elevation in metres
     * @return density altitude in feet (aviation convention)
     */
    public static double densityAltitude(double tempC, double pressureHpa, double elevationMetres) {
        double elevFt = elevationMetres * 3.28084;
        double pressureAlt = elevFt + (1013.25 - pressureHpa) * 30.0;
        double isaTemp = 15.0 - 0.001981 * elevFt;
        return pressureAlt + 120.0 * (tempC - isaTemp);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Crosswind / Headwind Component
    // ═══════════════════════════════════════════════════════════════════════════

    /** Result of wind component decomposition relative to a runway or track. */
    public static class WindComponents {
        /** Headwind component (positive = headwind, negative = tailwind) in m/s. */
        public final double headwind;
        /** Crosswind component (always positive magnitude) in m/s. */
        public final double crosswind;

        WindComponents(double headwind, double crosswind) {
            this.headwind  = headwind;
            this.crosswind = crosswind;
        }

        /**
         * Format as a human-readable string.
         * @return e.g. "HW 5.2 m/s, XW 3.1 m/s" or "TW 2.0 m/s, XW 1.5 m/s"
         */
        public String format() {
            String hwLabel = headwind >= 0 ? "HW" : "TW";
            return String.format(Locale.US, "%s %.1f m/s, XW %.1f m/s",
                    hwLabel, Math.abs(headwind), crosswind);
        }
    }

    /**
     * Decompose wind into headwind and crosswind components relative to a
     * runway heading or direction of travel.
     *
     * @param windDirDeg wind direction in degrees (where wind is coming FROM)
     * @param windSpeedMs wind speed in m/s
     * @param runwayHeadingDeg runway heading or travel direction in degrees
     * @return WindComponents with headwind (positive = into face) and crosswind (magnitude)
     */
    public static WindComponents windComponents(double windDirDeg, double windSpeedMs,
                                                 double runwayHeadingDeg) {
        double angleDiff = Math.toRadians(windDirDeg - runwayHeadingDeg);
        double hw = windSpeedMs * Math.cos(angleDiff);
        double xw = Math.abs(windSpeedMs * Math.sin(angleDiff));
        return new WindComponents(hw, xw);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Flight Category (VFR/MVFR/IFR/LIFR) from visibility + ceiling
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Determine FAA flight category from visibility and ceiling.
     *
     * <table>
     *   <tr><th>Category</th><th>Ceiling (ft AGL)</th><th>Visibility (SM)</th></tr>
     *   <tr><td>VFR</td><td>&gt; 3000</td><td>&gt; 5</td></tr>
     *   <tr><td>MVFR</td><td>1000–3000</td><td>3–5</td></tr>
     *   <tr><td>IFR</td><td>500–999</td><td>1–&lt;3</td></tr>
     *   <tr><td>LIFR</td><td>&lt; 500</td><td>&lt; 1</td></tr>
     * </table>
     *
     * <p>When ceiling is unknown, classification is based on visibility alone.
     * The most restrictive category between ceiling and visibility wins.</p>
     *
     * @param visibilityMetres visibility in metres
     * @param ceilingFeet      ceiling in feet AGL, or -1 if unknown/unlimited
     * @return "VFR", "MVFR", "IFR", or "LIFR"
     */
    public static String flightCategory(double visibilityMetres, double ceilingFeet) {
        // Convert visibility to statute miles for FAA thresholds
        double visSm = visibilityMetres / 1609.34;

        int visCat;
        if (visSm >= 5.0)      visCat = 0; // VFR
        else if (visSm >= 3.0) visCat = 1; // MVFR
        else if (visSm >= 1.0) visCat = 2; // IFR
        else                   visCat = 3; // LIFR

        int ceilCat = 0; // default VFR (unlimited ceiling)
        if (ceilingFeet >= 0) {
            if (ceilingFeet > 3000)       ceilCat = 0; // VFR
            else if (ceilingFeet >= 1000) ceilCat = 1; // MVFR
            else if (ceilingFeet >= 500)  ceilCat = 2; // IFR
            else                          ceilCat = 3; // LIFR
        }

        int worst = Math.max(visCat, ceilCat);
        switch (worst) {
            case 0:  return "VFR";
            case 1:  return "MVFR";
            case 2:  return "IFR";
            default: return "LIFR";
        }
    }

    /**
     * Convenience: classify using only visibility (ceiling unknown).
     *
     * @param visibilityMetres visibility in metres
     * @return "VFR", "MVFR", "IFR", or "LIFR"
     */
    public static String flightCategoryFromVisibility(double visibilityMetres) {
        return flightCategory(visibilityMetres, -1);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Formatting helpers
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Format a derived conditions summary string suitable for display.
     *
     * @param tempC      air temperature in °C
     * @param humidityPct relative humidity 0–100
     * @param windMs     wind speed in m/s
     * @return multi-line summary, e.g.:
     *         "Feels like: 28.3°C (Heat Index)
     *          Dew point: 18.2°C
     *          Bft 3 — Gentle breeze"
     */
    public static String formatDerivedSummary(double tempC, double humidityPct, double windMs) {
        StringBuilder sb = new StringBuilder();

        // Feels like
        double fl = feelsLike(tempC, humidityPct, windMs);
        String flSource;
        if (tempC >= 27.0 && humidityPct >= 40.0) {
            flSource = "Heat Index";
        } else if (tempC <= 10.0 && windMs > 1.34) {
            flSource = "Wind Chill";
        } else {
            flSource = "Ambient";
        }
        sb.append(String.format(Locale.US, "Feels like: %.1f°C (%s)", fl, flSource));

        // Dew point
        double dp = dewPoint(tempC, humidityPct);
        sb.append(String.format(Locale.US, "\nDew point: %.1f°C", dp));

        // Beaufort
        BeaufortResult bft = beaufort(windMs);
        sb.append("\n").append(bft.toString());

        return sb.toString();
    }
}
