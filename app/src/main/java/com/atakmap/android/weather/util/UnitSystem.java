package com.atakmap.android.weather.util;

/**
 * Unit system preference for the WeatherTool plugin.
 * Determines how all weather values are displayed throughout the UI.
 */
public enum UnitSystem {
    METRIC,     // °C, m/s, hPa, m/km, mm
    IMPERIAL,   // °F, mph, inHg, mi, in
    AVIATION    // °C, kt, inHg, SM/ft, mm
}
