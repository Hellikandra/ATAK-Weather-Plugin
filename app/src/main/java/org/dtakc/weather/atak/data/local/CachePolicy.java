package org.dtakc.weather.atak.data.local;

import org.dtakc.weather.atak.data.preferences.WeatherParameterPreferences;

import java.math.BigInteger;
import java.security.MessageDigest;

/**
 * Pure-static cache policy helpers — no Android dependencies except the
 * WeatherParameterPreferences used for hash computation.
 *
 * TTL: 30 minutes default. The user cannot change this in v3; Sprint-3 backlog
 * item B-TTL would expose it in a future settings screen.
 *
 * Coordinate rounding: 4 decimal places (≈11 m). Matches the DAO query
 * tolerance. Stored rounded so the primary-key comparison is exact after
 * the DAO ±0.00005 window does its job.
 *
 * Param hash: MD5 of (hourlyQueryParam + dailyQueryParam). A change in
 * Tab 4 selections produces a different hash, which bypasses the cache
 * (OpenMeteoSource.isStale() is the fast path; hash is the durable path
 * that survives a plugin restart).
 */
public final class CachePolicy {

    /** Default cache lifetime: 30 minutes. */
    public static final long TTL_MS = 30L * 60L * 1000L;

    private CachePolicy() {}

    /** Round a coordinate to 4 decimal places for cache key use. */
    public static double roundCoord(double coord) {
        return Math.round(coord * 10_000.0) / 10_000.0;
    }

    /** Expiry timestamp from a fetch time and TTL. */
    public static long expiresAt(long fetchedAtMs) {
        return fetchedAtMs + TTL_MS;
    }

    /** True if the snapshot is still within its TTL. */
    public static boolean isFresh(long expiresAtMs) {
        return System.currentTimeMillis() < expiresAtMs;
    }

    /**
     * MD5 hash of the combined hourly + daily query param strings.
     * Returns a 32-char hex string. Falls back to a simple hashCode string
     * if MD5 is unavailable (should never happen on Android).
     */
    public static String paramHash(WeatherParameterPreferences prefs) {
        if (prefs == null) return "default";
        String raw = prefs.buildHourlyQueryParam() + "|" + prefs.buildDailyQueryParam();
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(raw.getBytes("UTF-8"));
            return String.format("%032x",
                    new BigInteger(1, digest));
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    /**
     * Human-readable "Cached HH:MM" label from a fetchedAt epoch-millis.
     * Shown in the Tab 1 badge when serving stale data.
     */
    public static String cachedLabel(long fetchedAtMs) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(fetchedAtMs);
        return String.format("Cached %02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE));
    }
}
