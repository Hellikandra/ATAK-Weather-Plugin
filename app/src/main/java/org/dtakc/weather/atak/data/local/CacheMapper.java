package org.dtakc.weather.atak.data.local;

import org.dtakc.weather.atak.data.local.entity.DailyEntry;
import org.dtakc.weather.atak.data.local.entity.HourlyEntry;
import org.dtakc.weather.atak.data.local.entity.WeatherSnapshot;
import org.dtakc.weather.atak.domain.model.DailyForecastModel;
import org.dtakc.weather.atak.domain.model.HourlyEntryModel;
import org.dtakc.weather.atak.domain.model.LocationSource;
import org.dtakc.weather.atak.domain.model.WeatherModel;

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
        s.lat                  = CachePolicy.roundCoord(m.latitude);
        s.lon                  = CachePolicy.roundCoord(m.longitude);
        s.source               = source.name();
        s.paramHash            = paramHash;
        s.locationDisplayName  = displayName != null ? displayName : "";
        s.fetchedAt            = fetchedAtMs;
        s.expiresAt            = CachePolicy.expiresAt(fetchedAtMs);
        s.temperatureMin       = m.temperatureMin;
        s.temperatureMax       = m.temperatureMax;
        s.apparentTemperature  = m.apparentTemperature;
        s.humidity             = m.humidity;
        s.pressure             = m.pressure;
        s.visibility           = m.visibility;
        s.windSpeed            = m.windSpeed;
        s.windDirection        = m.windDirection;
        s.precipitationSum     = m.precipitationSum;
        s.precipitationHours   = m.precipitationHours;
        s.weatherCode          = m.weatherCode;
        s.requestTimestamp     = m.requestTimestamp;
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
