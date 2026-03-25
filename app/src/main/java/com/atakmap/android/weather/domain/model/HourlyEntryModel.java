package com.atakmap.android.weather.domain.model;

/**
 * Domain model for a single hourly forecast entry.
 * Used by the SeekBar scrubber on Tab 1.
 */
public class HourlyEntryModel {

    private final String isoTime;           // "2024-07-27T14:00"
    private final int    hour;              // 0-23 extracted
    private final double temperature;       // °C
    private final double apparentTemperature;
    private final double humidity;          // %
    private final double pressure;          // hPa
    private final double visibility;        // meters
    private final double windSpeed;         // m/s
    private final double windDirection;     // degrees
    private final double precipitationProbability; // %
    private final double precipitation;            // mm
    private final int    weatherCode;              // WMO code

    private HourlyEntryModel(Builder b) {
        this.isoTime                  = b.isoTime;
        this.hour                     = b.hour;
        this.temperature              = b.temperature;
        this.apparentTemperature      = b.apparentTemperature;
        this.humidity                 = b.humidity;
        this.pressure                 = b.pressure;
        this.visibility               = b.visibility;
        this.windSpeed                = b.windSpeed;
        this.windDirection            = b.windDirection;
        this.precipitationProbability = b.precipitationProbability;
        this.precipitation            = b.precipitation;
        this.weatherCode              = b.weatherCode;
    }

    public String getIsoTime()                  { return isoTime; }
    public int    getHour()                     { return hour; }
    public double getTemperature()              { return temperature; }
    public double getApparentTemperature()      { return apparentTemperature; }
    public double getHumidity()                 { return humidity; }
    public double getPressure()                 { return pressure; }
    public double getVisibility()               { return visibility; }
    public double getWindSpeed()                { return windSpeed; }
    public double getWindDirection()            { return windDirection; }
    public double getPrecipitationProbability() { return precipitationProbability; }
    public double getPrecipitation()            { return precipitation; }
    public int    getWeatherCode()              { return weatherCode; }

    // ── Behavioral methods (Sprint 4 — S4.2) ──────────────────────────────────

    /**
     * Returns {@code true} if rain is likely in this hourly slot.
     * Criteria: precipitation probability &ge; 50% OR actual precipitation &gt; 0.
     *
     * @return true if rain is likely
     */
    public boolean isRainLikely() {
        return precipitationProbability >= 50.0 || precipitation > 0.0;
    }

    /**
     * Returns {@code true} if this hour has severe conditions.
     * Criteria: WMO code &ge; 95 (thunderstorm) OR wind speed &gt; 15 m/s OR visibility &lt; 1000m.
     *
     * @return true if conditions are severe
     */
    public boolean isSevere() {
        return weatherCode >= 95 || windSpeed > 15.0 || visibility < 1000.0;
    }

    /**
     * Tactical condition label for this hour: "GREEN", "AMBER", or "RED".
     *
     * @return condition string
     */
    public String tacticalCondition() {
        if (windSpeed > 15.0 || visibility < 1000.0 || weatherCode >= 95) return "RED";
        if (windSpeed > 10.0 || visibility < 5000.0 || weatherCode >= 51
                || precipitationProbability >= 60.0) return "AMBER";
        return "GREEN";
    }

    public static class Builder {
        private String isoTime = "";
        private int    hour    = 0;
        private double temperature = 0, apparentTemperature = 0;
        private double humidity = 0, pressure = 0, visibility = 0;
        private double windSpeed = 0, windDirection = 0;
        private double precipitationProbability = 0;
        private double precipitation = 0;
        private int    weatherCode   = 0;

        public Builder isoTime(String v)                  { isoTime = v;                  return this; }
        public Builder hour(int v)                        { hour = v;                     return this; }
        public Builder temperature(double v)              { temperature = v;              return this; }
        public Builder apparentTemperature(double v)      { apparentTemperature = v;      return this; }
        public Builder humidity(double v)                 { humidity = v;                 return this; }
        public Builder pressure(double v)                 { pressure = v;                 return this; }
        public Builder visibility(double v)               { visibility = v;               return this; }
        public Builder windSpeed(double v)                { windSpeed = v;                return this; }
        public Builder windDirection(double v)            { windDirection = v;            return this; }
        public Builder precipitationProbability(double v) { precipitationProbability = v; return this; }
        public Builder precipitation(double v)            { precipitation = v;            return this; }
        public Builder weatherCode(int v)                 { weatherCode = v;              return this; }

        public HourlyEntryModel build() { return new HourlyEntryModel(this); }
    }
}
