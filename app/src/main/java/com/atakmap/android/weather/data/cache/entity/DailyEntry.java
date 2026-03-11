package com.atakmap.android.weather.data.cache.entity;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Room entity — one row per daily forecast day, child of WeatherSnapshot.
 * Foreign key to weather_snapshot(id) with CASCADE DELETE.
 * dayIndex = 0..6.
 */
@Entity(
        tableName = "daily_entry",
        foreignKeys = @ForeignKey(
                entity    = WeatherSnapshot.class,
                parentColumns = "id",
                childColumns  = "snapshotId",
                onDelete  = ForeignKey.CASCADE
        ),
        indices = @Index("snapshotId")
)
public class DailyEntry {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public long   snapshotId;
    public int    dayIndex;
    public String date;
    public String dayLabel;
    public double temperatureMax;
    public double temperatureMin;
    public int    weatherCode;
    public double precipitationSum;
    public double precipitationHours;
    public double precipitationProbabilityMax;
}
