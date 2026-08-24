package com.atakmap.android.weather.overlay.marine;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.atakmap.coremap.log.Log;

import java.io.InputStream;

/**
 * Global land/water mask loaded from a bundled asset PNG.
 *
 * <p>The asset {@code land_mask.png} is a 1-bit (black/white) PNG where:
 * <ul>
 *   <li>Black (0) = land</li>
 *   <li>White (255) = water</li>
 * </ul>
 *
 * <p>Resolution: 3600×1800 (0.1° per pixel ≈ 11km at equator).
 * File size: ~50-100KB PNG compressed.</p>
 *
 * <p>The mask covers -180°W to +180°E longitude and -90°S to +90°N latitude.
 * Pixel (0,0) = top-left = (90°N, 180°W). Standard equirectangular projection.</p>
 *
 * <p>If the asset is not found, falls back to a hardcoded low-resolution
 * continental outline (360×180 = 1° per pixel).</p>
 */
public class GlobalLandMask {

    private static final String TAG = "GlobalLandMask";
    private static final String ASSET_NAME = "land_mask.png";

    // Singleton instance
    private static GlobalLandMask instance;

    private final int width;
    private final int height;
    /** Flat byte array: 1=water, 0=land. Row-major [y*width + x]. Single allocation. */
    private final byte[] waterFlat;

    private GlobalLandMask(int width, int height, byte[] waterFlat) {
        this.width = width;
        this.height = height;
        this.waterFlat = waterFlat;
    }

    /**
     * Load the global land mask. Tries bundled PNG asset first,
     * falls back to hardcoded low-res mask.
     */
    public static synchronized GlobalLandMask getInstance(Context context) {
        if (instance != null) return instance;

        Log.d(TAG, "Loading global land mask (context=" + context.getClass().getSimpleName() + ")...");

        // Try loading from bundled PNG asset
        instance = loadFromAsset(context);
        if (instance != null) {
            // Verify a known point
            boolean ukIsLand = !instance.isWater(51.5, -0.1);
            boolean channelIsWater = instance.isWater(50.5, 1.0);
            Log.d(TAG, "Loaded from asset: " + instance.width + "×" + instance.height
                    + " | UK=land:" + ukIsLand + " Channel=water:" + channelIsWater);
            return instance;
        }

        // Fallback: generate low-res mask from hardcoded continental outlines
        Log.w(TAG, "Asset 'land_mask.png' not found — using fallback continental mask");
        instance = generateFallbackMask();
        return instance;
    }

    /**
     * Check if a geographic point is water.
     *
     * @param lat latitude (-90 to +90)
     * @param lon longitude (-180 to +180)
     * @return true if water, false if land
     */
    public boolean isWater(double lat, double lon) {
        while (lon > 180) lon -= 360;
        while (lon < -180) lon += 360;

        int x = (int) ((lon + 180.0) / 360.0 * width);
        int y = (int) ((90.0 - lat) / 180.0 * height);
        x = Math.max(0, Math.min(width - 1, x));
        y = Math.max(0, Math.min(height - 1, y));

        return waterFlat[y * width + x] == 1;
    }

    /**
     * Build a CoastlineMask for the given bounds using this global mask.
     * Instant — no DTED queries, no API data needed.
     */
    public CoastlineMask buildMask(double north, double south,
                                    double west, double east, int size) {
        long t0 = System.currentTimeMillis();

        boolean[][] maskWater = new boolean[size][size];
        byte[] flat = new byte[size * size];
        int[] pixels = new int[size * size];
        int waterCount = 0, landCount = 0;

        double latStep = (north - south) / Math.max(1, size - 1);
        double lonStep = (east - west) / Math.max(1, size - 1);

        for (int my = 0; my < size; my++) {
            double lat = north - my * latStep;
            for (int mx = 0; mx < size; mx++) {
                double lon = west + mx * lonStep;
                int idx = my * size + mx;

                boolean isW = isWater(lat, lon);
                maskWater[my][mx] = isW;
                flat[idx] = isW ? (byte) 1 : (byte) 0;
                pixels[idx] = isW ? 0xFFFFFFFF : 0x00000000;

                if (isW) waterCount++;
                else landCount++;
            }
        }

        Bitmap clip = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        clip.setPixels(pixels, 0, size, 0, 0, size, size);

        long dt = System.currentTimeMillis() - t0;
        Log.d(TAG, String.format("Mask from global: %dx%d, water=%d land=%d (%.0f%%), %dms",
                size, size, waterCount, landCount,
                100.0 * waterCount / (size * size), dt));

        return new CoastlineMask(north, south, west, east,
                size, size, maskWater, clip, flat,
                waterCount, landCount, dt);
    }

    // ── Asset loader ────────────────────────────────────────────────────

    private static GlobalLandMask loadFromAsset(Context context) {
        try {
            // Try plugin context first, then app context
            InputStream is = null;
            try {
                is = context.getAssets().open(ASSET_NAME);
            } catch (Exception e) {
                // Plugin context may not have assets — try app context
                Context appCtx = context.getApplicationContext();
                if (appCtx != null) {
                    try { is = appCtx.getAssets().open(ASSET_NAME); } catch (Exception e2) {}
                }
            }
            if (is == null) return null;

            // Subsample on decode to reduce memory.
            // PNG is 7200×3600. With inSampleSize=4, loads as 1800×900 (~3MB bitmap).
            // Resolution: 0.2° per pixel ≈ 22km — adequate for 128×128 viewport masks.
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 4; // 7200/4=1800, 3600/4=900
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
            is.close();
            if (bmp == null) {
                Log.e(TAG, "BitmapFactory.decodeStream returned null");
                return null;
            }

            int w = bmp.getWidth();
            int h = bmp.getHeight();
            Log.d(TAG, "Decoded bitmap: " + w + "×" + h
                    + " (" + (w * h * 2 / 1024) + " KB in memory)");

            // Read pixels into flat byte array (single allocation, GC-friendly)
            int[] rowPixels = new int[w];
            byte[] waterFlat = new byte[w * h]; // 1=water, 0=land

            for (int y = 0; y < h; y++) {
                bmp.getPixels(rowPixels, 0, w, 0, y, w, 1);
                int rowOffset = y * w;
                for (int x = 0; x < w; x++) {
                    int pixel = rowPixels[x];
                    // Android decodes grayscale PNG as ARGB. Green channel = brightness.
                    int brightness = (pixel >> 8) & 0xFF;
                    waterFlat[rowOffset + x] = (brightness > 128) ? (byte) 1 : (byte) 0;
                }
            }

            bmp.recycle();
            Log.d(TAG, "Water mask: " + w + "×" + h + " = " + (w * h / 1024) + "KB");
            return new GlobalLandMask(w, h, waterFlat);

        } catch (Exception e) {
            Log.e(TAG, "Failed to load asset", e);
            return null;
        }
    }

    // ── Fallback: hardcoded continental outlines at 2° resolution ─────

    /**
     * Generate a crude 180×90 (2° per pixel) mask from hardcoded bounding boxes
     * of major continents. This is only used if the PNG asset is missing.
     * Accuracy is very low — suitable only as a last resort.
     */
    private static GlobalLandMask generateFallbackMask() {
        int w = 180, h = 90; // 2° per pixel
        byte[] waterFlat = new byte[w * h];

        // Initialize all as water
        java.util.Arrays.fill(waterFlat, (byte) 1);

        // Mark major land masses as land (crude bounding boxes)
        markLand(waterFlat, w, h, 35, 71, -10, 40);    // Europe
        markLand(waterFlat, w, h, -35, 37, -20, 55);    // Africa
        markLand(waterFlat, w, h, 10, 75, 25, 145);     // Asia
        markLand(waterFlat, w, h, -50, -10, 110, 155);   // Australia
        markLand(waterFlat, w, h, 15, 72, -170, -50);    // North America
        markLand(waterFlat, w, h, -55, 15, -80, -35);    // South America
        markLand(waterFlat, w, h, -85, -60, -180, 180);  // Antarctica

        // Carve out major water bodies
        markWater(waterFlat, w, h, 25, 48, -100, -75);   // Gulf of Mexico
        markWater(waterFlat, w, h, 55, 75, 10, 70);      // North Sea / Baltic
        markWater(waterFlat, w, h, 30, 45, 25, 45);      // Mediterranean / Black Sea
        markWater(waterFlat, w, h, 10, 30, 30, 50);      // Red Sea / Persian Gulf
        markWater(waterFlat, w, h, 0, 15, 95, 110);      // Bay of Bengal
        markWater(waterFlat, w, h, -10, 10, 100, 130);   // Indonesian waters

        Log.d(TAG, "Fallback mask: " + w + "×" + h + " (2° resolution)");
        return new GlobalLandMask(w, h, waterFlat);
    }

    /** Mark a lat/lon bounding box as land in the flat mask. */
    private static void markLand(byte[] waterFlat, int w, int h,
                                  double sLat, double nLat, double wLon, double eLon) {
        for (int y = 0; y < h; y++) {
            double lat = 90.0 - (y + 0.5) * (180.0 / h);
            if (lat < sLat || lat > nLat) continue;
            for (int x = 0; x < w; x++) {
                double lon = -180.0 + (x + 0.5) * (360.0 / w);
                if (lon >= wLon && lon <= eLon) {
                    waterFlat[y * w + x] = 0; // land
                }
            }
        }
    }

    /** Mark a lat/lon bounding box as water (override land). */
    private static void markWater(byte[] waterFlat, int w, int h,
                                   double sLat, double nLat, double wLon, double eLon) {
        for (int y = 0; y < h; y++) {
            double lat = 90.0 - (y + 0.5) * (180.0 / h);
            if (lat < sLat || lat > nLat) continue;
            for (int x = 0; x < w; x++) {
                double lon = -180.0 + (x + 0.5) * (360.0 / w);
                if (lon >= wLon && lon <= eLon) {
                    waterFlat[y * w + x] = 1; // water
                }
            }
        }
    }
}
