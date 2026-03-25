package com.atakmap.android.weather.domain.model;

/**
 * Domain model for a single day's forecast entry.
 *
 * <p>Sprint 9 (S9.2): Added sunrise, sunset, and daylightDurationSec fields
 * to support astronomical data display.</p>
 */
public class DailyForecastModel {

    private final String date;          // yyyy-MM-dd
    private final String dayLabel;      // "Today", "MONDAY", ...
    private final double temperatureMax;
    private final double temperatureMin;
    private final int    weatherCode;
    private final double precipitationSum;
    private final double precipitationHours;
    private final double precipitationProbabilityMax; // %
    private final String sunrise;               // ISO-8601 local, e.g. "2024-07-27T06:42"
    private final String sunset;                // ISO-8601 local, e.g. "2024-07-27T20:15"
    private final double daylightDurationSec;   // seconds of daylight

    private DailyForecastModel(Builder b) {
        this.date                        = b.date;
        this.dayLabel                    = b.dayLabel;
        this.temperatureMax              = b.temperatureMax;
        this.temperatureMin              = b.temperatureMin;
        this.weatherCode                 = b.weatherCode;
        this.precipitationSum            = b.precipitationSum;
        this.precipitationHours          = b.precipitationHours;
        this.precipitationProbabilityMax = b.precipitationProbabilityMax;
        this.sunrise                     = b.sunrise;
        this.sunset                      = b.sunset;
        this.daylightDurationSec         = b.daylightDurationSec;
    }

    public String getDate()                        { return date; }
    public String getDayLabel()                    { return dayLabel; }
    public double getTemperatureMax()              { return temperatureMax; }
    public double getTemperatureMin()              { return temperatureMin; }
    public int    getWeatherCode()                 { return weatherCode; }
    public double getPrecipitationSum()            { return precipitationSum; }
    public double getPrecipitationHours()          { return precipitationHours; }
    public double getPrecipitationProbabilityMax() { return precipitationProbabilityMax; }
    public String getSunrise()                     { return sunrise; }
    public String getSunset()                      { return sunset; }
    public double getDaylightDurationSec()         { return daylightDurationSec; }

    public static class Builder {
        private String date                        = "";
        private String dayLabel                    = "";
        private double temperatureMax              = 0;
        private double temperatureMin              = 0;
        private int    weatherCode                 = 0;
        private double precipitationSum            = 0;
        private double precipitationHours          = 0;
        private double precipitationProbabilityMax = 0;
        private String sunrise                     = null;
        private String sunset                      = null;
        private double daylightDurationSec         = 0;

        public Builder date(String v)                        { date = v;                        return this; }
        public Builder dayLabel(String v)                    { dayLabel = v;                    return this; }
        public Builder temperatureMax(double v)              { temperatureMax = v;              return this; }
        public Builder temperatureMin(double v)              { temperatureMin = v;              return this; }
        public Builder weatherCode(int v)                    { weatherCode = v;                 return this; }
        public Builder precipitationSum(double v)            { precipitationSum = v;            return this; }
        public Builder precipitationHours(double v)          { precipitationHours = v;          return this; }
        public Builder precipitationProbabilityMax(double v) { precipitationProbabilityMax = v; return this; }
        public Builder sunrise(String v)                     { sunrise = v;                     return this; }
        public Builder sunset(String v)                      { sunset = v;                      return this; }
        public Builder daylightDurationSec(double v)         { daylightDurationSec = v;         return this; }

        public DailyForecastModel build() { return new DailyForecastModel(this); }
    }
}
