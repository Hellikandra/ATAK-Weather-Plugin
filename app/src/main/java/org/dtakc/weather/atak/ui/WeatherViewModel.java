package org.dtakc.weather.atak.ui;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.dtakc.weather.atak.data.local.CachingWeatherRepository;
import org.dtakc.weather.atak.domain.model.ComparisonModel;
import org.dtakc.weather.atak.domain.model.DailyForecastModel;
import org.dtakc.weather.atak.domain.model.HourlyEntryModel;
import org.dtakc.weather.atak.domain.model.LocationSnapshot;
import org.dtakc.weather.atak.domain.model.LocationSource;
import org.dtakc.weather.atak.domain.model.WeatherModel;
import org.dtakc.weather.atak.domain.repository.IGeocodingRepository;
import org.dtakc.weather.atak.domain.repository.IWeatherRepository;

import java.util.List;

/**
 * ViewModel for all weather UI.
 * Observes IWeatherRepository + IGeocodingRepository and publishes LiveData for each tab.
 */
public final class WeatherViewModel extends ViewModel {

    private final MutableLiveData<UiState<WeatherModel>>             currentWeather = new MutableLiveData<>();
    private final MutableLiveData<UiState<List<DailyForecastModel>>> dailyForecast  = new MutableLiveData<>();
    private final MutableLiveData<UiState<List<HourlyEntryModel>>>   hourlyForecast = new MutableLiveData<>();
    private final MutableLiveData<LocationSnapshot>                  activeLocation = new MutableLiveData<>();
    private final MutableLiveData<Integer>                           selectedHour   = new MutableLiveData<>(0);
    private final MutableLiveData<String>                            errorMessage   = new MutableLiveData<>();
    private final MutableLiveData<String>                            cacheBadge     = new MutableLiveData<>("");
    private final MutableLiveData<UiState<ComparisonModel>>          comparison     = new MutableLiveData<>();
    private final MutableLiveData<LocationSnapshot>                  selfLocation   = new MutableLiveData<>();
    private final MutableLiveData<LocationSnapshot>                  centerLocation = new MutableLiveData<>();

    private final IWeatherRepository   repo;
    private final IGeocodingRepository geocoder;
    private List<HourlyEntryModel>     hourlyCache;

    public WeatherViewModel(IWeatherRepository repo, IGeocodingRepository geocoder) {
        this.repo     = repo;
        this.geocoder = geocoder;
        if (repo instanceof CachingWeatherRepository) {
            ((CachingWeatherRepository) repo).setCacheStatusListener(
                    (status, label) -> cacheBadge.setValue(label));
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void loadWeather(double lat, double lon, LocationSource source) {
        if (repo instanceof CachingWeatherRepository)
            ((CachingWeatherRepository) repo).setCurrentSource(source);
        currentWeather.setValue(UiState.loading());
        repo.getCurrentWeather(lat, lon, new IWeatherRepository.Callback<WeatherModel>() {
            @Override public void onSuccess(WeatherModel result) {
                currentWeather.setValue(UiState.success(result));
                resolveLocation(lat, lon, source);
                loadForecast(lat, lon);
            }
            @Override public void onError(String msg) {
                currentWeather.setValue(UiState.error(msg));
                errorMessage.setValue(msg);
            }
        });
    }

    public void loadWeatherWithFallback(double selfLat, double selfLon,
                                        double cenLat,  double cenLon) {
        boolean hasGps = !(selfLat == 0.0 && selfLon == 0.0);
        loadWeather(hasGps ? selfLat : cenLat, hasGps ? selfLon : cenLon,
                hasGps ? LocationSource.SELF_MARKER : LocationSource.MAP_CENTRE);
    }

    public void selectHour(int index) { selectedHour.setValue(index); }

    public void loadComparison(double selfLat, double selfLon,
                               double cenLat,  double cenLon) {
        comparison.setValue(UiState.loading());
        repo.getCurrentWeather(selfLat, selfLon, new IWeatherRepository.Callback<WeatherModel>() {
            @Override public void onSuccess(WeatherModel self) {
                selfLocation.setValue(new LocationSnapshot(selfLat, selfLon, null, LocationSource.SELF_MARKER));
                repo.getCurrentWeather(cenLat, cenLon, new IWeatherRepository.Callback<WeatherModel>() {
                    @Override public void onSuccess(WeatherModel centre) {
                        centerLocation.setValue(new LocationSnapshot(cenLat, cenLon, null, LocationSource.MAP_CENTRE));
                        comparison.setValue(UiState.success(new ComparisonModel(self, centre)));
                    }
                    @Override public void onError(String msg) { comparison.setValue(UiState.error(msg)); }
                });
            }
            @Override public void onError(String msg) { comparison.setValue(UiState.error(msg)); }
        });
    }

    // ── LiveData getters ──────────────────────────────────────────────────────

    public LiveData<UiState<WeatherModel>>             getCurrentWeather() { return currentWeather; }
    public LiveData<UiState<List<DailyForecastModel>>> getDailyForecast()  { return dailyForecast;  }
    public LiveData<UiState<List<HourlyEntryModel>>>   getHourlyForecast() { return hourlyForecast; }
    public LiveData<LocationSnapshot>                  getActiveLocation() { return activeLocation; }
    public LiveData<Integer>                           getSelectedHour()   { return selectedHour;   }
    public LiveData<String>                            getErrorMessage()   { return errorMessage;   }
    public LiveData<String>                            getCacheBadge()     { return cacheBadge;     }
    public LiveData<UiState<ComparisonModel>>          getComparison()     { return comparison;     }
    public LiveData<LocationSnapshot>                  getSelfLocation()   { return selfLocation;   }
    public LiveData<LocationSnapshot>                  getCenterLocation() { return centerLocation; }

    // ── Private ───────────────────────────────────────────────────────────────

    private void loadForecast(double lat, double lon) {
        dailyForecast.setValue(UiState.loading());
        repo.getDailyForecast(lat, lon, new IWeatherRepository.Callback<List<DailyForecastModel>>() {
            @Override public void onSuccess(List<DailyForecastModel> result) { dailyForecast.setValue(UiState.success(result)); }
            @Override public void onError(String msg) { dailyForecast.setValue(UiState.error(msg)); }
        });
        hourlyForecast.setValue(UiState.loading());
        repo.getHourlyForecast(lat, lon, new IWeatherRepository.Callback<List<HourlyEntryModel>>() {
            @Override public void onSuccess(List<HourlyEntryModel> result) {
                hourlyCache = result;
                hourlyForecast.setValue(UiState.success(result));
            }
            @Override public void onError(String msg) { hourlyForecast.setValue(UiState.error(msg)); }
        });
    }

    private void resolveLocation(double lat, double lon, LocationSource source) {
        geocoder.reverseGeocode(lat, lon, source, new IGeocodingRepository.Callback() {
            @Override public void onSuccess(LocationSnapshot snapshot) { activeLocation.setValue(snapshot); }
            @Override public void onError(String msg) { /* ignore geocoding errors silently */ }
        });
    }
}
