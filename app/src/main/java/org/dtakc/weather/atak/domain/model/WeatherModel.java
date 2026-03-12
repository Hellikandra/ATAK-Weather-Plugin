package org.dtakc.weather.atak.domain.model;

/** Pure domain model — no Android/ATAK imports. */
public final class WeatherModel {
    public final double latitude, longitude;
    public final String locationName;
    public final double temperatureMin, temperatureMax, apparentTemperature;
    public final double humidity, pressure, visibility;
    public final double windSpeed, windDirection;
    public final double precipitationSum, precipitationHours;
    public final int    weatherCode;
    public final String requestTimestamp;
    // METAR-specific (empty for non-METAR sources)
    public final String icaoId, flightCategory, rawMetar;

    private WeatherModel(Builder b) {
        latitude = b.latitude; longitude = b.longitude; locationName = b.locationName;
        temperatureMin = b.temperatureMin; temperatureMax = b.temperatureMax;
        apparentTemperature = b.apparentTemperature; humidity = b.humidity;
        pressure = b.pressure; visibility = b.visibility;
        windSpeed = b.windSpeed; windDirection = b.windDirection;
        precipitationSum = b.precipitationSum; precipitationHours = b.precipitationHours;
        weatherCode = b.weatherCode; requestTimestamp = b.requestTimestamp;
        icaoId = b.icaoId; flightCategory = b.flightCategory; rawMetar = b.rawMetar;
    }

    public boolean isMetarSource() { return icaoId != null && !icaoId.isEmpty(); }

    public static class Builder {
        double latitude, longitude;
        String locationName = "";
        double temperatureMin, temperatureMax, apparentTemperature, humidity,
               pressure, visibility, windSpeed, windDirection,
               precipitationSum, precipitationHours;
        int    weatherCode;
        String requestTimestamp = "", icaoId = "", flightCategory = "", rawMetar = "";

        public Builder(double lat, double lon) { latitude = lat; longitude = lon; }
        public Builder locationName(String v)         { locationName = v; return this; }
        public Builder temperatureMin(double v)        { temperatureMin = v; return this; }
        public Builder temperatureMax(double v)        { temperatureMax = v; return this; }
        public Builder apparentTemperature(double v)   { apparentTemperature = v; return this; }
        public Builder humidity(double v)              { humidity = v; return this; }
        public Builder pressure(double v)              { pressure = v; return this; }
        public Builder visibility(double v)            { visibility = v; return this; }
        public Builder windSpeed(double v)             { windSpeed = v; return this; }
        public Builder windDirection(double v)         { windDirection = v; return this; }
        public Builder precipitationSum(double v)      { precipitationSum = v; return this; }
        public Builder precipitationHours(double v)    { precipitationHours = v; return this; }
        public Builder weatherCode(int v)              { weatherCode = v; return this; }
        public Builder requestTimestamp(String v)      { requestTimestamp = v; return this; }
        public Builder icaoId(String v)                { icaoId = v; return this; }
        public Builder flightCategory(String v)        { flightCategory = v; return this; }
        public Builder rawMetar(String v)              { rawMetar = v; return this; }
        public WeatherModel build()                    { return new WeatherModel(this); }
    }
}
