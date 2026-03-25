package com.atakmap.android.weather.overlay.heatmap;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Holds fetched grid data for all weather parameters and time steps.
 *
 * <p>Structure: for each parameter key (e.g. "temperature_2m"), stores a 3D array
 * {@code double[hour][row][col]} containing the weather values at each grid node
 * for each forecast hour.</p>
 *
 * <p>Created by {@link HeatmapBatchFetcher} and consumed by
 * {@link HeatmapOverlayManager} for rendering.</p>
 */
public class HeatmapDataSet {

    private final GridSpec grid;
    /** paramKey -> double[hour][row][col] */
    private final Map<String, double[][][]> data;
    private final String[] timeLabels;  // ISO time for each hour
    private final int hoursCount;       // typically 48
    private final long fetchTime;

    /**
     * @param grid       the grid specification used for fetching
     * @param data       map of parameter key to 3D value arrays [hour][row][col]
     * @param timeLabels ISO-8601 time strings for each hour index
     * @param hoursCount number of forecast hours
     * @param fetchTime  system time when data was fetched (millis)
     */
    public HeatmapDataSet(GridSpec grid,
                           Map<String, double[][][]> data,
                           String[] timeLabels,
                           int hoursCount,
                           long fetchTime) {
        this.grid = grid;
        this.data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
        this.timeLabels = timeLabels;
        this.hoursCount = hoursCount;
        this.fetchTime = fetchTime;
    }

    public GridSpec getGrid() { return grid; }
    public int getHoursCount() { return hoursCount; }
    public String[] getTimeLabels() { return timeLabels; }
    public long getFetchTime() { return fetchTime; }

    /**
     * Get grid values for a parameter at a specific hour index.
     *
     * @param paramKey  the parameter key (e.g. "temperature_2m")
     * @param hourIndex the hour index (0..hoursCount-1)
     * @return double[row][col] or null if not available
     */
    public double[][] getGrid(String paramKey, int hourIndex) {
        double[][][] paramData = data.get(paramKey);
        if (paramData == null || hourIndex < 0 || hourIndex >= paramData.length) {
            return null;
        }
        return paramData[hourIndex];
    }

    /** Get all available parameter keys. */
    public Set<String> getParameters() {
        return data.keySet();
    }

    /**
     * Check if the data covers given bounds (for cache hit check).
     * Returns true if the fetched grid fully encloses the requested area
     * with a small tolerance.
     */
    public boolean coversBounds(double n, double s, double e, double w) {
        double tolerance = 0.01; // ~1km tolerance
        return grid.getNorth() >= n - tolerance
                && grid.getSouth() <= s + tolerance
                && grid.getEast() >= e - tolerance
                && grid.getWest() <= w + tolerance;
    }
}
