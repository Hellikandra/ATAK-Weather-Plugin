package com.atakmap.android.weather.data.cache;

import com.atakmap.android.weather.data.cache.entity.DailyEntry;
import com.atakmap.android.weather.data.cache.entity.HourlyEntry;
import com.atakmap.android.weather.data.cache.entity.WeatherSnapshot;
import com.atakmap.android.weather.domain.model.DailyForecastModel;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.LocationSource;
import com.atakmap.android.weather.domain.model.WeatherModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Bidirectional mapping between Room entities and domain models.
 * Keeps all persistence concerns out of domain classes.
 */
public final class CacheMapper {

    private CacheMapper() {}

    // ── WeatherSnapshot ↔ WeatherModel ────────────────────────────────────────

    public static WeatherModel toDomain(WeatherSnapshot s) {
        return new WeatherModel.Builder(s.lat, s.lon)
                .temperatureMin(s.temperatureMin)
                .temperatureMax(s.temperatureMax)
                .apparentTemperature(s.apparentTemperature)
                .humidity(s.humidity)
                .pressure(s.pressure)
                .visibility(s.visibility)
                .windSpeed(s.windSpeed)
                .windDirection(s.windDirection)
                .precipitationSum(s.precipitationSum)
                .precipitationHours(s.precipitationHours)
                .weatherCode(s.weatherCode)
                .requestTimestamp(s.requestTimestamp)
                .build();
    }

    public static WeatherSnapshot toSnapshot(WeatherModel m,
                                             LocationSource source,
                                             String paramHash,
                                             String displayName,
                                             long fetchedAtMs) {
        WeatherSnapshot s = new WeatherSnapshot();
        s.lat                  = CachePolicy.roundCoord(m.getLatitude());
        s.lon                  = CachePolicy.roundCoord(m.getLongitude());
        s.source               = source.name();
        s.paramHash            = paramHash;
        s.locationDisplayName  = displayName != null ? displayName : "";
        s.fetchedAt            = fetchedAtMs;
        s.expiresAt            = CachePolicy.expiresAt(fetchedAtMs);
        s.temperatureMin       = m.getTemperatureMin();
        s.temperatureMax       = m.getTemperatureMax();
        s.apparentTemperature  = m.getApparentTemperature();
        s.humidity             = m.getHumidity();
        s.pressure             = m.getPressure();
        s.visibility           = m.getVisibility();
        s.windSpeed            = m.getWindSpeed();
        s.windDirection        = m.getWindDirection();
        s.precipitationSum     = m.getPrecipitationSum();
        s.precipitationHours   = m.getPrecipitationHours();
        s.weatherCode          = m.getWeatherCode();
        s.requestTimestamp     = m.getRequestTimestamp();
        return s;
    }

    // ── HourlyEntry ↔ HourlyEntryModel ────────────────────────────────────────

    public static HourlyEntryModel toDomain(HourlyEntry e) {
        return new HourlyEntryModel.Builder()
                .isoTime(e.isoTime)
                .hour(e.hour)
                .temperature(e.temperature)
                .apparentTemperature(e.apparentTemperature)
                .humidity(e.humidity)
                .pressure(e.pressure)
                .visibility(e.visibility)
                .windSpeed(e.windSpeed)
                .windDirection(e.windDirection)
                .precipitationProbability(e.precipitationProbability)
                .precipitation(e.precipitation)
                .weatherCode(e.weatherCode)
                .build();
    }

    public static HourlyEntry toEntity(HourlyEntryModel m, int slotIndex) {
        HourlyEntry e = new HourlyEntry();
        e.slotIndex                 = slotIndex;
        e.isoTime                   = m.getIsoTime();
        e.hour                      = m.getHour();
        e.temperature               = m.getTemperature();
        e.apparentTemperature       = m.getApparentTemperature();
        e.humidity                  = m.getHumidity();
        e.pressure                  = m.getPressure();
        e.visibility                = m.getVisibility();
        e.windSpeed                 = m.getWindSpeed();
        e.windDirection             = m.getWindDirection();
        e.precipitationProbability  = m.getPrecipitationProbability();
        e.precipitation             = m.getPrecipitation();
        e.weatherCode               = m.getWeatherCode();
        return e;
    }

    public static List<HourlyEntryModel> hourlyToDomain(List<HourlyEntry> rows) {
        List<HourlyEntryModel> out = new ArrayList<>(rows.size());
        for (HourlyEntry r : rows) out.add(toDomain(r));
        return out;
    }

    public static List<HourlyEntry> hourlyToEntities(List<HourlyEntryModel> models) {
        List<HourlyEntry> out = new ArrayList<>(models.size());
        for (int i = 0; i < models.size(); i++) out.add(toEntity(models.get(i), i));
        return out;
    }

    // ── DailyEntry ↔ DailyForecastModel ──────────────────────────────────────

    public static DailyForecastModel toDomain(DailyEntry e) {
        return new DailyForecastModel.Builder()
                .date(e.date)
                .dayLabel(e.dayLabel)
                .temperatureMax(e.temperatureMax)
                .temperatureMin(e.temperatureMin)
                .weatherCode(e.weatherCode)
                .precipitationSum(e.precipitationSum)
                .precipitationHours(e.precipitationHours)
                .precipitationProbabilityMax(e.precipitationProbabilityMax)
                .build();
    }

    public static DailyEntry toEntity(DailyForecastModel m, int dayIndex) {
        DailyEntry e = new DailyEntry();
        e.dayIndex                   = dayIndex;
        e.date                       = m.getDate();
        e.dayLabel                   = m.getDayLabel();
        e.temperatureMax             = m.getTemperatureMax();
        e.temperatureMin             = m.getTemperatureMin();
        e.weatherCode                = m.getWeatherCode();
        e.precipitationSum           = m.getPrecipitationSum();
        e.precipitationHours         = m.getPrecipitationHours();
        e.precipitationProbabilityMax = m.getPrecipitationProbabilityMax();
        return e;
    }

    public static List<DailyForecastModel> dailyToDomain(List<DailyEntry> rows) {
        List<DailyForecastModel> out = new ArrayList<>(rows.size());
        for (DailyEntry r : rows) out.add(toDomain(r));
        return out;
    }

    public static List<DailyEntry> dailyToEntities(List<DailyForecastModel> models) {
        List<DailyEntry> out = new ArrayList<>(models.size());
        for (int i = 0; i < models.size(); i++) out.add(toEntity(models.get(i), i));
        return out;
    }
}
