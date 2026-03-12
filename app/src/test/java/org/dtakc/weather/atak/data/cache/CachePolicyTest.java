package org.dtakc.weather.atak.data.cache;

import org.dtakc.weather.atak.data.local.CachePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/** Wave 2 — CachePolicy unit tests (pure Java, no Android dependencies). */
class CachePolicyTest {

    @Test
    void roundCoord_fourDecimalPlaces() {
        assertEquals(48.8567, CachePolicy.roundCoord(48.85674321), 1e-9);
        assertEquals(-5.3456, CachePolicy.roundCoord(-5.34562), 1e-9);
    }

    @Test
    void roundCoord_identicalAfterDoubleRound() {
        double r = CachePolicy.roundCoord(51.505678);
        assertEquals(r, CachePolicy.roundCoord(r), 0.0, "Double rounding must be idempotent");
    }

    @Test
    void expiresAt_addsTtl() {
        long now = 1_700_000_000_000L;
        assertEquals(now + CachePolicy.TTL_MS, CachePolicy.expiresAt(now));
    }

    @Test
    void isFresh_returnsTrueBeforeExpiry() {
        long future = System.currentTimeMillis() + 60_000L;
        assertTrue(CachePolicy.isFresh(future));
    }

    @Test
    void isFresh_returnsFalseAfterExpiry() {
        long past = System.currentTimeMillis() - 1L;
        assertFalse(CachePolicy.isFresh(past));
    }

    @Test
    void paramHash_nullPrefs_returnsDefault() {
        assertEquals("default", CachePolicy.paramHash(null));
    }

    @ParameterizedTest
    @CsvSource({
        "48.8600, 2.3500",
        "-33.8700, 151.2100",
        "0.0000, 0.0000"
    })
    void roundCoord_matchesExpectedPrecision(double lat, double lon) {
        // Verify that rounding to 4dp gives coordinates within 11 m tolerance
        double roundedLat = CachePolicy.roundCoord(lat);
        double roundedLon = CachePolicy.roundCoord(lon);
        assertTrue(Math.abs(roundedLat - lat) < 0.00005, "lat rounding within ±0.00005");
        assertTrue(Math.abs(roundedLon - lon) < 0.00005, "lon rounding within ±0.00005");
    }
}
