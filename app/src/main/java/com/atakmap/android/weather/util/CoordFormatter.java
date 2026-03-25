package com.atakmap.android.weather.util;

import java.util.Locale;

/**
 * Coordinate formatting utilities.
 * Fixes the original float precision loss (double → float → String).
 */
public final class CoordFormatter {

    private CoordFormatter() {}

    /**
     * Format a latitude or longitude to 6 decimal places (~0.1m precision).
     * The original code cast to float (~11m precision) — this is fixed here.
     *
     * <p>IMPORTANT: always uses {@link Locale#US} so the decimal separator
     * is a dot ({@code 48.856600}), never a comma ({@code 48,856600}).
     * European locales produce commas which break Open-Meteo URL parsing.</p>
     */
    public static String format(double coordinate) {
        return String.format(Locale.US, "%.6f", coordinate);
    }
}
