package org.dtakc.weather.atak.domain.model;

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

    public double deltaTemperatureMax()       { return mapCenter.temperatureMax      - selfMarker.temperatureMax; }
    public double deltaTemperatureMin()       { return mapCenter.temperatureMin      - selfMarker.temperatureMin; }
    public double deltaApparentTemperature()  { return mapCenter.apparentTemperature - selfMarker.apparentTemperature; }
    public double deltaHumidity()             { return mapCenter.humidity            - selfMarker.humidity; }
    public double deltaPressure()             { return mapCenter.pressure            - selfMarker.pressure; }
    public double deltaVisibility()           { return mapCenter.visibility          - selfMarker.visibility; }
    public double deltaWindSpeed()            { return mapCenter.windSpeed           - selfMarker.windSpeed; }
    public double deltaPrecipitationSum()     { return mapCenter.precipitationSum    - selfMarker.precipitationSum; }

    /** Format a delta with a leading +/− sign. */
    public static String formatDelta(double delta, String unit) {
        return String.format("%+.1f %s", delta, unit);
    }
}
