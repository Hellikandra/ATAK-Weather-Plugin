package com.atakmap.android.weather.domain.model;

/**
 * Holds two WeatherModel instances for side-by-side comparison:
 * the user's own position (self marker) vs. the current map centre.
 *
 * Delta values are computed lazily for each numeric field.
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
}
