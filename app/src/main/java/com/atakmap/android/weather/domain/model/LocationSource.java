package com.atakmap.android.weather.domain.model;

/**
 * Identifies which map coordinate was used to request weather data.
 *
 * This enum travels with every weather request and is stored alongside
 * the result so the UI can always show the user exactly which position
 * the data describes.
 *
 * Fallback chain (applied in WeatherViewModel.loadWeather):
 *   SELF_MARKER  →  GPS fix available
 *   MAP_CENTRE   →  GPS unavailable, use map centre instead
 *
 * In Tab 6 (Comparison), both slots are loaded simultaneously, one per
 * source.  The Room cache (Sprint 3) will key snapshots by this enum so
 * SELF and CENTRE results are never mixed up.
 */
public enum LocationSource {

    /** Device GPS position — from MapView.getSelfMarker(). */
    SELF_MARKER("Self"),

    /** Current map centre — from MapView.getCenterPoint(). */
    MAP_CENTRE("Map centre");

    /** Short human-readable label shown in UI card headers. */
    public final String label;

    LocationSource(String label) {
        this.label = label;
    }
}
