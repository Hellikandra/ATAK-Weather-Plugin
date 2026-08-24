package com.atakmap.android.weather.domain.repository;

import com.atakmap.android.weather.domain.model.WeatherModel;

/**
 * Interface Segregation (Sprint 24 — S24.2): source for current weather only.
 *
 * <p>Consumers that only need current conditions (e.g., dashboard, markers)
 * depend on this narrow interface instead of the full {@link IWeatherRepository}.</p>
 */
public interface ICurrentWeatherSource {

    /** Callback for current weather result. */
    interface CurrentWeatherCallback {
        void onResult(WeatherModel model);
        void onError(String message);
    }

    /**
     * Fetch current weather for the given coordinates.
     *
     * @param lat      latitude
     * @param lon      longitude
     * @param callback result callback (main thread)
     */
    void fetchCurrentWeather(double lat, double lon, CurrentWeatherCallback callback);
}
