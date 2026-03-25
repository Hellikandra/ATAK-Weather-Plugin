package com.atakmap.android.weather.data.remote.schema;

/**
 * Configuration for batch (multi-location) API requests.
 * Describes whether the source supports batching, and how to
 * format the latitude/longitude parameters.
 */
public class BatchConfig {

    private final boolean supported;
    private final int maxLocations;
    private final String latParam;
    private final String lonParam;
    private final String separator;

    public BatchConfig(boolean supported, int maxLocations,
                       String latParam, String lonParam, String separator) {
        this.supported = supported;
        this.maxLocations = maxLocations;
        this.latParam = latParam;
        this.lonParam = lonParam;
        this.separator = separator;
    }

    public boolean isSupported() { return supported; }
    public int getMaxLocations() { return maxLocations; }
    public String getLatParam() { return latParam; }
    public String getLonParam() { return lonParam; }
    public String getSeparator() { return separator; }
}
