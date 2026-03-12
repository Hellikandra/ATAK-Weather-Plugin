package org.dtakc.weather.atak.domain.repository;

import org.dtakc.weather.atak.domain.model.DailyForecastModel;
import org.dtakc.weather.atak.domain.model.HourlyEntryModel;
import org.dtakc.weather.atak.domain.model.WeatherModel;
import org.dtakc.weather.atak.domain.model.WindProfileModel;

import java.util.List;

/** Repository abstraction — data layer provides implementations. */
public interface IWeatherRepository {
    interface Callback<T> {
        void onSuccess(T result);
        void onError(String message);
    }
    void getCurrentWeather(double lat, double lon, Callback<WeatherModel> cb);
    void getDailyForecast(double lat, double lon, Callback<List<DailyForecastModel>> cb);
    void getHourlyForecast(double lat, double lon, Callback<List<HourlyEntryModel>> cb);
    void getWindProfile(double lat, double lon, Callback<List<WindProfileModel>> cb);
}
