package com.atakmap.android.weather.overlay.heatmap;

import android.graphics.Color;

/**
 * Colour scale for mapping weather parameter values to ARGB colours.
 *
 * <p>Each scale is defined by paired arrays of threshold values and corresponding
 * colours. Values between thresholds are linearly interpolated in RGB space.</p>
 *
 * <h3>Built-in scales</h3>
 * <ul>
 *   <li><b>temperature_2m:</b> Blue(-20C) -> Cyan(0) -> Green(15) -> Yellow(25) -> Red(40)</li>
 *   <li><b>wind_speed_10m:</b> Green(0) -> Yellow(5) -> Orange(10) -> Red(15) -> Magenta(25+)</li>
 *   <li><b>visibility:</b> Red(&lt;1km) -> Orange(1-5km) -> Yellow(5-10km) -> Green(10km+)</li>
 *   <li><b>precipitation:</b> Transparent(0) -> LightBlue -> Blue -> Purple</li>
 *   <li><b>weather_code:</b> Green(clear) -> Amber(clouds) -> Red(severe)</li>
 * </ul>
 */
public class ColourScale {

    private final int[] colors;      // ARGB colour stops
    private final double[] values;   // value thresholds (ascending)

    /**
     * @param values ascending array of threshold values
     * @param colors corresponding ARGB colours (same length as values)
     */
    public ColourScale(double[] values, int[] colors) {
        if (values.length != colors.length || values.length < 2) {
            throw new IllegalArgumentException(
                    "values and colors must have same length >= 2");
        }
        this.values = values;
        this.colors = colors;
    }

    /**
     * Get the colour scale for a weather parameter key.
     *
     * @param paramKey e.g. "temperature_2m", "wind_speed_10m", etc.
     * @return the appropriate colour scale
     */
    public static ColourScale forParameter(String paramKey) {
        if (paramKey == null) return temperature();
        switch (paramKey) {
            case "temperature_2m":  return temperature();
            case "wind_speed_10m":  return windSpeed();
            case "visibility":      return visibility();
            case "precipitation":   return precipitation();
            case "weather_code":    return weatherCode();
            default:                return temperature();
        }
    }

    /**
     * Interpolate colour for a value. Returns ARGB int.
     *
     * @param value the weather parameter value
     * @return interpolated ARGB colour
     */
    public int getColor(double value) {
        if (Double.isNaN(value)) {
            return Color.TRANSPARENT;
        }

        // Clamp to range
        if (value <= values[0]) return colors[0];
        if (value >= values[values.length - 1]) return colors[colors.length - 1];

        // Find the bracketing pair
        for (int i = 0; i < values.length - 1; i++) {
            if (value >= values[i] && value <= values[i + 1]) {
                double t = (value - values[i]) / (values[i + 1] - values[i]);
                return lerpColor(colors[i], colors[i + 1], (float) t);
            }
        }
        return colors[colors.length - 1];
    }

    /** Return the threshold values array (ascending). */
    public double[] getValues() { return values; }

    /** Return the ARGB colour stops array (parallel to values). */
    public int[] getColors() { return colors; }

    // ── Built-in scales ─────────────────────────────────────────────────────

    /** Temperature: Blue(-20C) -> Cyan(0) -> Green(15) -> Yellow(25) -> Red(40) */
    private static ColourScale temperature() {
        return new ColourScale(
                new double[]{ -20, -5,   0,  10,  15,  25,  35,  40 },
                new int[]{
                        0xFF2020C0,  // deep blue
                        0xFF4080E0,  // medium blue
                        0xFF00D0D0,  // cyan
                        0xFF40C040,  // green
                        0xFF80E000,  // yellow-green
                        0xFFFFD000,  // yellow
                        0xFFFF6000,  // orange
                        0xFFE00000   // red
                }
        );
    }

    /** Wind speed (m/s): Green(0) -> Yellow(5) -> Orange(10) -> Red(15) -> Magenta(25+) */
    private static ColourScale windSpeed() {
        return new ColourScale(
                new double[]{ 0,  3,   5,  10,  15,  20,  25 },
                new int[]{
                        0xFF00C000,  // green (calm)
                        0xFF80D000,  // light green
                        0xFFE0E000,  // yellow
                        0xFFFFA000,  // orange
                        0xFFFF4000,  // red-orange
                        0xFFE00000,  // red
                        0xFFD000D0   // magenta (strong)
                }
        );
    }

    /** Visibility (metres): Red(<1km) -> Orange(1-5km) -> Yellow(5-10km) -> Green(10km+) */
    private static ColourScale visibility() {
        return new ColourScale(
                new double[]{ 0,   500,  1000,  5000,  10000,  30000 },
                new int[]{
                        0xFFC00000,  // dark red (fog)
                        0xFFE04000,  // red
                        0xFFFF8000,  // orange
                        0xFFE0E000,  // yellow
                        0xFF80D040,  // yellow-green
                        0xFF00B000   // green (clear)
                }
        );
    }

    /** Precipitation (mm): Transparent(0) -> LightBlue -> Blue -> Purple */
    private static ColourScale precipitation() {
        return new ColourScale(
                new double[]{ 0,  0.1,   1,   5,  10,  25 },
                new int[]{
                        0x00000000,  // transparent (no precip)
                        0xFF80C0FF,  // light blue
                        0xFF4090FF,  // medium blue
                        0xFF2060E0,  // blue
                        0xFF3030C0,  // dark blue
                        0xFF8020C0   // purple (heavy)
                }
        );
    }

    /** Weather code (WMO): Green(clear) -> Amber(clouds) -> Red(severe) */
    private static ColourScale weatherCode() {
        return new ColourScale(
                new double[]{ 0,   3,  45,  55,  65,  80,  95, 99 },
                new int[]{
                        0xFF00C000,  // clear sky — green
                        0xFF80D000,  // mainly clear — yellow-green
                        0xFFD0D000,  // fog — yellow
                        0xFFFFA000,  // drizzle — orange
                        0xFFFF4000,  // rain — red-orange
                        0xFFE00000,  // showers — red
                        0xFFC000C0,  // thunderstorm — purple
                        0xFF800080   // severe thunderstorm — dark purple
                }
        );
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Linear interpolation between two ARGB colours.
     */
    private static int lerpColor(int c1, int c2, float t) {
        int a1 = Color.alpha(c1), r1 = Color.red(c1),
                g1 = Color.green(c1), b1 = Color.blue(c1);
        int a2 = Color.alpha(c2), r2 = Color.red(c2),
                g2 = Color.green(c2), b2 = Color.blue(c2);

        int a = Math.round(a1 + (a2 - a1) * t);
        int r = Math.round(r1 + (r2 - r1) * t);
        int g = Math.round(g1 + (g2 - g1) * t);
        int b = Math.round(b1 + (b2 - b1) * t);

        return Color.argb(clamp(a), clamp(r), clamp(g), clamp(b));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
