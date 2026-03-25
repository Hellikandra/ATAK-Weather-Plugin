package com.atakmap.android.weather.overlay.heatmap;

/**
 * Computes a {@link GridSpec} for the heatmap overlay given map viewport bounds
 * and weather model metadata.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Calculate viewport extent in km (approximate Mercator).</li>
 *   <li>Choose grid density based on extent: wider views use coarser grids.</li>
 *   <li>Clamp spacing to the model's accuracy radius.</li>
 *   <li>Flag {@code beyondResolution} if spacing &lt; model grid resolution.</li>
 *   <li>Clamp total points to 900 (within Open-Meteo 1000 batch limit).</li>
 *   <li>Generate evenly-spaced lat/lon arrays.</li>
 * </ol>
 */
public final class HeatmapGridCalculator {

    /** Absolute cap on grid points to stay within Open-Meteo batch limit (1000). */
    private static final int MAX_POINTS = 900;

    /** Approximate km per degree of latitude. */
    private static final double KM_PER_DEG_LAT = 111.0;

    private HeatmapGridCalculator() { /* non-instantiable */ }

    /**
     * Compute a grid specification given map viewport and model info.
     *
     * @param north             northern latitude bound
     * @param south             southern latitude bound
     * @param east              eastern longitude bound
     * @param west              western longitude bound
     * @param modelResolutionKm model grid resolution in km (e.g. 13 for GFS)
     * @param accuracyRadiusKm  minimum meaningful spacing in km
     * @return GridSpec with lat/lon arrays, clamped to 900 max points
     */
    public static GridSpec computeGrid(double north, double south,
                                        double east, double west,
                                        double modelResolutionKm,
                                        double accuracyRadiusKm) {
        // 1. Calculate viewport size in km
        double latExtentDeg = Math.abs(north - south);
        double lonExtentDeg = Math.abs(east - west);
        double midLat = (north + south) / 2.0;
        double kmPerDegLon = KM_PER_DEG_LAT * Math.cos(Math.toRadians(midLat));

        double heightKm = latExtentDeg * KM_PER_DEG_LAT;
        double widthKm  = lonExtentDeg * kmPerDegLon;
        double extentKm = Math.max(widthKm, heightKm);

        // 2. Choose grid density based on viewport extent
        int targetDim;
        if (extentKm > 500) {
            targetDim = 10;
        } else if (extentKm > 100) {
            targetDim = 15;
        } else if (extentKm > 30) {
            targetDim = 20;
        } else {
            targetDim = 20;
        }

        // Calculate initial spacing in km
        double spacingKm = extentKm / targetDim;

        // 3. Clamp spacing to accuracyRadiusKm minimum
        if (spacingKm < accuracyRadiusKm) {
            spacingKm = accuracyRadiusKm;
        }

        // 4. Set beyondResolution flag
        boolean beyondResolution = spacingKm < modelResolutionKm;

        // Calculate rows and cols from spacing
        int rows = Math.max(2, (int) Math.ceil(heightKm / spacingKm) + 1);
        int cols = Math.max(2, (int) Math.ceil(widthKm / spacingKm) + 1);

        // 5. Clamp total to MAX_POINTS
        while (rows * cols > MAX_POINTS) {
            // Reduce the larger dimension first
            if (rows >= cols) {
                rows--;
            } else {
                cols--;
            }
            if (rows < 2) rows = 2;
            if (cols < 2) cols = 2;
        }

        // Recalculate actual spacing
        double actualSpacingLatKm = heightKm / Math.max(1, rows - 1);
        double actualSpacingLonKm = widthKm / Math.max(1, cols - 1);
        double actualSpacingKm = Math.max(actualSpacingLatKm, actualSpacingLonKm);

        // 6. Generate lat/lon arrays evenly spaced within bounds
        double[] latitudes = new double[rows];
        double[] longitudes = new double[cols];

        double latStep = (rows > 1) ? latExtentDeg / (rows - 1) : 0;
        double lonStep = (cols > 1) ? lonExtentDeg / (cols - 1) : 0;

        for (int r = 0; r < rows; r++) {
            latitudes[r] = north - r * latStep;  // north to south
        }
        for (int c = 0; c < cols; c++) {
            longitudes[c] = west + c * lonStep;   // west to east
        }

        return new GridSpec(rows, cols, actualSpacingKm,
                latitudes, longitudes,
                north, south, east, west,
                beyondResolution, rows * cols);
    }
}
