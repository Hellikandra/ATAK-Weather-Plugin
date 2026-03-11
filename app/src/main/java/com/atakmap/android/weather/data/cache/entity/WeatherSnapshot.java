package com.atakmap.android.weather.data.cache.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Room entity — one snapshot row per coordinate + source combination.
 *
 * Key design decisions:
 *  - lat/lon stored at 4 dp precision (≈11 m): close enough to deduplicate
 *    repeated loads at the same position without filling the table with
 *    slightly-different coords.
 *  - source string: "SELF_MARKER" or "MAP_CENTRE" — matches LocationSource.name()
 *  - fetchedAt / expiresAt: epoch-millis so TTL comparison is a simple long compare
 *  - locationDisplayName stored so Tab 1 header works offline without a Nominatim call
 *  - paramHash: MD5-like hash of the parameter query string so a parameter
 *    change invalidates the cached entry (Sprint 2 integration)
 */
@Entity(tableName = "weather_snapshot")
public class WeatherSnapshot {

    @PrimaryKey(autoGenerate = true)
    public long id;

    // ── Key ─────────────────────────────────────────────────────────────────
    public double lat;             // rounded to 4 dp
    public double lon;
    public String source;          // LocationSource.name()
    public String paramHash;       // hash of hourly+daily query params

    // ── Metadata ─────────────────────────────────────────────────────────────
    public String locationDisplayName;
    public long   fetchedAt;       // epoch-millis
    public long   expiresAt;       // fetchedAt + TTL_MS

    // ── Current-conditions payload (mirrors WeatherModel fields) ─────────────
    public double temperatureMin;
    public double temperatureMax;
    public double apparentTemperature;
    public double humidity;
    public double pressure;
    public double visibility;
    public double windSpeed;
    public double windDirection;
    public double precipitationSum;
    public double precipitationHours;
    public int    weatherCode;
    public String requestTimestamp;
}
