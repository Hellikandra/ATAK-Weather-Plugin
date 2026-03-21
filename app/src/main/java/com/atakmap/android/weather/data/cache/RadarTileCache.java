package com.atakmap.android.weather.data.cache;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.atakmap.coremap.log.Log;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SQLite-backed L2 radar tile cache inspired by ATAK's {@code MBTilesContainer}
 * and {@code TileProxy} patterns from the TAK kernel.
 *
 * <h3>Design references (takkernel/engine)</h3>
 * <ul>
 *   <li>{@code tilematrix/TileProxy.java} — transparent cache wrapping client + container
 *       with geographic priority queue and per-tile expiration.</li>
 *   <li>{@code mbtiles/MBTilesContainer.java} — async-buffered SQLite tile writes using
 *       a {@code pendingTileWrites} map flushed by a background thread.</li>
 *   <li>{@code tilematrix/AbstractTileClient.java} — per-tile expiration offset
 *       (default 7 days for map tiles; we use 15 minutes for radar).</li>
 *   <li>{@code tilematrix/TileContainer.java} — {@code setTile(level,x,y,data,expiration)}
 *       contract with epoch-millisecond expiration per tile.</li>
 * </ul>
 *
 * <h3>Schema</h3>
 * <pre>
 *   radar_tiles (
 *     zoom        INTEGER,
 *     x           INTEGER,
 *     y           INTEGER,
 *     timestamp   INTEGER,     -- RainViewer frame timestamp (Unix seconds)
 *     tile_data   BLOB,        -- PNG bytes
 *     expiration  INTEGER,     -- epoch ms when this tile becomes stale
 *     inserted_at INTEGER,     -- epoch ms when written (for LRU eviction)
 *     PRIMARY KEY(zoom, x, y, timestamp)
 *   )
 * </pre>
 *
 * <h3>Write buffering</h3>
 * Tile writes are buffered in a {@link ConcurrentHashMap} and flushed to SQLite
 * in batches by a scheduled executor — same pattern as {@code MBTilesContainer}'s
 * async writer thread.  This avoids SQLite contention during rapid tile downloads.
 *
 * <h3>Thread safety</h3>
 * <ul>
 *   <li>{@link #getTile} and {@link #getStaleTile} are safe to call from any thread.</li>
 *   <li>{@link #putTile} is safe to call from download threads (buffered).</li>
 *   <li>{@link #flushPendingWrites()} is synchronized on {@code this}.</li>
 * </ul>
 *
 * <h3>Lifecycle</h3>
 * Created once by {@code WeatherMapComponent.onCreate()} and injected into
 * {@link com.atakmap.android.weather.overlay.radar.RadarOverlayManager}.
 * {@link #dispose()} must be called from {@code onDestroyImpl()}.
 */
public class RadarTileCache {

    private static final String TAG = "RadarTileCache";

    // ── Constants ──────────────────────────────────────────────────────────────

    private static final String DB_NAME    = "radar_tile_cache.db";
    private static final int    DB_VERSION = 1;

    /** Default expiration offset: 15 minutes (radar frames are short-lived). */
    private static final long DEFAULT_EXPIRATION_MS = 15L * 60L * 1000L;

    /** Default maximum cache size on disk: 50 MB. */
    private static final long DEFAULT_MAX_SIZE_BYTES = 50L * 1024L * 1024L;

    /** Flush buffered writes when this many tiles are pending. */
    private static final int FLUSH_THRESHOLD = 10;

    /** Periodic flush interval for the background writer (milliseconds). */
    private static final long FLUSH_INTERVAL_MS = 500L;

    // ── State ──────────────────────────────────────────────────────────────────

    private final SQLiteDatabase db;
    private final ConcurrentHashMap<String, PendingTile> pendingWrites = new ConcurrentHashMap<>();
    private final ScheduledExecutorService flushExecutor;

    private long expirationOffsetMs = DEFAULT_EXPIRATION_MS;
    private long maxCacheSizeBytes  = DEFAULT_MAX_SIZE_BYTES;

    /** Pending tile write entry — mirrors ATAK's MBTilesContainer.pendingTileWrites. */
    private static final class PendingTile {
        final int  zoom, x, y;
        final long timestamp;
        final byte[] data;

        PendingTile(int zoom, int x, int y, long timestamp, byte[] data) {
            this.zoom = zoom; this.x = x; this.y = y;
            this.timestamp = timestamp; this.data = data;
        }
    }

    // ── Constructor ────────────────────────────────────────────────────────────

    /**
     * Open or create the radar tile cache database.
     *
     * @param context host application context (use {@code mapView.getContext()},
     *                not the plugin context — see {@link WeatherDatabase} for rationale).
     */
    public RadarTileCache(Context context) {
        Context appCtx = context.getApplicationContext();
        if (appCtx == null) appCtx = context;
        DbHelper helper = new DbHelper(appCtx, DB_NAME, DB_VERSION);
        this.db = helper.getWritableDatabase();

        this.flushExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RadarTileCache-flush");
            t.setDaemon(true);
            return t;
        });
        this.flushExecutor.scheduleWithFixedDelay(
                this::flushPendingWrites,
                FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);

        Log.d(TAG, "RadarTileCache opened: " + DB_NAME);
    }

    // ── Configuration ──────────────────────────────────────────────────────────

    /** Set the per-tile expiration offset in milliseconds. Default: 15 min. */
    public void setExpirationOffset(long ms) { this.expirationOffsetMs = ms; }

    /** Set the maximum disk cache size in bytes. Default: 50 MB. */
    public void setMaxCacheSize(long bytes) { this.maxCacheSizeBytes = bytes; }

    // ── Read API ───────────────────────────────────────────────────────────────

    /**
     * Retrieve a fresh (non-expired) radar tile from the cache.
     *
     * <p>Mirrors ATAK's {@code TileProxy.getTileData()} pattern:
     * check expiration first, return null if expired or missing.</p>
     *
     * @return decoded Bitmap if cached and fresh; {@code null} otherwise.
     */
    public Bitmap getTile(int zoom, int x, int y, long timestamp) {
        // Check pending writes first (buffer acts as L1.5)
        String key = tileKey(zoom, x, y, timestamp);
        PendingTile pending = pendingWrites.get(key);
        if (pending != null) {
            return BitmapFactory.decodeByteArray(pending.data, 0, pending.data.length);
        }

        try (Cursor c = db.rawQuery(
                "SELECT tile_data, expiration FROM radar_tiles " +
                "WHERE zoom=? AND x=? AND y=? AND timestamp=?",
                new String[]{
                    String.valueOf(zoom), String.valueOf(x),
                    String.valueOf(y),    String.valueOf(timestamp)
                })) {
            if (c.moveToFirst()) {
                long expiration = c.getLong(1);
                if (System.currentTimeMillis() > expiration) return null; // expired
                byte[] data = c.getBlob(0);
                if (data == null || data.length == 0) return null;
                return BitmapFactory.decodeByteArray(data, 0, data.length);
            }
        } catch (Exception e) {
            Log.w(TAG, "getTile error: " + e.getMessage());
        }
        return null;
    }

    /**
     * Retrieve a tile even if expired — for offline/degraded-network fallback.
     *
     * <p>Mirrors ATAK's offline fallback pattern in {@code AbstractTileClient}:
     * when the network fetch fails, serve the expired cached tile rather than
     * showing nothing.</p>
     *
     * @return decoded Bitmap if any cached version exists; {@code null} if truly missing.
     */
    public Bitmap getStaleTile(int zoom, int x, int y, long timestamp) {
        // Check pending writes first
        String key = tileKey(zoom, x, y, timestamp);
        PendingTile pending = pendingWrites.get(key);
        if (pending != null) {
            return BitmapFactory.decodeByteArray(pending.data, 0, pending.data.length);
        }

        try (Cursor c = db.rawQuery(
                "SELECT tile_data FROM radar_tiles " +
                "WHERE zoom=? AND x=? AND y=? AND timestamp=?",
                new String[]{
                    String.valueOf(zoom), String.valueOf(x),
                    String.valueOf(y),    String.valueOf(timestamp)
                })) {
            if (c.moveToFirst()) {
                byte[] data = c.getBlob(0);
                if (data == null || data.length == 0) return null;
                return BitmapFactory.decodeByteArray(data, 0, data.length);
            }
        } catch (Exception e) {
            Log.w(TAG, "getStaleTile error: " + e.getMessage());
        }
        return null;
    }

    // ── Write API ──────────────────────────────────────────────────────────────

    /**
     * Buffer a tile for asynchronous write to SQLite.
     *
     * <p>Modeled on ATAK's {@code MBTilesContainer} async write buffer:
     * tiles are queued in a {@link ConcurrentHashMap} and flushed to SQLite
     * in batches by the scheduled executor or when the threshold is reached.</p>
     *
     * @param zoom      tile zoom level
     * @param x         tile column
     * @param y         tile row
     * @param timestamp RainViewer frame timestamp (Unix seconds)
     * @param bitmap    the tile image to cache
     */
    public void putTile(int zoom, int x, int y, long timestamp, Bitmap bitmap) {
        if (bitmap == null) return;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos);
        byte[] data = bos.toByteArray();

        String key = tileKey(zoom, x, y, timestamp);
        pendingWrites.put(key, new PendingTile(zoom, x, y, timestamp, data));

        if (pendingWrites.size() >= FLUSH_THRESHOLD) {
            flushPendingWrites();
        }
    }

    // ── Flush (modeled on MBTilesContainer.flushPendingWrites) ─────────────────

    /**
     * Flush all buffered tile writes to SQLite in a single transaction.
     * Called periodically by the scheduled executor and on threshold.
     */
    private synchronized void flushPendingWrites() {
        if (pendingWrites.isEmpty()) return;

        // Snapshot and clear — minimize time under lock
        Map<String, PendingTile> snapshot = new ConcurrentHashMap<>(pendingWrites);
        pendingWrites.clear();

        db.beginTransaction();
        try {
            SQLiteStatement stmt = db.compileStatement(
                    "INSERT OR REPLACE INTO radar_tiles " +
                    "(zoom, x, y, timestamp, tile_data, expiration, inserted_at) " +
                    "VALUES(?, ?, ?, ?, ?, ?, ?)");

            long now = System.currentTimeMillis();
            long expiry = now + expirationOffsetMs;

            for (PendingTile tile : snapshot.values()) {
                stmt.bindLong(1, tile.zoom);
                stmt.bindLong(2, tile.x);
                stmt.bindLong(3, tile.y);
                stmt.bindLong(4, tile.timestamp);
                stmt.bindBlob(5, tile.data);
                stmt.bindLong(6, expiry);
                stmt.bindLong(7, now);
                stmt.executeInsert();
                stmt.clearBindings();
            }
            db.setTransactionSuccessful();
            Log.d(TAG, "Flushed " + snapshot.size() + " tiles to SQLite");
        } catch (Exception e) {
            Log.e(TAG, "Flush error", e);
        } finally {
            db.endTransaction();
        }

        enforceSizeLimit();
    }

    // ── Maintenance ────────────────────────────────────────────────────────────

    /**
     * Delete all tiles whose expiration timestamp has passed.
     * Mirrors ATAK's {@code TileCacheControl.expireTiles(long)}.
     */
    public void purgeExpired() {
        try {
            int deleted = db.delete("radar_tiles",
                    "expiration < ?",
                    new String[]{ String.valueOf(System.currentTimeMillis()) });
            if (deleted > 0) Log.d(TAG, "Purged " + deleted + " expired tiles");
        } catch (Exception e) {
            Log.w(TAG, "purgeExpired error: " + e.getMessage());
        }
    }

    /**
     * Enforce the maximum cache size by evicting the oldest 25% of tiles.
     * Uses {@code inserted_at} ordering for LRU eviction — same strategy
     * as ATAK's tile cache size management.
     */
    private void enforceSizeLimit() {
        long size = getCacheSizeBytes();
        if (size <= maxCacheSizeBytes) return;

        try {
            db.execSQL(
                "DELETE FROM radar_tiles WHERE rowid IN (" +
                "  SELECT rowid FROM radar_tiles " +
                "  ORDER BY inserted_at ASC " +
                "  LIMIT (SELECT MAX(1, COUNT(*)/4) FROM radar_tiles)" +
                ")");
            Log.d(TAG, "Evicted oldest 25% — was " + (size / 1024) + " KB");
        } catch (Exception e) {
            Log.w(TAG, "enforceSizeLimit error: " + e.getMessage());
        }
    }

    // ── Query API ──────────────────────────────────────────────────────────────

    /** Total cache size in bytes (sum of all tile_data BLOBs). */
    public long getCacheSizeBytes() {
        try (Cursor c = db.rawQuery(
                "SELECT COALESCE(SUM(LENGTH(tile_data)), 0) FROM radar_tiles", null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Total number of cached tiles. */
    public int getTileCount() {
        try (Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM radar_tiles", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Delete all cached tiles and pending writes. */
    public void clearAll() {
        pendingWrites.clear();
        try {
            db.execSQL("DELETE FROM radar_tiles");
            Log.d(TAG, "All radar tiles cleared");
        } catch (Exception e) {
            Log.w(TAG, "clearAll error: " + e.getMessage());
        }
    }

    /** Human-readable cache size label (e.g. "12.3 MB"). */
    public String getCacheSizeLabel() {
        long bytes = getCacheSizeBytes();
        if (bytes < 1024)        return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /**
     * Flush pending writes, shut down the background writer, and close the database.
     * Call from {@code WeatherMapComponent.onDestroyImpl()}.
     */
    public void dispose() {
        try {
            flushPendingWrites();
        } catch (Exception e) {
            Log.w(TAG, "dispose flush error: " + e.getMessage());
        }
        flushExecutor.shutdown();
        try {
            if (!flushExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                flushExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            flushExecutor.shutdownNow();
        }
        if (db.isOpen()) db.close();
        Log.d(TAG, "RadarTileCache disposed");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static String tileKey(int z, int x, int y, long ts) {
        return z + "/" + x + "/" + y + "/" + ts;
    }

    // ── SQLiteOpenHelper ────────────────────────────────────────────────────────

    private static class DbHelper extends SQLiteOpenHelper {
        DbHelper(Context ctx, String name, int version) {
            super(ctx, name, null, version);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS radar_tiles (" +
                "  zoom        INTEGER NOT NULL," +
                "  x           INTEGER NOT NULL," +
                "  y           INTEGER NOT NULL," +
                "  timestamp   INTEGER NOT NULL," +
                "  tile_data   BLOB," +
                "  expiration  INTEGER NOT NULL," +
                "  inserted_at INTEGER NOT NULL," +
                "  PRIMARY KEY(zoom, x, y, timestamp)" +
                ")");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_radar_expiration " +
                       "ON radar_tiles(expiration)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_radar_inserted " +
                       "ON radar_tiles(inserted_at)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS radar_tiles");
            onCreate(db);
        }
    }
}
