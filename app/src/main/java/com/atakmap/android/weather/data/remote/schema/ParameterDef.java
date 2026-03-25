package com.atakmap.android.weather.data.remote.schema;

/**
 * A single selectable weather parameter within a category (hourly, daily, current).
 * Defines the API key, display label, unit string, and whether the parameter
 * is enabled by default.
 */
public class ParameterDef {

    private final String key;
    private final String label;
    private final String unit;
    private final boolean defaultOn;

    public ParameterDef(String key, String label, String unit, boolean defaultOn) {
        this.key = key;
        this.label = label;
        this.unit = unit;
        this.defaultOn = defaultOn;
    }

    public String getKey() { return key; }
    public String getLabel() { return label; }
    public String getUnit() { return unit; }
    public boolean isDefaultOn() { return defaultOn; }
}
