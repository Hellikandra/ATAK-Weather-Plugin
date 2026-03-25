package com.atakmap.android.weather.overlay.heatmap;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.atakmap.coremap.log.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SQLite-based cache for heatmap grid data.
 *
 * <p>Stores serialized {@code double[][]} grids keyed by source ID, parameter,
 * and hour index. Each entry includes bounding box coordinates and expiry time
 * for cache invalidation.</p>
 *
 * <h3>Table schema</h3>
 * <pre>
 *   CREATE TABLE heatmap_data (
 *     id           INTEGER PRIMARY KEY AUTOINCREMENT,
 *     source_id    TEXT NOT NULL,
 *     param_key    TEXT NOT NULL,
 *     hour_index   INTEGER NOT NULL,
 *     time_label   TEXT,
 *     grid_blob    BLOB NOT NULL,
 *     north        REAL NOT NULL,
 *     south        REAL NOT NULL,
 *     east         REAL NOT NULL,
 *     west         REAL NOT NULL,
 *     rows_count   INTEGER NOT NULL,
 *     cols_count   INTEGER NOT NULL,
 *     fetch_time   INTEGER NOT NULL,
 *     expiry_time  INTEGER NOT NULL,
 *     UNIQUE(source_id, param_key, hour_index) ON CONFLICT REPLACE
 *   )
 * </pre>
 */
public class HeatmapTileCache {

    private static final String TAG = "HeatmapTileCache";
    private static final String DB_NAME = "heatmap_cache.db";
    private static final int DB_VERSION = 1;
    private static final long DEFAULT_TTL_MS = 6L * 3600L * 1000L; // 6 hours for GFS

    private final CacheDbHelper dbHelper;

    public HeatmapTileCache(Context context) {
        this.dbHelper = new CacheDbHelper(context);
    }

    /**
     * Store a complete HeatmapDataSet, writing each parameter + hour as a row.
     *
     * @param sourceId the weather source identifier
     * @param dataSet  the data set to cache
     */
    public void store(String sourceId, HeatmapDataSet dataSet) {
        SQLiteDatabase db = null;
        try {
            db = dbHelper.getWritableDatabase();
            db.beginTransaction();

            GridSpec grid = dataSet.getGrid();
            long now = System.currentTimeMillis();
            long expiry = now + DEFAULT_TTL_MS;
            String[] timeLabels = dataSet.getTimeLabels();

            for (String paramKey : dataSet.getParameters()) {
                for (int h = 0; h < dataSet.getHoursCount(); h++) {
                    double[][] gridValues = dataSet.getGrid(paramKey, h);
                    if (gridValues == null) continue;

                    byte[] blob = serializeGrid(gridValues);
                    if (blob == null) continue;

                    ContentValues cv = new ContentValues();
                    cv.put("source_id", sourceId);
                    cv.put("param_key", paramKey);
                    cv.put("hour_index", h);
                    cv.put("time_label", h < timeLabels.length ? timeLabels[h] : "");
                    cv.put("grid_blob", blob);
                    cv.put("north", grid.getNorth());
                    cv.put("south", grid.getSouth());
                    cv.put("east", grid.getEast());
                    cv.put("west", grid.getWest());
                    cv.put("rows_count", grid.getRows());
                    cv.put("cols_count", grid.getCols());
                    cv.put("fetch_time", now);
                    cv.put("expiry_time", expiry);

                    db.insertWithOnConflict("heatmap_data", null, cv,
                            SQLiteDatabase.CONFLICT_REPLACE);
                }
            }
            db.setTransactionSuccessful();
        } catch (Exception e) {
            Log.e(TAG, "Store error", e);
        } finally {
            if (db != null) {
                try { db.endTransaction(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Retrieve cached data if it covers the requested bounds and is not expired.
     *
     * @param sourceId the source ID to query
     * @param n        northern bound
     * @param s        southern bound
     * @param e        eastern bound
     * @param w        western bound
     * @return reconstructed HeatmapDataSet, or null on cache miss
     */
    public HeatmapDataSet retrieve(String sourceId,
                                    double n, double s, double e, double w) {
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = dbHelper.getReadableDatabase();
            long now = System.currentTimeMillis();

            // Query for non-expired entries that cover the requested bounds
            cursor = db.rawQuery(
                    "SELECT param_key, hour_index, time_label, grid_blob, "
                            + "north, south, east, west, rows_count, cols_count, fetch_time "
                            + "FROM heatmap_data "
                            + "WHERE source_id = ? AND expiry_time > ? "
                            + "AND north >= ? AND south <= ? AND east >= ? AND west <= ? "
                            + "ORDER BY param_key, hour_index",
                    new String[]{
                            sourceId,
                            String.valueOf(now),
                            String.valueOf(n - 0.01),
                            String.valueOf(s + 0.01),
                            String.valueOf(e - 0.01),
                            String.valueOf(w + 0.01)
                    });

            if (cursor == null || !cursor.moveToFirst()) return null;

            // Reconstruct the data set from cached rows
            Map<String, Map<Integer, double[][]>> paramHourGrids = new LinkedHashMap<>();
            Map<Integer, String> hourTimeLabels = new LinkedHashMap<>();
            int cachedRows = 0, cachedCols = 0;
            double cachedNorth = 0, cachedSouth = 0, cachedEast = 0, cachedWest = 0;
            long fetchTime = 0;
            int maxHour = 0;

            do {
                String paramKey = cursor.getString(0);
                int hourIndex = cursor.getInt(1);
                String timeLabel = cursor.getString(2);
                byte[] blob = cursor.getBlob(3);
                cachedNorth = cursor.getDouble(4);
                cachedSouth = cursor.getDouble(5);
                cachedEast = cursor.getDouble(6);
                cachedWest = cursor.getDouble(7);
                cachedRows = cursor.getInt(8);
                cachedCols = cursor.getInt(9);
                fetchTime = cursor.getLong(10);

                double[][] grid = deserializeGrid(blob);
                if (grid == null) continue;

                Map<Integer, double[][]> hourMap = paramHourGrids.get(paramKey);
                if (hourMap == null) {
                    hourMap = new LinkedHashMap<>();
                    paramHourGrids.put(paramKey, hourMap);
                }
                hourMap.put(hourIndex, grid);
                hourTimeLabels.put(hourIndex, timeLabel);
                if (hourIndex > maxHour) maxHour = hourIndex;
            } while (cursor.moveToNext());

            if (paramHourGrids.isEmpty()) return null;

            // Build GridSpec (simplified — no spacing info from cache)
            int hoursCount = maxHour + 1;
            double[] lats = new double[cachedRows];
            double[] lons = new double[cachedCols];
            double latStep = cachedRows > 1
                    ? (cachedNorth - cachedSouth) / (cachedRows - 1) : 0;
            double lonStep = cachedCols > 1
                    ? (cachedEast - cachedWest) / (cachedCols - 1) : 0;
            for (int r = 0; r < cachedRows; r++) lats[r] = cachedNorth - r * latStep;
            for (int c = 0; c < cachedCols; c++) lons[c] = cachedWest + c * lonStep;

            GridSpec gridSpec = new GridSpec(cachedRows, cachedCols, 0,
                    lats, lons,
                    cachedNorth, cachedSouth, cachedEast, cachedWest,
                    false, cachedRows * cachedCols);

            // Convert to the 3D array format
            Map<String, double[][][]> data = new LinkedHashMap<>();
            for (Map.Entry<String, Map<Integer, double[][]>> entry : paramHourGrids.entrySet()) {
                double[][][] arr = new double[hoursCount][cachedRows][cachedCols];
                for (Map.Entry<Integer, double[][]> hourEntry : entry.getValue().entrySet()) {
                    arr[hourEntry.getKey()] = hourEntry.getValue();
                }
                data.put(entry.getKey(), arr);
            }

            String[] labels = new String[hoursCount];
            for (int h = 0; h < hoursCount; h++) {
                String label = hourTimeLabels.get(h);
                labels[h] = label != null ? label : "";
            }

            return new HeatmapDataSet(gridSpec, data, labels, hoursCount, fetchTime);

        } catch (Exception ex) {
            Log.e(TAG, "Retrieve error", ex);
            return null;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /** Clear all cached data. */
    public void clear() {
        try {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            db.delete("heatmap_data", null, null);
            Log.d(TAG, "Cache cleared");
        } catch (Exception e) {
            Log.e(TAG, "Clear error", e);
        }
    }

    /** Get approximate cache size in bytes. */
    public long getCacheSizeBytes() {
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = dbHelper.getReadableDatabase();
            cursor = db.rawQuery(
                    "SELECT SUM(LENGTH(grid_blob)) FROM heatmap_data", null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "getCacheSizeBytes error", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return 0;
    }

    /** Get human-readable cache size label. */
    public String getCacheSizeLabel() {
        long bytes = getCacheSizeBytes();
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US,
                "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US,
                "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** Dispose the cache (close database). */
    public void dispose() {
        try {
            dbHelper.close();
        } catch (Exception e) {
            Log.e(TAG, "Dispose error", e);
        }
    }

    // ── Serialization ────────────────────────────────────────────────────────

    /**
     * Serialize a double[][] grid to a byte array.
     * Format: [rows:int][cols:int][values:double...]
     */
    private static byte[] serializeGrid(double[][] grid) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            int rows = grid.length;
            int cols = rows > 0 ? grid[0].length : 0;
            dos.writeInt(rows);
            dos.writeInt(cols);
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    dos.writeDouble(grid[r][c]);
                }
            }
            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            Log.e(TAG, "Serialize error", e);
            return null;
        }
    }

    /**
     * Deserialize a byte array back to a double[][] grid.
     */
    private static double[][] deserializeGrid(byte[] blob) {
        if (blob == null || blob.length < 8) return null;
        try {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(blob));
            int rows = dis.readInt();
            int cols = dis.readInt();
            if (rows <= 0 || cols <= 0 || rows > 100 || cols > 100) return null;
            double[][] grid = new double[rows][cols];
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    grid[r][c] = dis.readDouble();
                }
            }
            return grid;
        } catch (IOException e) {
            Log.e(TAG, "Deserialize error", e);
            return null;
        }
    }

    // ── SQLiteOpenHelper ─────────────────────────────────────────────────────

    private static class CacheDbHelper extends SQLiteOpenHelper {

        CacheDbHelper(Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE heatmap_data ("
                    + "id           INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "source_id    TEXT NOT NULL, "
                    + "param_key    TEXT NOT NULL, "
                    + "hour_index   INTEGER NOT NULL, "
                    + "time_label   TEXT, "
                    + "grid_blob    BLOB NOT NULL, "
                    + "north        REAL NOT NULL, "
                    + "south        REAL NOT NULL, "
                    + "east         REAL NOT NULL, "
                    + "west         REAL NOT NULL, "
                    + "rows_count   INTEGER NOT NULL, "
                    + "cols_count   INTEGER NOT NULL, "
                    + "fetch_time   INTEGER NOT NULL, "
                    + "expiry_time  INTEGER NOT NULL, "
                    + "UNIQUE(source_id, param_key, hour_index) ON CONFLICT REPLACE"
                    + ")");

            db.execSQL("CREATE INDEX idx_heatmap_lookup "
                    + "ON heatmap_data(source_id, expiry_time)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS heatmap_data");
            onCreate(db);
        }
    }
}
