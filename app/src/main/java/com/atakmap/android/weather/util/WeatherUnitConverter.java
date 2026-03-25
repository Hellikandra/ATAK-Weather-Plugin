package com.atakmap.android.weather.util;

import java.util.Locale;

/**
 * Centralised unit conversion utilities for the WeatherTool plugin.
 *
 * <h3>Why this class exists (Sprint 2 — S2.2)</h3>
 * Before extraction, conversion constants ({@code KT_TO_MS}, {@code SM_TO_M})
 * and inline arithmetic were scattered across {@code AviationWeatherSource},
 * {@code WindMarkerManager}, and various View classes. Consolidating here:
 * <ul>
 *   <li>Single source of truth for conversion factors.</li>
 *   <li>Consistent rounding / formatting across the plugin.</li>
 *   <li>Unit-testable in isolation (no Android dependencies).</li>
 * </ul>
 *
 * <h3>Internal units</h3>
 * <table>
 *   <tr><th>Quantity</th><th>Internal unit</th></tr>
 *   <tr><td>Temperature</td><td>°C (Celsius)</td></tr>
 *   <tr><td>Wind speed</td><td>m/s (metres per second)</td></tr>
 *   <tr><td>Pressure</td><td>hPa (hectopascals = millibars)</td></tr>
 *   <tr><td>Visibility / Distance</td><td>metres</td></tr>
 * </table>
 *
 * All conversions are <b>pure functions</b> — no side effects, no Android imports.
 * <p>
 * Sprint 7 adds a mutable {@link UnitSystem} preference and display formatters
 * ({@code fmtTemp}, {@code fmtWind}, etc.) that respect the current unit system.
 */
public final class WeatherUnitConverter {

    private WeatherUnitConverter() { /* non-instantiable */ }

    // ═══════════════════════════════════════════════════════════════════════════
    // Conversion factors (exact or standard reference values)
    // ═══════════════════════════════════════════════════════════════════════════

    /** Knots → metres per second. */
    public static final double KT_TO_MS  = 0.514444;

    /** Metres per second → knots. */
    public static final double MS_TO_KT  = 1.0 / KT_TO_MS;  // ≈ 1.94384

    /** Statute miles → metres. */
    public static final double SM_TO_M   = 1609.34;

    /** Metres → statute miles. */
    public static final double M_TO_SM   = 1.0 / SM_TO_M;

    /** Metres per second → kilometres per hour. */
    public static final double MS_TO_KMH = 3.6;

    /** Kilometres per hour → metres per second. */
    public static final double KMH_TO_MS = 1.0 / 3.6;

    /** Metres per second → miles per hour. */
    public static final double MS_TO_MPH = 2.23694;

    /** Miles per hour → metres per second. */
    public static final double MPH_TO_MS = 1.0 / 2.23694;

    /** Hectopascals → inches of mercury (inHg). */
    public static final double HPA_TO_INHG = 0.02953;

    /** Inches of mercury → hectopascals. */
    public static final double INHG_TO_HPA = 1.0 / 0.02953;

    /** Metres → feet. */
    public static final double M_TO_FT  = 3.28084;

    /** Feet → metres. */
    public static final double FT_TO_M  = 1.0 / 3.28084;

    /** Metres → nautical miles. */
    public static final double M_TO_NM  = 1.0 / 1852.0;

    /** Nautical miles → metres. */
    public static final double NM_TO_M  = 1852.0;

    // ═══════════════════════════════════════════════════════════════════════════
    // Temperature
    // ═══════════════════════════════════════════════════════════════════════════

    /** Celsius → Fahrenheit. */
    public static double celsiusToFahrenheit(double c) {
        return c * 9.0 / 5.0 + 32.0;
    }

    /** Fahrenheit → Celsius. */
    public static double fahrenheitToCelsius(double f) {
        return (f - 32.0) * 5.0 / 9.0;
    }

    /** Celsius → Kelvin. */
    public static double celsiusToKelvin(double c) {
        return c + 273.15;
    }

    /** Kelvin → Celsius. */
    public static double kelvinToCelsius(double k) {
        return k - 273.15;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Wind speed
    // ═══════════════════════════════════════════════════════════════════════════

    /** Knots → m/s. */
    public static double knotsToMs(double kt) { return kt * KT_TO_MS; }

    /** m/s → knots. */
    public static double msToKnots(double ms) { return ms * MS_TO_KT; }

    /** m/s → km/h. */
    public static double msToKmh(double ms) { return ms * MS_TO_KMH; }

    /** km/h → m/s. */
    public static double kmhToMs(double kmh) { return kmh * KMH_TO_MS; }

    /** m/s → mph. */
    public static double msToMph(double ms) { return ms * MS_TO_MPH; }

    /** mph → m/s. */
    public static double mphToMs(double mph) { return mph * MPH_TO_MS; }

    // ═══════════════════════════════════════════════════════════════════════════
    // Pressure
    // ═══════════════════════════════════════════════════════════════════════════

    /** hPa → inHg. */
    public static double hpaToInhg(double hpa) { return hpa * HPA_TO_INHG; }

    /** inHg → hPa. */
    public static double inhgToHpa(double inhg) { return inhg * INHG_TO_HPA; }

    // ═══════════════════════════════════════════════════════════════════════════
    // Distance / Visibility
    // ═══════════════════════════════════════════════════════════════════════════

    /** Statute miles → metres. */
    public static double statuteMilesToMetres(double sm) { return sm * SM_TO_M; }

    /** Metres → statute miles. */
    public static double metresToStatuteMiles(double m) { return m * M_TO_SM; }

    /** Metres → feet. */
    public static double metresToFeet(double m) { return m * M_TO_FT; }

    /** Feet → metres. */
    public static double feetToMetres(double ft) { return ft * FT_TO_M; }

    /** Metres → nautical miles. */
    public static double metresToNauticalMiles(double m) { return m * M_TO_NM; }

    /** Nautical miles → metres. */
    public static double nauticalMilesToMetres(double nm) { return nm * NM_TO_M; }

    // ═══════════════════════════════════════════════════════════════════════════
    // Formatting helpers
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Format temperature with unit suffix.
     * @param celsius temperature in °C
     * @param useFahrenheit if true, convert and display °F; otherwise °C
     * @return e.g. "23.4°C" or "74.1°F"
     */
    public static String formatTemp(double celsius, boolean useFahrenheit) {
        if (useFahrenheit) {
            return String.format(Locale.US, "%.1f°F", celsiusToFahrenheit(celsius));
        }
        return String.format(Locale.US, "%.1f°C", celsius);
    }

    /**
     * Format wind speed in knots from internal m/s value.
     * @param ms wind speed in m/s
     * @return e.g. "12.3 kt"
     */
    public static String formatWindKt(double ms) {
        return String.format(Locale.US, "%.1f kt", msToKnots(ms));
    }

    /**
     * Format wind speed in m/s.
     * @param ms wind speed in m/s
     * @return e.g. "6.3 m/s"
     */
    public static String formatWindMs(double ms) {
        return String.format(Locale.US, "%.1f m/s", ms);
    }

    /**
     * Format pressure with unit suffix.
     * @param hpa pressure in hPa
     * @param useInhg if true, display inHg; otherwise hPa
     * @return e.g. "1013.2 hPa" or "29.92 inHg"
     */
    public static String formatPressure(double hpa, boolean useInhg) {
        if (useInhg) {
            return String.format(Locale.US, "%.2f inHg", hpaToInhg(hpa));
        }
        return String.format(Locale.US, "%.1f hPa", hpa);
    }

    /**
     * Convert a compass bearing (0–360°) to a cardinal direction string.
     * @param degrees bearing in degrees true north
     * @return e.g. "N", "NNE", "NE", ... "NNW"
     */
    public static String degreesToCardinal(double degrees) {
        final String[] CARDINALS = {
                "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
        };
        double d = ((degrees % 360) + 360) % 360;  // normalise to [0, 360)
        int idx = (int) Math.round(d / 22.5) % 16;
        return CARDINALS[idx];
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Unit System Preference (Sprint 7 — S7.1, granular per-unit selection)
    // ═══════════════════════════════════════════════════════════════════════════

    private static UnitSystem currentSystem = UnitSystem.METRIC;

    // Per-unit preferences (default to preset values, overridden individually)
    private static String tempUnit     = "C";    // C, F
    private static String windUnit     = "MS";   // MS, KMH, MPH, KT
    private static String pressureUnit = "HPA";  // HPA, INHG, MMHG
    private static String visUnit      = "M";    // M, MI, SM, NM
    private static String altUnit      = "M";    // M, FT
    private static String precipUnit   = "MM";   // MM, IN

    /** Set the global unit system preset. Updates all individual units to match. */
    public static void setUnitSystem(UnitSystem system) {
        if (system == null) return;
        currentSystem = system;
        switch (system) {
            case IMPERIAL:
                tempUnit = "F"; windUnit = "MPH"; pressureUnit = "INHG";
                visUnit = "MI"; altUnit = "FT"; precipUnit = "IN";
                break;
            case AVIATION:
                tempUnit = "C"; windUnit = "KT"; pressureUnit = "INHG";
                visUnit = "SM"; altUnit = "FT"; precipUnit = "MM";
                break;
            case METRIC:
            default:
                tempUnit = "C"; windUnit = "MS"; pressureUnit = "HPA";
                visUnit = "M"; altUnit = "M"; precipUnit = "MM";
                break;
        }
    }

    /** Get the current unit system preset. */
    public static UnitSystem getUnitSystem() { return currentSystem; }

    // ── Per-unit setters (called from WeatherPreferenceFragment) ────────

    public static void setTempUnit(String u)     { if (u != null) tempUnit = u; }
    public static void setWindUnit(String u)     { if (u != null) windUnit = u; }
    public static void setPressureUnit(String u) { if (u != null) pressureUnit = u; }
    public static void setVisUnit(String u)      { if (u != null) visUnit = u; }
    public static void setAltUnit(String u)      { if (u != null) altUnit = u; }
    public static void setPrecipUnit(String u)   { if (u != null) precipUnit = u; }

    // ── Per-unit getters ────────────────────────────────────────────────

    public static String getTempUnit()     { return tempUnit; }
    public static String getWindUnit()     { return windUnit; }
    public static String getPressureUnit() { return pressureUnit; }
    public static String getVisUnit()      { return visUnit; }
    public static String getAltUnit()      { return altUnit; }
    public static String getPrecipUnit()   { return precipUnit; }

    // ── Display formatters (use per-unit preferences) ───────────────────

    /** Format temperature using current unit preference. Input: celsius. */
    public static String fmtTemp(double celsius) {
        switch (tempUnit) {
            case "F":  return String.format(Locale.US, "%.1f\u00B0F", celsiusToFahrenheit(celsius));
            default:   return String.format(Locale.US, "%.1f\u00B0C", celsius);
        }
    }

    /** Format temperature range. Input: celsius. */
    public static String fmtTempRange(double minC, double maxC) {
        return fmtTemp(minC) + " / " + fmtTemp(maxC);
    }

    /** Format wind speed using current unit preference. Input: m/s. */
    public static String fmtWind(double ms) {
        switch (windUnit) {
            case "MPH": return String.format(Locale.US, "%.1f mph", msToMph(ms));
            case "KT":  return String.format(Locale.US, "%.0f kt", msToKnots(ms));
            case "KMH": return String.format(Locale.US, "%.1f km/h", msToKmh(ms));
            default:    return String.format(Locale.US, "%.1f m/s", ms);
        }
    }

    /** Format wind with direction. Input: m/s, degrees. */
    public static String fmtWindDir(double ms, double degrees) {
        return fmtWind(ms) + " / " + String.format(Locale.US, "%.0f\u00B0", degrees);
    }

    /** Format pressure using current unit preference. Input: hPa. */
    public static String fmtPressure(double hpa) {
        switch (pressureUnit) {
            case "INHG": return String.format(Locale.US, "%.2f inHg", hpaToInhg(hpa));
            case "MMHG": return String.format(Locale.US, "%.1f mmHg", hpa * 0.750062);
            default:     return String.format(Locale.US, "%.1f hPa", hpa);
        }
    }

    /** Format visibility using current unit preference. Input: metres. */
    public static String fmtVisibility(double metres) {
        switch (visUnit) {
            case "MI": return String.format(Locale.US, "%.1f mi", metresToStatuteMiles(metres));
            case "SM": return String.format(Locale.US, "%.1f SM", metresToStatuteMiles(metres));
            case "NM": return String.format(Locale.US, "%.1f NM", metresToNauticalMiles(metres));
            default:
                if (metres >= 10000) return String.format(Locale.US, "%.1f km", metres / 1000.0);
                return String.format(Locale.US, "%.0f m", metres);
        }
    }

    /** Format precipitation using current unit preference. Input: mm. */
    public static String fmtPrecip(double mm) {
        switch (precipUnit) {
            case "IN": return String.format(Locale.US, "%.2f in", mm / 25.4);
            default:   return String.format(Locale.US, "%.1f mm", mm);
        }
    }

    /** Format altitude using current unit preference. Input: metres. */
    public static String fmtAltitude(double metres) {
        switch (altUnit) {
            case "FT": return String.format(Locale.US, "%.0f ft", metresToFeet(metres));
            default:   return String.format(Locale.US, "%.0f m", metres);
        }
    }

    /** Format humidity. Always percent. */
    public static String fmtHumidity(double percent) {
        return String.format(Locale.US, "%.0f %%", percent);
    }

    // ── Open-Meteo server-side unit query params ─────────────────────────

    /** Open-Meteo temperature_unit query param value. */
    public static String omTempUnit() {
        return "F".equals(tempUnit) ? "fahrenheit" : "celsius";
    }

    /** Open-Meteo wind_speed_unit query param value. */
    public static String omWindUnit() {
        switch (windUnit) {
            case "MPH": return "mph";
            case "KT":  return "kn";
            case "KMH": return "kmh";
            default:    return "ms";
        }
    }

    /** Open-Meteo precipitation_unit query param value. */
    public static String omPrecipUnit() {
        return "IN".equals(precipUnit) ? "inch" : "mm";
    }
}
