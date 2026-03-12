package org.dtakc.weather.atak.data.remote;

import org.dtakc.weather.atak.data.preferences.WeatherParameterPreferences;
import org.dtakc.weather.atak.domain.model.DailyForecastModel;
import org.dtakc.weather.atak.domain.model.HourlyEntryModel;
import org.dtakc.weather.atak.domain.model.WeatherModel;
import org.dtakc.weather.atak.domain.model.WindProfileModel;
import org.dtakc.weather.atak.domain.repository.IWeatherRepository;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live-network implementation of IWeatherRepository.
 * Delegates all fetches to the currently active IWeatherDataSource.
 * The cache decorator (CachingWeatherRepository) wraps this at the DI layer.
 */
public final class NetworkWeatherRepository implements IWeatherRepository {

    private static final int DEFAULT_DAYS  = 7;
    private static final int DEFAULT_HOURS = 168;

    private final Map<String, IWeatherDataSource> sources;
    private       String                          activeSourceId;

    public NetworkWeatherRepository(Map<String, IWeatherDataSource> sources,
                                    String activeSourceId) {
        this.sources        = new ConcurrentHashMap<>(sources);
        this.activeSourceId = activeSourceId;
    }

    public void setActiveSource(String id) {
        if (sources.containsKey(id)) activeSourceId = id;
    }

    public String getActiveSourceId() { return activeSourceId; }

    public void setParameterPreferences(WeatherParameterPreferences prefs) {
        active().setParameterPreferences(prefs);
    }

    /**
     * Returns true when the active source's parameter selection has changed
     * since the last fetch (ISS-08 — use AtomicBoolean in OpenMeteoDataSource).
     */
    public boolean isStaleForCurrentSource() {
        IWeatherDataSource src = sources.get(activeSourceId);
        if (src instanceof org.dtakc.weather.atak.data.remote.openmeteo.OpenMeteoDataSource) {
            return ((org.dtakc.weather.atak.data.remote.openmeteo.OpenMeteoDataSource) src).isStale();
        }
        return false;
    }

    // ── IWeatherRepository ───────────────────────────────────────────────────

    @Override
    public void getCurrentWeather(double lat, double lon, Callback<WeatherModel> cb) {
        active().fetchCurrentWeather(lat, lon,
                new IWeatherDataSource.FetchCallback<WeatherModel>() {
                    @Override public void onResult(WeatherModel d) { cb.onSuccess(d); }
                    @Override public void onError(String m)        { cb.onError(m); }
                });
    }

    @Override
    public void getDailyForecast(double lat, double lon, Callback<List<DailyForecastModel>> cb) {
        active().fetchDailyForecast(lat, lon, DEFAULT_DAYS,
                new IWeatherDataSource.FetchCallback<List<DailyForecastModel>>() {
                    @Override public void onResult(List<DailyForecastModel> d) { cb.onSuccess(d); }
                    @Override public void onError(String m)                     { cb.onError(m); }
                });
    }

    @Override
    public void getHourlyForecast(double lat, double lon, Callback<List<HourlyEntryModel>> cb) {
        active().fetchHourlyForecast(lat, lon, DEFAULT_HOURS,
                new IWeatherDataSource.FetchCallback<List<HourlyEntryModel>>() {
                    @Override public void onResult(List<HourlyEntryModel> d) { cb.onSuccess(d); }
                    @Override public void onError(String m)                   { cb.onError(m); }
                });
    }

    @Override
    public void getWindProfile(double lat, double lon, Callback<List<WindProfileModel>> cb) {
        active().fetchWindProfile(lat, lon,
                new IWeatherDataSource.FetchCallback<List<WindProfileModel>>() {
                    @Override public void onResult(List<WindProfileModel> d) { cb.onSuccess(d); }
                    @Override public void onError(String m)                   { cb.onError(m); }
                });
    }

    private IWeatherDataSource active() {
        IWeatherDataSource s = sources.get(activeSourceId);
        return s != null ? s : sources.values().iterator().next();
    }
}
