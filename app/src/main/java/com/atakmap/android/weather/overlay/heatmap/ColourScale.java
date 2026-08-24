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
            case "temperature_2m":       return temperature();
            case "wind_speed_10m":       return windSpeed();
            case "relative_humidity_2m": return humidity();
            case "surface_pressure":     return pressure();
            case "visibility":           return visibility();
            case "precipitation":        return precipitation();
            case "weather_code":         return weatherCode();
            // Marine parameters
            case "wave_height":
            case "wind_wave_height":
            case "swell_wave_height":    return waveHeight();
            case "ocean_current_velocity": return oceanCurrent();
            case "sea_surface_temperature": return seaSurfaceTemp();
            case "sea_level_height_msl": return tideLevel();
            case "wave_period":
            case "wind_wave_period":
            case "swell_wave_period":    return wavePeriod();
            default:                     return temperature();
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

    /**
     * Relative humidity (%): Brown(dry) -> Yellow(moderate) -> Green(humid) -> Blue(saturated).
     * Key insight: 0-30% is very dry (brown/yellow), 40-70% is comfortable (green),
     * 70-100% is humid to saturated (teal to blue).
     */
    private static ColourScale humidity() {
        return new ColourScale(
                new double[]{ 0,   20,   35,   50,   65,   80,   95,  100 },
                new int[]{
                        0xFFC08040,  // brown (very dry)
                        0xFFD0A020,  // dark yellow
                        0xFFE0D000,  // yellow
                        0xFF80C040,  // yellow-green (comfortable)
                        0xFF40A060,  // green
                        0xFF209080,  // teal
                        0xFF2070B0,  // blue-teal (humid)
                        0xFF2050D0   // blue (saturated)
                }
        );
    }

    /**
     * Surface pressure (hPa): Purple(low) -> Blue -> Green(normal) -> Yellow -> Orange(high).
     * Standard sea-level is ~1013 hPa. Low pressure = storms, high = fair weather.
     */
    private static ColourScale pressure() {
        return new ColourScale(
                new double[]{ 970,  985,  995, 1005, 1013, 1020, 1030, 1040 },
                new int[]{
                        0xFFA020C0,  // purple (very low — deep storm)
                        0xFF6040E0,  // blue-purple
                        0xFF4080E0,  // blue (low)
                        0xFF40B0A0,  // teal
                        0xFF60C060,  // green (normal ~1013)
                        0xFFB0D040,  // yellow-green
                        0xFFE0C000,  // yellow (high)
                        0xFFE08000   // orange (very high)
                }
        );
    }

    // ── Marine scales ──────────────────────────────────────────────────────

    /** Wave height (m): Teal(calm) -> Blue(moderate) -> Purple(high) -> Red(dangerous) */
    private static ColourScale waveHeight() {
        return new ColourScale(
                new double[]{ 0,  0.5,  1,  2,  3,  5,  8 },
                new int[]{
                        0xFF40E0D0,  // teal (calm)
                        0xFF30B0C0,  // medium teal
                        0xFF2080C0,  // blue
                        0xFF3060D0,  // deep blue
                        0xFF6040C0,  // purple
                        0xFFA030A0,  // magenta
                        0xFFC02060   // red (dangerous)
                }
        );
    }

    /** Ocean current velocity (m/s): Dark blue(still) -> Cyan(moderate) -> White(strong) */
    private static ColourScale oceanCurrent() {
        return new ColourScale(
                new double[]{ 0,  0.1,  0.3,  0.5,  1.0,  2.0,  3.0 },
                new int[]{
                        0xFF103060,  // dark navy (still)
                        0xFF204080,  // dark blue
                        0xFF3070C0,  // blue
                        0xFF40A0E0,  // light blue
                        0xFF60D0E0,  // cyan
                        0xFF80E0E0,  // bright cyan
                        0xFFD0F0F0   // near-white (strong)
                }
        );
    }

    /** Sea surface temperature (°C): Blue(cold) -> Cyan -> Green -> Yellow -> Red(warm) */
    private static ColourScale seaSurfaceTemp() {
        return new ColourScale(
                new double[]{ -2,   2,   8,  14,  20,  26,  32 },
                new int[]{
                        0xFF2020C0,  // deep blue (near freezing)
                        0xFF4080E0,  // blue
                        0xFF40C0C0,  // cyan
                        0xFF40C040,  // green
                        0xFFE0E000,  // yellow
                        0xFFFF8000,  // orange
                        0xFFE02020   // red (tropical)
                }
        );
    }

    /** Tide / sea level height (m relative to MSL): Brown(low) -> White(MSL) -> Blue(high) */
    private static ColourScale tideLevel() {
        return new ColourScale(
                new double[]{ -1.5,  -0.5,  -0.1,   0,   0.1,   0.5,   1.5 },
                new int[]{
                        0xFF806030,  // dark brown (very low tide)
                        0xFFC0A060,  // tan
                        0xFFE0D0A0,  // light tan
                        0xFFFFFFFF,  // white (MSL)
                        0xFFA0C0E0,  // light blue
                        0xFF4080C0,  // blue
                        0xFF2050A0   // deep blue (very high tide)
                }
        );
    }

    /** Wave period (seconds): Short periods are steep/dangerous, long are gentle swell */
    private static ColourScale wavePeriod() {
        return new ColourScale(
                new double[]{ 2,   4,   6,   8,  10,  14,  18 },
                new int[]{
                        0xFFE02020,  // red (very short — steep, dangerous)
                        0xFFFFA000,  // orange
                        0xFFE0E000,  // yellow
                        0xFF80D040,  // green
                        0xFF40B0B0,  // teal
                        0xFF4080D0,  // blue
                        0xFF6060C0   // purple (long period swell)
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
