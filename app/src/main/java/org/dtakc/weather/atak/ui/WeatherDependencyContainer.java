package org.dtakc.weather.atak.ui;

import android.content.Context;

import com.atakmap.android.maps.MapView;

import org.dtakc.weather.atak.data.geocoding.NominatimGeocodingSource;
import org.dtakc.weather.atak.data.local.CachingWeatherRepository;
import org.dtakc.weather.atak.data.local.WeatherDatabase;
import org.dtakc.weather.atak.data.preferences.WeatherParameterPreferences;
import org.dtakc.weather.atak.data.remote.IWeatherDataSource;
import org.dtakc.weather.atak.data.remote.NetworkWeatherRepository;
import org.dtakc.weather.atak.data.remote.WeatherDataSourceRegistry;
import org.dtakc.weather.atak.domain.repository.IGeocodingRepository;

import java.util.HashMap;
import java.util.Map;

/**
 * Lightweight DI container for the weather UI layer.
 * Created once in WeatherDropDownController and shared with all tab Presenters.
 * Destroyed (released) from disposeImpl().
 */
public final class WeatherDependencyContainer {

    public final WeatherViewModel       weatherViewModel;
    public final WindProfileViewModel   windViewModel;
    public final WeatherParameterPreferences paramPrefs;
    public final CachingWeatherRepository cachingRepo;
    public final NetworkWeatherRepository networkRepo;

    public WeatherDependencyContainer(Context pluginContext, MapView mapView) {
        Context appCtx = mapView.getContext();
        WeatherDataSourceRegistry registry = WeatherDataSourceRegistry.getInstance(appCtx);

        Map<String, IWeatherDataSource> srcMap = new HashMap<>();
        for (WeatherDataSourceRegistry.SourceEntry e : registry.getAvailableEntries()) {
            IWeatherDataSource s = registry.getSourceById(e.sourceId);
            if (s != null) srcMap.put(e.sourceId, s);
        }

        networkRepo = new NetworkWeatherRepository(srcMap, registry.getActiveSourceId());
        IGeocodingRepository geocodingRepo = new NominatimGeocodingSource();
        paramPrefs = new WeatherParameterPreferences(pluginContext);
        networkRepo.setParameterPreferences(paramPrefs);

        cachingRepo = new CachingWeatherRepository(
                networkRepo,
                WeatherDatabase.getInstance(appCtx).weatherDao(),
                paramPrefs);
        cachingRepo.purgeExpired();

        weatherViewModel = new WeatherViewModel(cachingRepo, geocodingRepo);
        windViewModel    = new WindProfileViewModel(cachingRepo);
    }

    /** Release resources on plugin unload. */
    public void dispose() {
        cachingRepo.clearWindCache();
        WeatherDataSourceRegistry.destroyInstance();
    }
}
