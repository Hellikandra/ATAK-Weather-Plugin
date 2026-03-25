package com.atakmap.android.weather.overlay.heatmap;

/**
 * Specification for a rectangular grid of sample points covering a map viewport.
 *
 * <p>Produced by {@link HeatmapGridCalculator#computeGrid} and consumed by
 * {@link HeatmapBatchFetcher} (to build the batch API URL) and
 * {@link HeatmapRenderer} (to map pixel positions to grid nodes).</p>
 *
 * <p>The grid is defined by evenly-spaced latitude and longitude arrays
 * within the bounding box. {@code latitudes[0]} is the northernmost row,
 * {@code longitudes[0]} is the westernmost column.</p>
 */
public class GridSpec {

    private final int rows;
    private final int cols;
    private final double spacingKm;
    private final double[] latitudes;    // length = rows, north→south
    private final double[] longitudes;   // length = cols, west→east
    private final double north;
    private final double south;
    private final double east;
    private final double west;
    private final boolean beyondResolution; // true if zoomed beyond model grid
    private final int totalPoints;

    /**
     * @param rows              number of latitude rows
     * @param cols              number of longitude columns
     * @param spacingKm         spacing between adjacent grid points in km
     * @param latitudes         latitude array (length = rows), north to south
     * @param longitudes        longitude array (length = cols), west to east
     * @param north             northern bound of the viewport
     * @param south             southern bound of the viewport
     * @param east              eastern bound of the viewport
     * @param west              western bound of the viewport
     * @param beyondResolution  true if the grid spacing is finer than the model resolution
     * @param totalPoints       rows * cols
     */
    public GridSpec(int rows, int cols, double spacingKm,
                    double[] latitudes, double[] longitudes,
                    double north, double south, double east, double west,
                    boolean beyondResolution, int totalPoints) {
        this.rows = rows;
        this.cols = cols;
        this.spacingKm = spacingKm;
        this.latitudes = latitudes;
        this.longitudes = longitudes;
        this.north = north;
        this.south = south;
        this.east = east;
        this.west = west;
        this.beyondResolution = beyondResolution;
        this.totalPoints = totalPoints;
    }

    public int getRows() { return rows; }
    public int getCols() { return cols; }
    public double getSpacingKm() { return spacingKm; }
    public double[] getLatitudes() { return latitudes; }
    public double[] getLongitudes() { return longitudes; }
    public double getNorth() { return north; }
    public double getSouth() { return south; }
    public double getEast() { return east; }
    public double getWest() { return west; }
    public boolean isBeyondResolution() { return beyondResolution; }
    public int getTotalPoints() { return totalPoints; }

    /**
     * Build a comma-separated latitude string for the batch API.
     * Open-Meteo accepts: {@code latitude=lat1,lat2,...}
     */
    public String buildLatitudeParam() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (sb.length() > 0) sb.append(',');
                sb.append(String.format(java.util.Locale.US, "%.4f", latitudes[r]));
            }
        }
        return sb.toString();
    }

    /**
     * Build a comma-separated longitude string for the batch API.
     * Open-Meteo accepts: {@code longitude=lon1,lon2,...}
     */
    public String buildLongitudeParam() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (sb.length() > 0) sb.append(',');
                sb.append(String.format(java.util.Locale.US, "%.4f", longitudes[c]));
            }
        }
        return sb.toString();
    }
}
