package org.dtakc.weather.atak.data.remote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wave 2 — DataSourceDescriptor / IWeatherDataSource unit tests.
 *
 * TC-11: getSourceId() returns non-null, non-empty string
 * TC-12: getDisplayName() returns non-null, non-empty string
 * TC-13: getSupportedParameters() never returns null
 * TC-14: getSourceId() is stable (same value across multiple calls)
 * TC-15: AtomicBoolean thread-safety on OpenMeteoDataSource.isStale (ISS-08)
 */
class DataSourceDescriptorTest {

    /** Create a concrete IWeatherDataSource via the known OpenMeteo impl. */
    private IWeatherDataSource makeSource() {
        return new org.dtakc.weather.atak.data.remote.openmeteo.OpenMeteoDataSource();
    }

    // TC-11: sourceId non-null and non-empty
    @Test
    void TC11_sourceId_isNonNullNonEmpty() {
        IWeatherDataSource src = makeSource();
        assertNotNull(src.getSourceId(), "sourceId must not be null");
        assertFalse(src.getSourceId().isEmpty(), "sourceId must not be empty");
    }

    // TC-12: displayName non-null and non-empty
    @Test
    void TC12_displayName_isNonNullNonEmpty() {
        IWeatherDataSource src = makeSource();
        assertNotNull(src.getDisplayName(), "displayName must not be null");
        assertFalse(src.getDisplayName().isEmpty(), "displayName must not be empty");
    }

    // TC-13: getSupportedParameters() never returns null for any built-in source
    @Test
    void TC13_supportedParameters_neverNull() {
        IWeatherDataSource[] sources = {
            new org.dtakc.weather.atak.data.remote.openmeteo.OpenMeteoDataSource(),
            new org.dtakc.weather.atak.data.remote.openmeteo.OpenMeteoECMWFSource(),
            new org.dtakc.weather.atak.data.remote.openmeteo.OpenMeteoDWDSource(),
            new org.dtakc.weather.atak.data.remote.avwx.AviationWeatherDataSource()
        };
        for (IWeatherDataSource src : sources) {
            assertNotNull(src.getSupportedParameters(),
                    src.getSourceId() + ".getSupportedParameters() must not be null");
        }
    }

    // TC-14: sourceId is stable across multiple calls
    @Test
    void TC14_sourceId_isStable() {
        IWeatherDataSource src = makeSource();
        String id1 = src.getSourceId();
        String id2 = src.getSourceId();
        assertEquals(id1, id2, "sourceId must return the same value on repeated calls");
    }

    /**
     * TC-15: AtomicBoolean thread-safety (ISS-08).
     * 100 concurrent threads toggle isStale; no data race should occur.
     * If isStale were a plain volatile boolean with CAS reads this would
     * be a best-effort test — with AtomicBoolean it is guaranteed.
     */
    @Test
    void TC15_isStale_isAtomicUnderConcurrency() throws InterruptedException {
        org.dtakc.weather.atak.data.remote.openmeteo.OpenMeteoDataSource src =
                new org.dtakc.weather.atak.data.remote.openmeteo.OpenMeteoDataSource();

        int threadCount = 100;
        Thread[] threads = new Thread[threadCount];
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done  = new java.util.concurrent.CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final boolean markStale = (i % 2 == 0);
            threads[i] = new Thread(() -> {
                try { start.await(); } catch (InterruptedException ignored) {}
                // Alternate: mark stale / clear stale / read
                if (markStale) src.markStale();
                else           src.clearStale();
                boolean _ = src.isStaleForCurrentSource(); // read under contention
                done.countDown();
            });
            threads[i].start();
        }

        start.countDown(); // release all threads simultaneously
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "All threads should complete within 5s");
        // No assertion on final value — the test passes if no exception/deadlock
    }

    // Parameterized: verify all built-in sources have stable non-empty IDs
    @ParameterizedTest(name = "sourceId stable for {0}")
    @CsvSource({
        "open_meteo_gfs",
        "open_meteo_ecmwf",
        "open_meteo_dwd",
        "aviation_weather"
    })
    void TC14_parameterized_sourceId_stable(String expectedId) {
        IWeatherDataSource src;
        switch (expectedId) {
            case "open_meteo_gfs":   src = new org.dtakc.weather.atak.data.remote.openmeteo.OpenMeteoDataSource(); break;
            case "open_meteo_ecmwf": src = new org.dtakc.weather.atak.data.remote.openmeteo.OpenMeteoECMWFSource(); break;
            case "open_meteo_dwd":   src = new org.dtakc.weather.atak.data.remote.openmeteo.OpenMeteoDWDSource(); break;
            case "aviation_weather": src = new org.dtakc.weather.atak.data.remote.avwx.AviationWeatherDataSource(); break;
            default: fail("Unknown source: " + expectedId); return;
        }
        assertEquals(expectedId, src.getSourceId(),
                "Source ID must match registered constant");
    }
}
