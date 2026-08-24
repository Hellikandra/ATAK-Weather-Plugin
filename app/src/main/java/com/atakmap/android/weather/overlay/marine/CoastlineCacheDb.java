package com.atakmap.android.weather.overlay.marine;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.atakmap.coremap.log.Log;

/**
 * SQLite cache for coastline masks built from DTED/SRTM elevation data.
 *
 * <p>Each cached mask is keyed by a geo-tile address (rounded lat/lon bounds at
 * a specific resolution). On first query for an area, the mask is built from
 * DTED (~16ms for 128×128) and stored as a compressed byte[] BLOB. Subsequent
 * queries for the same tile load from DB in ~1ms.</p>
 *
 * <h3>Tile Key Design</h3>
 * <p>Bounds are rounded to 0.5° intervals to create stable tile keys that can
 * be reused across nearby viewports. A viewport shift of < 0.25° reuses the
 * same cached tile. This means ~720×360 = 259,200 possible global tiles at
 * 0.5° resolution — only the ones actually queried are stored.</p>
 *
 * <h3>Storage</h3>
 * <p>Each 128×128 mask = 16,384 bytes (1 byte per pixel). With SQLite overhead,
 * ~20KB per tile. 100 cached tiles ≈ 2MB — negligible on mobile storage.</p>
 *
 * <h3>Versioning</h3>
 * <p>If the user loads new DTED data (e.g., higher resolution SRTM1), the cache
 * can be cleared via Settings → Data Management → Clear Coastline Cache.</p>
 */
public class CoastlineCacheDb {

    private static final String TAG = "CoastlineCacheDb";
    private static final String DB_NAME = "coastline_cache.db";
    private static final int DB_VERSION = 1;

    private static final String TABLE = "coastline_masks";
    private static final String COL_TILE_KEY = "tile_key";      // "N50.0_S49.0_W5.0_E6.0_128"
    private static final String COL_MASK_DATA = "mask_data";     // byte[] BLOB (1=water, 0=land)
    private static final String COL_WIDTH = "width";
    private static final String COL_HEIGHT = "height";
    private static final String COL_NORTH = "north";
    private static final String COL_SOUTH = "south";
    private static final String COL_WEST = "west";
    private static final String COL_EAST = "east";
    private static final String COL_WATER_COUNT = "water_count";
    private static final String COL_LAND_COUNT = "land_count";
    private static final String COL_BUILD_TIME_MS = "build_time_ms";
    private static final String COL_CREATED_AT = "created_at";   // epoch millis

    /** Resolution for rounding bounds to tile keys (degrees). */
    private static final double TILE_SNAP = 0.5;

    private final DbHelper helper;

    public CoastlineCacheDb(Context context) {
        // Use host Activity context, not plugin context (avoids ENOENT crash)
        Context dbCtx = context.getApplicationContext();
        if (dbCtx == null) dbCtx = context;
        helper = new DbHelper(dbCtx);
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Look up a cached coastline mask that covers the given bounds.
     *
     * @return the cached CoastlineMask, or null if not in cache
     */
    public CoastlineMask retrieve(double north, double south,
                                   double west, double east, int size) {
        String key = tileKey(north, south, west, east, size);

        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            Cursor c = db.query(TABLE, null,
                    COL_TILE_KEY + "=?", new String[]{key},
                    null, null, null, "1");

            if (c != null && c.moveToFirst()) {
                byte[] maskData = c.getBlob(c.getColumnIndexOrThrow(COL_MASK_DATA));
                int w = c.getInt(c.getColumnIndexOrThrow(COL_WIDTH));
                int h = c.getInt(c.getColumnIndexOrThrow(COL_HEIGHT));
                double n = c.getDouble(c.getColumnIndexOrThrow(COL_NORTH));
                double s = c.getDouble(c.getColumnIndexOrThrow(COL_SOUTH));
                double ww = c.getDouble(c.getColumnIndexOrThrow(COL_WEST));
                double e = c.getDouble(c.getColumnIndexOrThrow(COL_EAST));
                int waterCount = c.getInt(c.getColumnIndexOrThrow(COL_WATER_COUNT));
                int landCount = c.getInt(c.getColumnIndexOrThrow(COL_LAND_COUNT));
                long buildTime = c.getLong(c.getColumnIndexOrThrow(COL_BUILD_TIME_MS));
                c.close();

                // Reconstruct CoastlineMask from cached data
                CoastlineMask mask = CoastlineMask.fromCached(
                        n, s, ww, e, w, h, maskData, waterCount, landCount, buildTime);
                Log.d(TAG, "Cache HIT: " + key + " (water=" + waterCount + ")");
                return mask;
            }
            if (c != null) c.close();
        } catch (Exception e) {
            Log.e(TAG, "Cache retrieve error", e);
        }

        Log.d(TAG, "Cache MISS: " + key);
        return null;
    }

    /**
     * Store a coastline mask in the cache.
     */
    public void store(CoastlineMask mask) {
        if (mask == null) return;

        String key = tileKey(mask.getNorth(), mask.getSouth(),
                mask.getWest(), mask.getEast(), mask.getWidth());

        try {
            SQLiteDatabase db = helper.getWritableDatabase();

            ContentValues cv = new ContentValues();
            cv.put(COL_TILE_KEY, key);
            cv.put(COL_MASK_DATA, mask.getFlatMask());
            cv.put(COL_WIDTH, mask.getWidth());
            cv.put(COL_HEIGHT, mask.getHeight());
            cv.put(COL_NORTH, mask.getNorth());
            cv.put(COL_SOUTH, mask.getSouth());
            cv.put(COL_WEST, mask.getWest());
            cv.put(COL_EAST, mask.getEast());
            cv.put(COL_WATER_COUNT, mask.getWaterCount());
            cv.put(COL_LAND_COUNT, mask.getLandCount());
            cv.put(COL_BUILD_TIME_MS, mask.getBuildTimeMs());
            cv.put(COL_CREATED_AT, System.currentTimeMillis());

            db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            Log.d(TAG, "Cache STORE: " + key);
        } catch (Exception e) {
            Log.e(TAG, "Cache store error", e);
        }
    }

    /**
     * Get the total number of cached tiles.
     */
    public int getCachedTileCount() {
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
            if (c != null && c.moveToFirst()) {
                int count = c.getInt(0);
                c.close();
                return count;
            }
            if (c != null) c.close();
        } catch (Exception e) {
            Log.e(TAG, "Count error", e);
        }
        return 0;
    }

    /**
     * Get approximate cache size in bytes.
     */
    public long getCacheSizeBytes() {
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            Cursor c = db.rawQuery(
                    "SELECT SUM(LENGTH(" + COL_MASK_DATA + ")) FROM " + TABLE, null);
            if (c != null && c.moveToFirst()) {
                long size = c.getLong(0);
                c.close();
                return size;
            }
            if (c != null) c.close();
        } catch (Exception e) {
            Log.e(TAG, "Size error", e);
        }
        return 0;
    }

    /**
     * Clear all cached coastline masks.
     */
    public void clearAll() {
        try {
            SQLiteDatabase db = helper.getWritableDatabase();
            db.delete(TABLE, null, null);
            Log.d(TAG, "Cache cleared");
        } catch (Exception e) {
            Log.e(TAG, "Clear error", e);
        }
    }

    public void close() {
        helper.close();
    }

    // ── Tile key ──────────────────────────────────────────────────────────

    /**
     * Create a stable tile key by rounding bounds to TILE_SNAP intervals.
     * This ensures nearby viewports reuse the same cached mask.
     */
    static String tileKey(double north, double south,
                           double west, double east, int size) {
        double n = Math.ceil(north / TILE_SNAP) * TILE_SNAP;
        double s = Math.floor(south / TILE_SNAP) * TILE_SNAP;
        double w = Math.floor(west / TILE_SNAP) * TILE_SNAP;
        double e = Math.ceil(east / TILE_SNAP) * TILE_SNAP;
        return String.format(java.util.Locale.US,
                "N%.1f_S%.1f_W%.1f_E%.1f_%d", n, s, w, e, size);
    }

    // ── SQLite helper ─────────────────────────────────────────────────────

    private static class DbHelper extends SQLiteOpenHelper {
        DbHelper(Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + COL_TILE_KEY + " TEXT PRIMARY KEY, "
                    + COL_MASK_DATA + " BLOB, "
                    + COL_WIDTH + " INTEGER, "
                    + COL_HEIGHT + " INTEGER, "
                    + COL_NORTH + " REAL, "
                    + COL_SOUTH + " REAL, "
                    + COL_WEST + " REAL, "
                    + COL_EAST + " REAL, "
                    + COL_WATER_COUNT + " INTEGER, "
                    + COL_LAND_COUNT + " INTEGER, "
                    + COL_BUILD_TIME_MS + " INTEGER, "
                    + COL_CREATED_AT + " INTEGER"
                    + ")");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE);
            onCreate(db);
        }
    }
}
