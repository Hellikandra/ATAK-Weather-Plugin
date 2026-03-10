package com.atakmap.android.weather.data.remote;

import com.atakmap.android.weather.domain.model.DailyForecastModel;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.model.WindProfileModel;

import java.util.List;

/**
 * Pluggable API source contract.
 *
 * Any weather provider (Open-Meteo, OpenWeatherMap, AviationWeather, …)
 * implements this interface. The active implementation is selected via
 * WeatherPreferences and injected by WeatherModule.
 */
public interface IWeatherRemoteSource {

    /** Unique identifier used in preferences, e.g. "open-meteo". */
    String getSourceId();

    interface FetchCallback<T> {
        void onResult(T data);
        void onError(String message);
    }

    void fetchCurrentWeather(double lat, double lon,
                             FetchCallback<WeatherModel> callback);

    void fetchDailyForecast(double lat, double lon, int days,
                            FetchCallback<List<DailyForecastModel>> callback);

    void fetchHourlyForecast(double lat, double lon, int hours,
                             FetchCallback<List<HourlyEntryModel>> callback);

    void fetchWindProfile(double lat, double lon,
                          FetchCallback<List<WindProfileModel>> callback);
}
