package com.atakmap.android.weather.domain.repository;

import com.atakmap.android.weather.domain.model.DailyForecastModel;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;

import java.util.List;

/**
 * Interface Segregation (Sprint 24 — S24.2): source for forecast data.
 *
 * <p>Consumers that need daily/hourly forecasts (e.g., chart, heatmap)
 * depend on this narrow interface instead of the full {@link IWeatherRepository}.</p>
 */
public interface IForecastSource {

    /** Callback for daily forecast result. */
    interface DailyForecastCallback {
        void onResult(List<DailyForecastModel> forecasts);
        void onError(String message);
    }

    /** Callback for hourly forecast result. */
    interface HourlyForecastCallback {
        void onResult(List<HourlyEntryModel> entries);
        void onError(String message);
    }

    /**
     * Fetch daily forecast for the given coordinates.
     */
    void fetchDailyForecast(double lat, double lon, DailyForecastCallback callback);

    /**
     * Fetch hourly forecast for the given coordinates.
     */
    void fetchHourlyForecast(double lat, double lon, String hourlyParams,
                              HourlyForecastCallback callback);
}
