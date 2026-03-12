package com.atakmap.android.weather.data.cache.entity;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Room entity — one row per hourly forecast slot, child of WeatherSnapshot.
 *
 * Foreign key to weather_snapshot(id) with CASCADE DELETE so all hourly rows
 * are removed atomically when the parent snapshot is evicted or refreshed.
 *
 * slotIndex = 0..167, matching the hourlyCache list index in WeatherViewModel.
 */
@Entity(
        tableName = "hourly_entry",
        foreignKeys = @ForeignKey(
                entity    = WeatherSnapshot.class,
                parentColumns = "id",
                childColumns  = "snapshotId",
                onDelete  = ForeignKey.CASCADE
        ),
        indices = @Index("snapshotId")
)
public class HourlyEntry {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public long   snapshotId;
    public int    slotIndex;
    public String isoTime;
    public int    hour;
    public double temperature;
    public double apparentTemperature;
    public double humidity;
    public double pressure;
    public double visibility;
    public double windSpeed;
    public double windDirection;
    public double precipitationProbability;
    public double precipitation;
    public int    weatherCode;
}
