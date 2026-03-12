package com.atakmap.android.weather.util;

/**
 * Coordinate formatting utilities.
 * Fixes the original float precision loss (double → float → String).
 */
public final class CoordFormatter {

    private CoordFormatter() {}

    /**
     * Format a latitude or longitude to 6 decimal places (~0.1m precision).
     * The original code cast to float (~11m precision) — this is fixed here.
     */
    public static String format(double coordinate) {
        return String.format("%.6f", coordinate);
    }
}
