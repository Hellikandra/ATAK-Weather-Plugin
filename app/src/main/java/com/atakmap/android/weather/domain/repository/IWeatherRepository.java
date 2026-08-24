package com.atakmap.android.weather.domain.repository;

import com.atakmap.android.weather.domain.model.DailyForecastModel;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.model.WindProfileModel;

import java.util.List;

/**
 * Contract for weather data — implemented in the data layer.
 * The domain layer depends only on this interface (Dependency Inversion).
 *
 * <p>Sprint 24 (S24.2): This broad interface is kept for backward compatibility.
 * New code should prefer the narrow interfaces:
 * <ul>
 *   <li>{@link ICurrentWeatherSource} — current conditions only</li>
 *   <li>{@link IForecastSource} — daily/hourly forecasts</li>
 *   <li>{@link IWindProfileSource} — wind profile data</li>
 * </ul>
 * The implementation class ({@code WeatherRepositoryImpl}) implements all four.</p>
 */
public interface IWeatherRepository {

    interface Callback<T> {
        void onSuccess(T result);
        void onError(String message);
    }

    /**
     * Fetch current weather + today's stats for the given coordinates.
     */
    void getCurrentWeather(double latitude, double longitude,
                           Callback<WeatherModel> callback);

    /**
     * Fetch 7-day daily forecast.
     */
    void getDailyForecast(double latitude, double longitude,
                          Callback<List<DailyForecastModel>> callback);

    /**
     * Fetch hourly entries for the next N hours (default 168 = 7 days).
     */
    void getHourlyForecast(double latitude, double longitude,
                           Callback<List<HourlyEntryModel>> callback);

    /**
     * Fetch multi-altitude wind profile.
     */
    void getWindProfile(double latitude, double longitude,
                        Callback<List<WindProfileModel>> callback);
}
