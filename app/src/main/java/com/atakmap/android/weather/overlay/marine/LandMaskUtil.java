package com.atakmap.android.weather.overlay.marine;

import com.atakmap.coremap.log.Log;

/**
 * Utility for masking land cells in marine data grids using DTED/SRTM elevation.
 *
 * <p>ATAK provides global elevation data via {@code ElevationManager}. Points with
 * elevation significantly above sea level (>10m) are considered land. The mask
 * sets land cells to {@code Double.NaN} which the heatmap renderer draws as transparent.</p>
 */
public final class LandMaskUtil {

    private static final String TAG = "LandMaskUtil";

    /** Elevation threshold (meters) above which a point is considered land. */
    private static final double LAND_THRESHOLD_M = 10.0;

    private LandMaskUtil() {}

    /**
     * Apply a land mask to a 2D grid by setting land cells to NaN.
     * Uses ATAK's ElevationManager (DTED/SRTM) to determine land vs water.
     *
     * @param grid    the data grid [row][col] — modified in place
     * @param north   northern latitude bound
     * @param south   southern latitude bound
     * @param west    western longitude bound
     * @param east    eastern longitude bound
     * @return number of cells masked as land
     */
    public static int applyLandMask(double[][] grid,
                                     double north, double south,
                                     double west, double east) {
        if (grid == null || grid.length == 0) return 0;

        int rows = grid.length;
        int cols = grid[0].length;
        double latStep = (rows > 1) ? (north - south) / (rows - 1) : 0;
        double lonStep = (cols > 1) ? (east - west) / (cols - 1) : 0;

        int masked = 0;
        for (int r = 0; r < rows; r++) {
            double lat = south + r * latStep;
            for (int c = 0; c < cols; c++) {
                // Skip already-NaN cells (API says no data)
                if (Double.isNaN(grid[r][c])) {
                    masked++;
                    continue;
                }

                double lon = west + c * lonStep;
                try {
                    double elev = com.atakmap.map.elevation.ElevationManager.getElevation(
                            lat, lon, null);
                    if (!Double.isNaN(elev) && elev > LAND_THRESHOLD_M) {
                        grid[r][c] = Double.NaN;
                        masked++;
                    }
                } catch (Exception ignored) {
                    // No DTED coverage — leave cell as-is
                }
            }
        }

        if (masked > 0) {
            Log.d(TAG, "Land mask: " + masked + "/" + (rows * cols) + " cells masked");
        }
        return masked;
    }

    /**
     * Check if a single point is on land using DTED/SRTM.
     *
     * @return true if elevation > threshold (land), false if water or no data
     */
    public static boolean isLand(double lat, double lon) {
        try {
            double elev = com.atakmap.map.elevation.ElevationManager.getElevation(
                    lat, lon, null);
            return !Double.isNaN(elev) && elev > LAND_THRESHOLD_M;
        } catch (Exception ignored) {
            return false;
        }
    }
}
