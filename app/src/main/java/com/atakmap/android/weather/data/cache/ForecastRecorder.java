package com.atakmap.android.weather.data.cache;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Records weather forecast snapshots to a local SQLite database for
 * historical comparison and CSV export.
 *
 * <p>Sprint 12 (S12.2): Each successful weather fetch can be persisted
 * as a snapshot. The recorder supports querying history by location and
 * time range, exporting to CSV, and purging old records.</p>
 */
public class ForecastRecorder extends SQLiteOpenHelper {

    private static final String TAG = "ForecastRecorder";
    private static final String DB_NAME = "forecast_history.db";
    private static final int DB_VERSION = 1;

    private static final String TABLE = "weather_snapshots";

    // ── Column names ─────────────────────────────────────────────────────────
    private static final String COL_ID          = "id";
    private static final String COL_TIMESTAMP   = "timestamp";
    private static final String COL_LAT         = "lat";
    private static final String COL_LON         = "lon";
    private static final String COL_SOURCE_ID   = "source_id";
    private static final String COL_TEMP        = "temp";
    private static final String COL_HUMIDITY    = "humidity";
    private static final String COL_WIND_SPEED  = "wind_speed";
    private static final String COL_WIND_DIR    = "wind_dir";
    private static final String COL_PRESSURE    = "pressure";
    private static final String COL_VISIBILITY  = "visibility";
    private static final String COL_WEATHER_CODE = "weather_code";
    private static final String COL_TACTICAL    = "tactical";

    private static ForecastRecorder instance;

    /**
     * Get or create the singleton ForecastRecorder instance.
     */
    public static synchronized ForecastRecorder getInstance(Context context) {
        if (instance == null) {
            instance = new ForecastRecorder(context.getApplicationContext());
        }
        return instance;
    }

    private ForecastRecorder(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_TIMESTAMP + " INTEGER NOT NULL, "
                + COL_LAT + " REAL NOT NULL, "
                + COL_LON + " REAL NOT NULL, "
                + COL_SOURCE_ID + " TEXT, "
                + COL_TEMP + " REAL, "
                + COL_HUMIDITY + " REAL, "
                + COL_WIND_SPEED + " REAL, "
                + COL_WIND_DIR + " REAL, "
                + COL_PRESSURE + " REAL, "
                + COL_VISIBILITY + " REAL, "
                + COL_WEATHER_CODE + " INTEGER, "
                + COL_TACTICAL + " TEXT"
                + ")");

        // Index for location + time range queries
        db.execSQL("CREATE INDEX idx_snapshots_loc_time ON " + TABLE
                + " (" + COL_LAT + ", " + COL_LON + ", " + COL_TIMESTAMP + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Future migrations go here
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    // ── Recording ────────────────────────────────────────────────────────────

    /**
     * Record a weather snapshot. Called on each successful fetch.
     *
     * @param weather  the current weather model
     * @param sourceId the weather source identifier
     * @param lat      latitude of the observation
     * @param lon      longitude of the observation
     */
    public void recordSnapshot(WeatherModel weather, String sourceId,
                                double lat, double lon) {
        if (weather == null) return;

        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put(COL_TIMESTAMP, System.currentTimeMillis());
            cv.put(COL_LAT, lat);
            cv.put(COL_LON, lon);
            cv.put(COL_SOURCE_ID, sourceId != null ? sourceId : "unknown");
            cv.put(COL_TEMP, weather.getTemperatureMax());
            cv.put(COL_HUMIDITY, weather.getHumidity());
            cv.put(COL_WIND_SPEED, weather.getWindSpeed());
            cv.put(COL_WIND_DIR, weather.getWindDirection());
            cv.put(COL_PRESSURE, weather.getPressure());
            cv.put(COL_VISIBILITY, weather.getVisibility());
            cv.put(COL_WEATHER_CODE, weather.getWeatherCode());
            cv.put(COL_TACTICAL, weather.tacticalCondition());

            db.insert(TABLE, null, cv);
            Log.d(TAG, "Recorded snapshot at " + lat + ", " + lon);
        } catch (Exception e) {
            Log.e(TAG, "Failed to record snapshot", e);
        }
    }

    // ── Query ────────────────────────────────────────────────────────────────

    /**
     * Get recorded history for a location within a time range.
     * Uses a bounding box of ~0.01 degrees (~1km) around the target point.
     *
     * @param lat     target latitude
     * @param lon     target longitude
     * @param startMs start of time range (epoch ms)
     * @param endMs   end of time range (epoch ms)
     * @return list of snapshots, ordered by timestamp ascending
     */
    public List<WeatherSnapshot> getHistory(double lat, double lon,
                                              long startMs, long endMs) {
        List<WeatherSnapshot> result = new ArrayList<>();
        double tolerance = 0.01; // ~1km

        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor cursor = db.query(TABLE, null,
                    COL_LAT + " BETWEEN ? AND ? AND "
                            + COL_LON + " BETWEEN ? AND ? AND "
                            + COL_TIMESTAMP + " BETWEEN ? AND ?",
                    new String[]{
                            String.valueOf(lat - tolerance),
                            String.valueOf(lat + tolerance),
                            String.valueOf(lon - tolerance),
                            String.valueOf(lon + tolerance),
                            String.valueOf(startMs),
                            String.valueOf(endMs)
                    },
                    null, null, COL_TIMESTAMP + " ASC");

            while (cursor.moveToNext()) {
                result.add(cursorToSnapshot(cursor));
            }
            cursor.close();
        } catch (Exception e) {
            Log.e(TAG, "getHistory failed", e);
        }

        return result;
    }

    // ── Export ────────────────────────────────────────────────────────────────

    /**
     * Export history as a CSV string.
     *
     * @param lat     target latitude
     * @param lon     target longitude
     * @param startMs start of time range (epoch ms)
     * @param endMs   end of time range (epoch ms)
     * @return CSV string with header row
     */
    public String exportCsv(double lat, double lon, long startMs, long endMs) {
        List<WeatherSnapshot> history = getHistory(lat, lon, startMs, endMs);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

        StringBuilder csv = new StringBuilder();
        csv.append("timestamp,datetime,lat,lon,source_id,temp_c,humidity_pct,")
           .append("wind_speed_ms,wind_dir_deg,pressure_hpa,visibility_m,")
           .append("weather_code,tactical\n");

        for (WeatherSnapshot s : history) {
            csv.append(s.timestamp).append(',');
            csv.append(sdf.format(new Date(s.timestamp))).append(',');
            csv.append(String.format(Locale.US, "%.6f", s.lat)).append(',');
            csv.append(String.format(Locale.US, "%.6f", s.lon)).append(',');
            csv.append(escapeCsv(s.sourceId)).append(',');
            csv.append(String.format(Locale.US, "%.1f", s.temp)).append(',');
            csv.append(String.format(Locale.US, "%.0f", s.humidity)).append(',');
            csv.append(String.format(Locale.US, "%.1f", s.windSpeed)).append(',');
            csv.append(String.format(Locale.US, "%.0f", s.windDir)).append(',');
            csv.append(String.format(Locale.US, "%.1f", s.pressure)).append(',');
            csv.append(String.format(Locale.US, "%.0f", s.visibility)).append(',');
            csv.append(s.weatherCode).append(',');
            csv.append(s.tacticalCondition).append('\n');
        }

        return csv.toString();
    }

    // ── Maintenance ──────────────────────────────────────────────────────────

    /**
     * Delete history older than the specified number of days.
     *
     * @param days records older than this many days will be deleted
     */
    public void purgeOlderThan(int days) {
        long cutoffMs = System.currentTimeMillis() - (long) days * 24 * 60 * 60 * 1000;
        try {
            SQLiteDatabase db = getWritableDatabase();
            int deleted = db.delete(TABLE, COL_TIMESTAMP + " < ?",
                    new String[]{String.valueOf(cutoffMs)});
            Log.d(TAG, "Purged " + deleted + " records older than " + days + " days");
        } catch (Exception e) {
            Log.e(TAG, "purgeOlderThan failed", e);
        }
    }

    /**
     * Get total record count and DB size.
     *
     * @return recorder statistics
     */
    public RecorderStats getStats() {
        RecorderStats stats = new RecorderStats();
        try {
            SQLiteDatabase db = getReadableDatabase();

            // Record count
            Cursor countCursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
            if (countCursor.moveToFirst()) {
                stats.recordCount = countCursor.getInt(0);
            }
            countCursor.close();

            // Oldest record
            Cursor oldestCursor = db.rawQuery(
                    "SELECT MIN(" + COL_TIMESTAMP + ") FROM " + TABLE, null);
            if (oldestCursor.moveToFirst() && !oldestCursor.isNull(0)) {
                long oldestMs = oldestCursor.getLong(0);
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
                stats.oldestRecord = sdf.format(new Date(oldestMs));
            } else {
                stats.oldestRecord = "None";
            }
            oldestCursor.close();

            // DB file size
            String dbPath = db.getPath();
            if (dbPath != null) {
                File dbFile = new File(dbPath);
                stats.dbSizeBytes = dbFile.length();
            }
        } catch (Exception e) {
            Log.e(TAG, "getStats failed", e);
        }
        return stats;
    }

    // ── Inner classes ────────────────────────────────────────────────────────

    /**
     * A recorded weather snapshot at a point in time.
     */
    public static class WeatherSnapshot {
        public long timestamp;
        public double lat;
        public double lon;
        public String sourceId;
        public double temp;
        public double humidity;
        public double windSpeed;
        public double windDir;
        public double pressure;
        public double visibility;
        public int weatherCode;
        public String tacticalCondition;
    }

    /**
     * Statistics about the forecast recorder database.
     */
    public static class RecorderStats {
        public int recordCount;
        public long dbSizeBytes;
        public String oldestRecord = "None";

        /**
         * @return human-readable DB size string
         */
        public String formattedSize() {
            if (dbSizeBytes < 1024) return dbSizeBytes + " B";
            if (dbSizeBytes < 1024 * 1024)
                return String.format(Locale.US, "%.1f KB", dbSizeBytes / 1024.0);
            return String.format(Locale.US, "%.1f MB", dbSizeBytes / (1024.0 * 1024.0));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private WeatherSnapshot cursorToSnapshot(Cursor c) {
        WeatherSnapshot s = new WeatherSnapshot();
        s.timestamp   = c.getLong(c.getColumnIndexOrThrow(COL_TIMESTAMP));
        s.lat         = c.getDouble(c.getColumnIndexOrThrow(COL_LAT));
        s.lon         = c.getDouble(c.getColumnIndexOrThrow(COL_LON));
        s.sourceId    = c.getString(c.getColumnIndexOrThrow(COL_SOURCE_ID));
        s.temp        = c.getDouble(c.getColumnIndexOrThrow(COL_TEMP));
        s.humidity    = c.getDouble(c.getColumnIndexOrThrow(COL_HUMIDITY));
        s.windSpeed   = c.getDouble(c.getColumnIndexOrThrow(COL_WIND_SPEED));
        s.windDir     = c.getDouble(c.getColumnIndexOrThrow(COL_WIND_DIR));
        s.pressure    = c.getDouble(c.getColumnIndexOrThrow(COL_PRESSURE));
        s.visibility  = c.getDouble(c.getColumnIndexOrThrow(COL_VISIBILITY));
        s.weatherCode = c.getInt(c.getColumnIndexOrThrow(COL_WEATHER_CODE));
        s.tacticalCondition = c.getString(c.getColumnIndexOrThrow(COL_TACTICAL));
        return s;
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
