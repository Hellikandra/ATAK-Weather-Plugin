package com.atakmap.android.weather.overlay.heatmap;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Renders a 2D grid of weather values into an ARGB_8888 Bitmap using a
 * {@link ColourScale} for value-to-colour mapping.
 *
 * <h3>Rendering modes</h3>
 * <ul>
 *   <li><b>Normal:</b> bilinear interpolation between the four nearest grid
 *       nodes produces smooth colour gradients.</li>
 *   <li><b>Beyond-resolution:</b> nearest-neighbour (flat cells) when the
 *       grid spacing is finer than the model's actual resolution.</li>
 * </ul>
 *
 * <p>Output bitmap is capped at 512x512 pixels for performance on mobile GPUs.</p>
 */
public final class HeatmapRenderer {

    /** Maximum output bitmap dimension. */
    private static final int MAX_SIZE = 512;

    private HeatmapRenderer() { /* non-instantiable */ }

    /**
     * Render a 2D grid of values into an ARGB_8888 Bitmap.
     *
     * @param grid             double[row][col] of weather values
     * @param scale            colour scale for mapping values to ARGB
     * @param width            requested output width (clamped to MAX_SIZE)
     * @param height           requested output height (clamped to MAX_SIZE)
     * @param alpha            opacity 0.0-1.0
     * @param beyondResolution if true, use nearest-neighbour instead of bilinear
     * @return the rendered ARGB_8888 bitmap, or null if grid is invalid
     */
    public static Bitmap render(double[][] grid, ColourScale scale,
                                 int width, int height, float alpha,
                                 boolean beyondResolution) {
        if (grid == null || grid.length < 2 || grid[0].length < 2) {
            return null;
        }

        int rows = grid.length;
        int cols = grid[0].length;

        // Clamp output size
        width = Math.min(Math.max(1, width), MAX_SIZE);
        height = Math.min(Math.max(1, height), MAX_SIZE);

        // Alpha clamp
        int alphaByte = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[width * height];

        for (int py = 0; py < height; py++) {
            // Map pixel y to grid row (fractional)
            double gridRow = (double) py / (height - 1) * (rows - 1);

            for (int px = 0; px < width; px++) {
                // Map pixel x to grid column (fractional)
                double gridCol = (double) px / (width - 1) * (cols - 1);

                double value;
                if (beyondResolution) {
                    // Nearest-neighbour: flat cells
                    int r = (int) Math.round(gridRow);
                    int c = (int) Math.round(gridCol);
                    r = Math.max(0, Math.min(rows - 1, r));
                    c = Math.max(0, Math.min(cols - 1, c));
                    value = grid[r][c];
                } else {
                    // Bilinear interpolation
                    value = bilinear(grid, gridRow, gridCol, rows, cols);
                }

                int color = scale.getColor(value);

                // Apply alpha: blend the scale's alpha with the requested opacity
                int srcAlpha = Color.alpha(color);
                int finalAlpha = (srcAlpha * alphaByte) / 255;
                color = Color.argb(finalAlpha,
                        Color.red(color),
                        Color.green(color),
                        Color.blue(color));

                pixels[py * width + px] = color;
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    /**
     * Bilinear interpolation of a value from a 2D grid.
     *
     * @param grid    the 2D data array
     * @param row     fractional row position
     * @param col     fractional column position
     * @param rows    total number of rows
     * @param cols    total number of columns
     * @return interpolated value
     */
    private static double bilinear(double[][] grid, double row, double col,
                                    int rows, int cols) {
        int r0 = (int) Math.floor(row);
        int c0 = (int) Math.floor(col);
        int r1 = r0 + 1;
        int c1 = c0 + 1;

        // Clamp to grid bounds
        r0 = Math.max(0, Math.min(rows - 1, r0));
        r1 = Math.max(0, Math.min(rows - 1, r1));
        c0 = Math.max(0, Math.min(cols - 1, c0));
        c1 = Math.max(0, Math.min(cols - 1, c1));

        double dr = row - Math.floor(row); // fractional row offset
        double dc = col - Math.floor(col); // fractional col offset

        double v00 = grid[r0][c0];
        double v01 = grid[r0][c1];
        double v10 = grid[r1][c0];
        double v11 = grid[r1][c1];

        // Handle NaN: if any corner is NaN, use nearest valid value
        if (Double.isNaN(v00) || Double.isNaN(v01)
                || Double.isNaN(v10) || Double.isNaN(v11)) {
            return nearestValid(v00, v01, v10, v11, dr, dc);
        }

        // Standard bilinear interpolation
        double top    = v00 + (v01 - v00) * dc;
        double bottom = v10 + (v11 - v10) * dc;
        return top + (bottom - top) * dr;
    }

    /**
     * When some grid corners are NaN, find the nearest valid value.
     */
    private static double nearestValid(double v00, double v01,
                                        double v10, double v11,
                                        double dr, double dc) {
        // Weight by inverse distance from fractional position
        double bestDist = Double.MAX_VALUE;
        double bestVal = Double.NaN;

        double[][] candidates = {
                {0, 0, v00}, {0, 1, v01},
                {1, 0, v10}, {1, 1, v11}
        };
        for (double[] c : candidates) {
            if (!Double.isNaN(c[2])) {
                double dist = (c[0] - dr) * (c[0] - dr)
                        + (c[1] - dc) * (c[1] - dc);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestVal = c[2];
                }
            }
        }
        return bestVal;
    }
}
