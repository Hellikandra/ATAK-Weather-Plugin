package com.atakmap.android.weather.domain.repository;

import com.atakmap.android.weather.domain.model.DailyForecastModel;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;

import java.util.List;

/**
 * A source that can produce a forecast series.
 *
 * <p><b>Opt-in.</b> Not every provider has one — the FAA Aviation Weather Center
 * publishes station observations and winds aloft but no gridded forecast, so
 * {@code AviationWeatherSource} does not implement this interface. That is the
 * point of the split: a source that cannot forecast has no forecast method to
 * implement, so it cannot quietly answer with someone else's data.
 *
 * <p>Before this existed, the aviation source held a private Open-Meteo instance
 * and delegated both methods to it unconditionally, and the UI went on saying
 * "AWC" (finding F21). Substitution is now a decision the repository makes in one
 * visible place, and the answering provider is stamped on the result.
 */
public interface IForecastSource {

    void fetchDailyForecast(double lat, double lon, int days,
                            FetchCallback<List<DailyForecastModel>> callback);

    void fetchHourlyForecast(double lat, double lon, int hours,
                             FetchCallback<List<HourlyEntryModel>> callback);
}
