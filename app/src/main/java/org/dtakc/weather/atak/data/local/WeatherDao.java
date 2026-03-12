package org.dtakc.weather.atak.data.local;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import org.dtakc.weather.atak.data.local.entity.DailyEntry;
import org.dtakc.weather.atak.data.local.entity.HourlyEntry;
import org.dtakc.weather.atak.data.local.entity.WeatherSnapshot;

import java.util.List;

/**
 * Room DAO — all cache read/write operations.
 *
 * Queries use a 4-dp lat/lon tolerance (±0.00005 ≈ 5 m) so small GPS jitter
 * does not produce cache misses. Exact equality is used for source + paramHash.
 */
@Dao
public interface WeatherDao {

    // ── Snapshot ──────────────────────────────────────────────────────────────

    /**
     * Find the freshest snapshot matching lat/lon/source/paramHash.
     * Returns null if no match or every match is expired.
     */
    @Query("SELECT * FROM weather_snapshot " +
            "WHERE ABS(lat - :lat) < 0.00005 " +
            "  AND ABS(lon - :lon) < 0.00005 " +
            "  AND source     = :source " +
            "  AND paramHash  = :paramHash " +
            "  AND expiresAt  > :nowMs " +
            "ORDER BY fetchedAt DESC LIMIT 1")
    WeatherSnapshot findFreshSnapshot(double lat, double lon,
                                      String source, String paramHash,
                                      long nowMs);

    /**
     * Find the most recent snapshot regardless of expiry — used for
     * the "no internet" offline display path.
     */
    @Query("SELECT * FROM weather_snapshot " +
            "WHERE ABS(lat - :lat) < 0.00005 " +
            "  AND ABS(lon - :lon) < 0.00005 " +
            "  AND source = :source " +
            "ORDER BY fetchedAt DESC LIMIT 1")
    WeatherSnapshot findLatestSnapshot(double lat, double lon, String source);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertSnapshot(WeatherSnapshot snapshot);

    @Query("DELETE FROM weather_snapshot WHERE id = :id")
    void deleteSnapshot(long id);

    /** Remove all expired snapshots (house-keeping, called on plugin open). */
    @Query("DELETE FROM weather_snapshot WHERE expiresAt < :nowMs")
    void purgeExpired(long nowMs);

    // ── Hourly entries ────────────────────────────────────────────────────────

    @Query("SELECT * FROM hourly_entry WHERE snapshotId = :snapshotId ORDER BY slotIndex ASC")
    List<HourlyEntry> getHourlyEntries(long snapshotId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertHourlyEntries(List<HourlyEntry> entries);

    // ── Daily entries ─────────────────────────────────────────────────────────

    @Query("SELECT * FROM daily_entry WHERE snapshotId = :snapshotId ORDER BY dayIndex ASC")
    List<DailyEntry> getDailyEntries(long snapshotId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertDailyEntries(List<DailyEntry> entries);

    // ── Composite write ───────────────────────────────────────────────────────

    /**
     * Atomically replace all data for a coordinate/source/paramHash combination.
     * Deletes any stale rows for this key first (cascade handles children),
     * then inserts the new snapshot + all its children.
     */
    @Transaction
    default long replaceSnapshot(WeatherSnapshot snapshot,
                                 List<HourlyEntry> hourly,
                                 List<DailyEntry>  daily) {
        // Remove stale rows for this exact key (not-yet-expired ones too — we
        // have fresh data now, so the old entry is redundant)
        deleteByKey(snapshot.lat, snapshot.lon, snapshot.source, snapshot.paramHash);
        long newId = insertSnapshot(snapshot);
        for (HourlyEntry h : hourly) h.snapshotId = newId;
        for (DailyEntry  d : daily)  d.snapshotId = newId;
        insertHourlyEntries(hourly);
        insertDailyEntries(daily);
        return newId;
    }

    @Query("DELETE FROM weather_snapshot " +
            "WHERE ABS(lat - :lat) < 0.00005 " +
            "  AND ABS(lon - :lon) < 0.00005 " +
            "  AND source    = :source " +
            "  AND paramHash = :paramHash")
    void deleteByKey(double lat, double lon, String source, String paramHash);
}
