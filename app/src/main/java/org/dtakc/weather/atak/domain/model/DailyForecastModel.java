package org.dtakc.weather.atak.domain.model;

/**
 * Domain model for a single day's forecast entry.
 */
public class DailyForecastModel {

    private final String date;          // yyyy-MM-dd
    private final String dayLabel;      // "Today", "MONDAY", …
    private final double temperatureMax;
    private final double temperatureMin;
    private final int    weatherCode;
    private final double precipitationSum;
    private final double precipitationHours;
    private final double precipitationProbabilityMax; // %

    private DailyForecastModel(Builder b) {
        this.date                        = b.date;
        this.dayLabel                    = b.dayLabel;
        this.temperatureMax              = b.temperatureMax;
        this.temperatureMin              = b.temperatureMin;
        this.weatherCode                 = b.weatherCode;
        this.precipitationSum            = b.precipitationSum;
        this.precipitationHours          = b.precipitationHours;
        this.precipitationProbabilityMax = b.precipitationProbabilityMax;
    }

    public String getDate()                        { return date; }
    public String getDayLabel()                    { return dayLabel; }
    public double getTemperatureMax()              { return temperatureMax; }
    public double getTemperatureMin()              { return temperatureMin; }
    public int    getWeatherCode()                 { return weatherCode; }
    public double getPrecipitationSum()            { return precipitationSum; }
    public double getPrecipitationHours()          { return precipitationHours; }
    public double getPrecipitationProbabilityMax() { return precipitationProbabilityMax; }

    public static class Builder {
        private String date                        = "";
        private String dayLabel                    = "";
        private double temperatureMax              = 0;
        private double temperatureMin              = 0;
        private int    weatherCode                 = 0;
        private double precipitationSum            = 0;
        private double precipitationHours          = 0;
        private double precipitationProbabilityMax = 0;

        public Builder date(String v)                        { date = v;                        return this; }
        public Builder dayLabel(String v)                    { dayLabel = v;                    return this; }
        public Builder temperatureMax(double v)              { temperatureMax = v;              return this; }
        public Builder temperatureMin(double v)              { temperatureMin = v;              return this; }
        public Builder weatherCode(int v)                    { weatherCode = v;                 return this; }
        public Builder precipitationSum(double v)            { precipitationSum = v;            return this; }
        public Builder precipitationHours(double v)          { precipitationHours = v;          return this; }
        public Builder precipitationProbabilityMax(double v) { precipitationProbabilityMax = v; return this; }

        public DailyForecastModel build() { return new DailyForecastModel(this); }
    }
}
