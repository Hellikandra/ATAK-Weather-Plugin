package org.dtakc.weather.atak.data;

import android.content.Context;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.dtakc.weather.atak.data.local.CachePolicy;
import org.dtakc.weather.atak.data.local.WeatherDao;
import org.dtakc.weather.atak.data.local.WeatherDatabase;
import org.dtakc.weather.atak.data.local.entity.WeatherSnapshot;
import org.dtakc.weather.atak.domain.model.LocationSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

import static org.junit.Assert.*;

/**
 * Wave 6 — Room in-memory database integration tests.
 *
 * TC-05i: fresh snapshot returns on findFreshSnapshot()
 * TC-06i: expired snapshot NOT returned by findFreshSnapshot()
 * TC-08i: findLatestSnapshot returns stale entry when no fresh one exists
 * TC-09i: insertSnapshot + findFreshSnapshot round-trip preserves data integrity
 */
@RunWith(AndroidJUnit4.class)
public class WeatherDaoIntegrationTest {

    private WeatherDatabase db;
    private WeatherDao      dao;

    @Before
    public void setUp() {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        db = Room.inMemoryDatabaseBuilder(ctx, WeatherDatabase.class)
                .allowMainThreadQueries() // test-only
                .build();
        dao = db.weatherDao();
    }

    @After
    public void tearDown() throws IOException {
        db.close();
    }

    // TC-05i: fresh snapshot within TTL → returned by findFreshSnapshot
    @Test
    public void TC05i_freshSnapshot_returnsOnQuery() {
        WeatherSnapshot snap = buildSnapshot(System.currentTimeMillis(),
                System.currentTimeMillis() + CachePolicy.TTL_MS);
        dao.insertSnapshot(snap);

        WeatherSnapshot found = dao.findFreshSnapshot(
                snap.lat, snap.lon, snap.source, snap.paramHash,
                System.currentTimeMillis());
        assertNotNull("Fresh snapshot must be returned", found);
    }

    // TC-06i: expired snapshot (expiresAt in past) → NOT returned by findFreshSnapshot
    @Test
    public void TC06i_expiredSnapshot_notReturnedByFindFresh() {
        long past = System.currentTimeMillis() - 10_000L;
        WeatherSnapshot snap = buildSnapshot(past - CachePolicy.TTL_MS, past);
        dao.insertSnapshot(snap);

        WeatherSnapshot found = dao.findFreshSnapshot(
                snap.lat, snap.lon, snap.source, snap.paramHash,
                System.currentTimeMillis());
        assertNull("Expired snapshot must not be returned by findFreshSnapshot", found);
    }

    // TC-08i: findLatestSnapshot returns expired entry when no fresh exists
    @Test
    public void TC08i_findLatest_returnsStaleFallback() {
        long past = System.currentTimeMillis() - 10_000L;
        WeatherSnapshot stale = buildSnapshot(past - CachePolicy.TTL_MS, past);
        dao.insertSnapshot(stale);

        WeatherSnapshot found = dao.findLatestSnapshot(stale.lat, stale.lon, stale.source);
        assertNotNull("findLatestSnapshot must return stale entry as offline fallback", found);
    }

    // TC-09i: data written and read back preserves key weather fields
    @Test
    public void TC09i_insertAndQuery_preservesFields() {
        WeatherSnapshot snap = buildSnapshot(System.currentTimeMillis(),
                System.currentTimeMillis() + CachePolicy.TTL_MS);
        snap.temperatureMax  = 28.5;
        snap.windSpeed       = 12.3;
        snap.requestTimestamp = "2024-07-27T12:00";
        dao.insertSnapshot(snap);

        WeatherSnapshot found = dao.findFreshSnapshot(
                snap.lat, snap.lon, snap.source, snap.paramHash,
                System.currentTimeMillis());
        assertNotNull(found);
        assertEquals(28.5, found.temperatureMax, 0.01);
        assertEquals(12.3, found.windSpeed,      0.01);
        assertEquals("2024-07-27T12:00", found.requestTimestamp);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private WeatherSnapshot buildSnapshot(long fetchedAt, long expiresAt) {
        WeatherSnapshot s = new WeatherSnapshot();
        s.lat             = CachePolicy.roundCoord(48.85);
        s.lon             = CachePolicy.roundCoord(2.35);
        s.source          = LocationSource.MAP_CENTRE.name();
        s.paramHash       = "test_hash";
        s.fetchedAt       = fetchedAt;
        s.expiresAt       = expiresAt;
        s.temperatureMax  = 20.0;
        s.requestTimestamp = "2024-07-27T00:00";
        return s;
    }
}
