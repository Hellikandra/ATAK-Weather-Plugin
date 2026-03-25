package com.atakmap.android.weather.domain.model;

/**
 * Domain model representing a weather alert triggered when conditions
 * breach user-configured thresholds.
 *
 * <h3>Sprint 5 — S5.4</h3>
 *
 * <p>Immutable value object. Created by
 * {@link com.atakmap.android.weather.domain.service.WeatherAlertService}
 * and stored in the alert history.</p>
 */
public class WeatherAlert {

    /** Alert severity levels. */
    public enum Severity {
        /** Approaching threshold. */
        ADVISORY,
        /** Threshold breached. */
        WARNING,
        /** Severe conditions detected. */
        CRITICAL
    }

    /** What type of condition triggered the alert. */
    public enum Category {
        WIND,
        TEMPERATURE,
        VISIBILITY,
        PRECIPITATION,
        PRESSURE,
        THUNDERSTORM,
        FLIGHT_CATEGORY
    }

    private final Category  category;
    private final Severity  severity;
    private final String    title;
    private final String    detail;
    private final double    observedValue;
    private final double    thresholdValue;
    private final String    unit;
    private final long      timestampMs;
    private final double    latitude;
    private final double    longitude;

    private WeatherAlert(Builder b) {
        this.category       = b.category;
        this.severity       = b.severity;
        this.title          = b.title;
        this.detail         = b.detail;
        this.observedValue  = b.observedValue;
        this.thresholdValue = b.thresholdValue;
        this.unit           = b.unit;
        this.timestampMs    = b.timestampMs;
        this.latitude       = b.latitude;
        this.longitude      = b.longitude;
    }

    // ── Getters ──────────────────────────────────────────────────────────────
    public Category  getCategory()       { return category; }
    public Severity  getSeverity()       { return severity; }
    public String    getTitle()          { return title; }
    public String    getDetail()         { return detail; }
    public double    getObservedValue()  { return observedValue; }
    public double    getThresholdValue() { return thresholdValue; }
    public String    getUnit()           { return unit; }
    public long      getTimestampMs()    { return timestampMs; }
    public double    getLatitude()       { return latitude; }
    public double    getLongitude()      { return longitude; }

    /** Human-readable summary, e.g. "WARNING: Wind 18.3 m/s > 15.0 m/s threshold" */
    public String getSummary() {
        return String.format("%s: %s %.1f %s (threshold: %.1f %s)",
                severity.name(), category.name().toLowerCase(),
                observedValue, unit, thresholdValue, unit);
    }

    // ── Builder ──────────────────────────────────────────────────────────────
    public static class Builder {
        private Category  category;
        private Severity  severity     = Severity.WARNING;
        private String    title        = "";
        private String    detail       = "";
        private double    observedValue;
        private double    thresholdValue;
        private String    unit         = "";
        private long      timestampMs  = System.currentTimeMillis();
        private double    latitude;
        private double    longitude;

        public Builder(Category category) { this.category = category; }

        public Builder severity(Severity v)       { severity = v;       return this; }
        public Builder title(String v)            { title = v;          return this; }
        public Builder detail(String v)           { detail = v;         return this; }
        public Builder observedValue(double v)    { observedValue = v;  return this; }
        public Builder thresholdValue(double v)   { thresholdValue = v; return this; }
        public Builder unit(String v)             { unit = v;           return this; }
        public Builder timestampMs(long v)        { timestampMs = v;    return this; }
        public Builder latitude(double v)         { latitude = v;       return this; }
        public Builder longitude(double v)        { longitude = v;      return this; }

        public WeatherAlert build() { return new WeatherAlert(this); }
    }
}
