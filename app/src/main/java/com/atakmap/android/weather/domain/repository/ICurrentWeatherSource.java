package com.atakmap.android.weather.domain.repository;

import com.atakmap.android.weather.domain.model.WeatherModel;

/**
 * A source that can report current conditions for a point.
 *
 * <p>Every weather source can do this, so {@code IWeatherRemoteSource} extends it.
 * It is a separate type anyway so that consumers which only need current
 * conditions can say so.
 */
public interface ICurrentWeatherSource {

    void fetchCurrentWeather(double lat, double lon,
                             FetchCallback<WeatherModel> callback);
}
