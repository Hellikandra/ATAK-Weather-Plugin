package org.dtakc.weather.atak.data.remote;

import org.dtakc.weather.atak.data.preferences.WeatherParameterPreferences;
import org.dtakc.weather.atak.domain.model.DailyForecastModel;
import org.dtakc.weather.atak.domain.model.HourlyEntryModel;
import org.dtakc.weather.atak.domain.model.WeatherModel;
import org.dtakc.weather.atak.domain.model.WeatherParameter;
import org.dtakc.weather.atak.domain.model.WindProfileModel;

import java.util.List;

/** Contract for all pluggable weather API sources. */
public interface IWeatherDataSource {
    interface FetchCallback<T> {
        void onResult(T data);
        void onError(String message);
    }

    String getSourceId();
    default String getDisplayName() {
        String id = getSourceId();
        return (id == null || id.isEmpty()) ? "Unknown"
                : id.substring(0, 1).toUpperCase() + id.substring(1).replace("-", " ");
    }

    List<WeatherParameter> getSupportedParameters();
    void setParameterPreferences(WeatherParameterPreferences prefs);

    void fetchCurrentWeather(double lat, double lon, FetchCallback<WeatherModel> cb);
    void fetchDailyForecast(double lat, double lon, int days, FetchCallback<List<DailyForecastModel>> cb);
    void fetchHourlyForecast(double lat, double lon, int hours, FetchCallback<List<HourlyEntryModel>> cb);
    void fetchWindProfile(double lat, double lon, FetchCallback<List<WindProfileModel>> cb);
}
