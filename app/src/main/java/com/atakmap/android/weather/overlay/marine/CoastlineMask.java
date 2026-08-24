package com.atakmap.android.weather.overlay.marine;

import android.content.Context;
import android.graphics.Bitmap;

import com.atakmap.coremap.log.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Binary land/water mask for marine overlay clipping.
 *
 * <h3>Strategy (v4 — Global Land Mask)</h3>
 * <p>Uses a pre-built global land/water mask from Natural Earth data
 * ({@link GlobalLandMask}). This provides accurate coastlines at ~11km
 * resolution worldwide, with zero DTED queries and zero API dependency.</p>
 *
 * <p>The mask is built by sampling the global mask at the requested
 * resolution for the viewport bounds. Typical build time: <5ms.</p>
 */
public class CoastlineMask {

    private static final String TAG = "CoastlineMask";

    private final double north, south, west, east;
    private final int width, height;
    private final boolean[][] waterMask;
    private final Bitmap clipBitmap;
    private final byte[] flatMask;
    private final int waterCount;
    private final int landCount;
    private final long buildTimeMs;

    /** Default mask resolution — 128×128 is now feasible (no DTED queries). */
    public static final int DEFAULT_SIZE = 128;

    CoastlineMask(double north, double south, double west, double east,
                  int width, int height,
                  boolean[][] waterMask, Bitmap clipBitmap, byte[] flatMask,
                  int waterCount, int landCount, long buildTimeMs) {
        this.north = north;
        this.south = south;
        this.west = west;
        this.east = east;
        this.width = width;
        this.height = height;
        this.waterMask = waterMask;
        this.clipBitmap = clipBitmap;
        this.flatMask = flatMask;
        this.waterCount = waterCount;
        this.landCount = landCount;
        this.buildTimeMs = buildTimeMs;
    }

    /**
     * Build a coastline mask using the global land mask.
     * Instant — no DTED queries, no API data needed.
     *
     * @param context  Android context (for loading asset on first call)
     * @param north    northern latitude bound
     * @param south    southern latitude bound
     * @param west     western longitude bound
     * @param east     eastern longitude bound
     * @param size     mask resolution (pixels per axis)
     * @return the coastline mask
     */
    public static CoastlineMask build(Context context,
                                       double north, double south,
                                       double west, double east, int size) {
        GlobalLandMask global = GlobalLandMask.getInstance(context);
        return global.buildMask(north, south, west, east, size);
    }

    /**
     * Legacy overload — delegates to context-based build.
     * Uses the marine data parameter for backward compatibility but ignores it.
     */
    public static CoastlineMask build(double north, double south,
                                       double west, double east, int size,
                                       com.atakmap.android.weather.overlay.heatmap.HeatmapDataSet marineData,
                                       int marineHour) {
        // Cannot build without context — return a blank water mask
        Log.w(TAG, "build() called without context — use build(context, ...) instead");
        return buildAllWater(north, south, west, east, size);
    }

    /** Simple overload for no-context case — treats everything as water. */
    public static CoastlineMask build(double north, double south,
                                       double west, double east, int size) {
        return buildAllWater(north, south, west, east, size);
    }

    /** Build a mask that treats everything as water (fallback). */
    private static CoastlineMask buildAllWater(double north, double south,
                                                double west, double east, int size) {
        boolean[][] water = new boolean[size][size];
        byte[] flat = new byte[size * size];
        int[] pixels = new int[size * size];
        for (int i = 0; i < size * size; i++) {
            flat[i] = 1;
            pixels[i] = 0xFFFFFFFF;
        }
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
                water[y][x] = true;

        Bitmap clip = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        clip.setPixels(pixels, 0, size, 0, 0, size, size);

        return new CoastlineMask(north, south, west, east,
                size, size, water, clip, flat,
                size * size, 0, 0);
    }

    /**
     * Reconstruct from cached DB data.
     */
    public static CoastlineMask fromCached(double north, double south, double west, double east,
                                            int width, int height, byte[] flatMask,
                                            int waterCount, int landCount, long buildTimeMs) {
        boolean[][] water = new boolean[height][width];
        int[] pixels = new int[height * width];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = y * width + x;
                boolean isWater = (idx < flatMask.length) && (flatMask[idx] == 1);
                water[y][x] = isWater;
                pixels[idx] = isWater ? 0xFFFFFFFF : 0x00000000;
            }
        }

        Bitmap clip = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        clip.setPixels(pixels, 0, width, 0, 0, width, height);

        return new CoastlineMask(north, south, west, east,
                width, height, water, clip, flatMask,
                waterCount, landCount, buildTimeMs);
    }

    // ── Accessors ───────────────────────────────────────────────────────

    public Bitmap getClipBitmap() { return clipBitmap; }

    public boolean isWater(double lat, double lon) {
        if (lat < south || lat > north || lon < west || lon > east) return false;
        int x = (int) ((lon - west) / (east - west) * (width - 1));
        int y = (int) ((north - lat) / (north - south) * (height - 1));
        x = Math.max(0, Math.min(width - 1, x));
        y = Math.max(0, Math.min(height - 1, y));
        return waterMask[y][x];
    }

    public byte[] getFlatMask() { return flatMask; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public double getNorth() { return north; }
    public double getSouth() { return south; }
    public double getWest() { return west; }
    public double getEast() { return east; }
    public int getWaterCount() { return waterCount; }
    public int getLandCount() { return landCount; }
    public float getWaterPercent() { return 100f * waterCount / (width * height); }
    public long getBuildTimeMs() { return buildTimeMs; }

    /**
     * Get coastline contour points for debug visualization.
     * Returns list of {lat, lon} at water/land boundary pixels.
     */
    public List<double[]> getContourPoints() {
        List<double[]> points = new ArrayList<>();
        double latStep = (north - south) / Math.max(1, height - 1);
        double lonStep = (east - west) / Math.max(1, width - 1);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean here = waterMask[y][x];
                boolean isEdge = false;
                if (x > 0 && waterMask[y][x - 1] != here) isEdge = true;
                if (x < width - 1 && waterMask[y][x + 1] != here) isEdge = true;
                if (y > 0 && waterMask[y - 1][x] != here) isEdge = true;
                if (y < height - 1 && waterMask[y + 1][x] != here) isEdge = true;

                if (isEdge) {
                    double lat = north - y * latStep;
                    double lon = west + x * lonStep;
                    points.add(new double[]{lat, lon});
                }
            }
        }
        return points;
    }

    public void recycle() {
        if (clipBitmap != null && !clipBitmap.isRecycled()) {
            clipBitmap.recycle();
        }
    }
}
