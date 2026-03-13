package com.atakmap.android.weather.domain.model;

/**
 * Domain model representing current weather conditions at a point.
 * Pure Java data class — no Android dependencies.
 */
public class WeatherModel {

    private final double latitude;
    private final double longitude;
    private final String locationName;

    private final double temperatureMin;    // °C
    private final double temperatureMax;    // °C
    private final double apparentTemperature; // °C (real feel)
    private final double humidity;          // %
    private final double pressure;          // hPa
    private final double visibility;        // meters
    private final double windSpeed;         // m/s
    private final double windDirection;     // degrees
    private final double precipitationSum;  // mm
    private final double precipitationHours;
    private final int    weatherCode;       // WMO code
    private final String requestTimestamp;  // yyyy-MM-dd HH:mm:ss

    // ── AWC METAR-specific fields (empty string when source is not AWC) ───────
    /** ICAO station identifier, e.g. "EBLG".  Empty for non-METAR sources. */
    private final String icaoId;
    /** FAA flight category: "VFR", "MVFR", "IFR", or "LIFR".  Empty for non-METAR sources. */
    private final String flightCategory;
    /** Full encoded METAR observation string, e.g. "EBLG 111220Z 27008KT ...". */
    private final String rawMetar;

    private WeatherModel(Builder b) {
        this.latitude             = b.latitude;
        this.longitude            = b.longitude;
        this.locationName         = b.locationName;
        this.temperatureMin       = b.temperatureMin;
        this.temperatureMax       = b.temperatureMax;
        this.apparentTemperature  = b.apparentTemperature;
        this.humidity             = b.humidity;
        this.pressure             = b.pressure;
        this.visibility           = b.visibility;
        this.windSpeed            = b.windSpeed;
        this.windDirection        = b.windDirection;
        this.precipitationSum     = b.precipitationSum;
        this.precipitationHours   = b.precipitationHours;
        this.weatherCode          = b.weatherCode;
        this.requestTimestamp     = b.requestTimestamp;
        this.icaoId               = b.icaoId;
        this.flightCategory       = b.flightCategory;
        this.rawMetar             = b.rawMetar;
    }

    // ── Getters ──────────────────────────────────────────────────────────────
    public double getLatitude()             { return latitude; }
    public double getLongitude()            { return longitude; }
    public String getLocationName()         { return locationName; }
    public double getTemperatureMin()       { return temperatureMin; }
    public double getTemperatureMax()       { return temperatureMax; }
    public double getApparentTemperature()  { return apparentTemperature; }
    public double getHumidity()             { return humidity; }
    public double getPressure()             { return pressure; }
    public double getVisibility()           { return visibility; }
    public double getWindSpeed()            { return windSpeed; }
    public double getWindDirection()        { return windDirection; }
    public double getPrecipitationSum()     { return precipitationSum; }
    public double getPrecipitationHours()   { return precipitationHours; }
    public int    getWeatherCode()          { return weatherCode; }
    public String getRequestTimestamp()     { return requestTimestamp; }
    /** ICAO station identifier.  Empty string when source is not AWC METAR. */
    public String getIcaoId()              { return icaoId; }
    /** FAA flight category: VFR / MVFR / IFR / LIFR.  Empty for non-METAR sources. */
    public String getFlightCategory()      { return flightCategory; }
    /** Full raw encoded METAR string.  Empty for non-METAR sources. */
    public String getRawMetar()            { return rawMetar; }
    /** Returns true when this model was produced by an AWC METAR fetch. */
    public boolean isMetarSource()         { return !icaoId.isEmpty(); }

    // ── Builder ──────────────────────────────────────────────────────────────
    public static class Builder {
        private double latitude, longitude;
        private String locationName         = "";
        private double temperatureMin       = 0;
        private double temperatureMax       = 0;
        private double apparentTemperature  = 0;
        private double humidity             = 0;
        private double pressure             = 0;
        private double visibility           = 0;
        private double windSpeed            = 0;
        private double windDirection        = 0;
        private double precipitationSum     = 0;
        private double precipitationHours   = 0;
        private int    weatherCode          = 0;
        private String requestTimestamp     = "";
        // AWC METAR-specific (empty = not a METAR source)
        private String icaoId               = "";
        private String flightCategory       = "";
        private String rawMetar             = "";

        public Builder(double latitude, double longitude) {
            this.latitude  = latitude;
            this.longitude = longitude;
        }

        public Builder locationName(String v)        { locationName = v;        return this; }
        public Builder temperatureMin(double v)      { temperatureMin = v;      return this; }
        public Builder temperatureMax(double v)      { temperatureMax = v;      return this; }
        public Builder apparentTemperature(double v) { apparentTemperature = v; return this; }
        public Builder humidity(double v)            { humidity = v;            return this; }
        public Builder pressure(double v)            { pressure = v;            return this; }
        public Builder visibility(double v)          { visibility = v;          return this; }
        public Builder windSpeed(double v)           { windSpeed = v;           return this; }
        public Builder windDirection(double v)       { windDirection = v;       return this; }
        public Builder precipitationSum(double v)    { precipitationSum = v;    return this; }
        public Builder precipitationHours(double v)  { precipitationHours = v;  return this; }
        public Builder weatherCode(int v)            { weatherCode = v;         return this; }
        public Builder requestTimestamp(String v)    { requestTimestamp = v;    return this; }
        public Builder icaoId(String v)              { icaoId = v;              return this; }
        public Builder flightCategory(String v)      { flightCategory = v;      return this; }
        public Builder rawMetar(String v)            { rawMetar = v;            return this; }

        public WeatherModel build() { return new WeatherModel(this); }
    }
}
