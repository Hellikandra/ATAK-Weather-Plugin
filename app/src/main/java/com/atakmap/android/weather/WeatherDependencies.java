package com.atakmap.android.weather;

import android.content.Context;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.weather.data.WeatherRepositoryImpl;
import com.atakmap.android.weather.data.cache.CachingWeatherRepository;
import com.atakmap.android.weather.data.cache.WeatherDatabase;
import com.atakmap.android.weather.data.geocoding.NominatimGeocodingSource;
import com.atakmap.android.weather.data.remote.IWeatherRemoteSource;
import com.atakmap.android.weather.data.remote.WeatherSourceManager;
import com.atakmap.android.weather.data.remote.schema.PreferencesApiKeyStore;
import com.atakmap.android.weather.domain.repository.ApiKeyStore;
import com.atakmap.android.weather.domain.repository.IGeocodingRepository;
import com.atakmap.android.weather.infrastructure.preferences.WeatherParameterPreferences;
import com.atakmap.coremap.log.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * WeatherDependencies — the plugin's composition root.
 *
 * <p>Every data-layer object is built here, once, and handed down by constructor.
 * Nothing below this class calls {@code new} on a repository, a source map or a
 * preferences object.</p>
 *
 * <h3>Why this exists (finding F20)</h3>
 *
 * Before this class, the graph was built <b>twice</b>: once in
 * {@link WeatherMapComponent#onCreate} to feed {@code WindProfileViewModel}, and
 * again in {@code WeatherDropDownReceiver.initDependencies()} to feed
 * {@code WeatherViewModel}. The two halves of the UI therefore ran on two
 * independent {@link CachingWeatherRepository} instances over the same Room
 * database, which meant:
 *
 * <ul>
 *   <li>the same location was fetched twice against an API that returns HTTP 429
 *       after roughly ten requests a minute;</li>
 *   <li>{@code purgeExpired()} ran twice on startup;</li>
 *   <li>two {@link WeatherParameterPreferences} instances existed, each with its
 *       own listener list, so a parameter the user ticked invalidated only one
 *       half of the UI;</li>
 *   <li>{@code setActiveSource()} was only ever called on the drop-down's copy, so
 *       changing the weather source left the Wind tab reading the previous one;</li>
 *   <li>two never-shut-down executor threads existed instead of one.</li>
 * </ul>
 *
 * <h3>Context discipline</h3>
 *
 * Everything built here needs the <b>host</b> context ({@code mapView.getContext()})
 * because it all touches on-disk storage — SharedPreferences and SQLite. The plugin
 * context has no data directory. See the "ATAK Plugin Context" rule in CLAUDE.md;
 * getting this wrong is issue #18.
 *
 * <h3>Lifetime</h3>
 *
 * Created in {@code onCreate} and valid for as long as the plugin is loaded. It is
 * deliberately not disposable: teardown of the pieces it holds is already handled by
 * {@code WeatherMapComponent.onDestroyImpl} (which closes the database) and by the
 * drop-down's {@code disposeImpl} (which evicts in-memory caches).
 */
public final class WeatherDependencies {

    private static final String TAG = "WeatherDependencies";

    private final WeatherSourceManager        sourceManager;
    private final WeatherParameterPreferences parameterPreferences;
    private final WeatherRepositoryImpl       networkRepository;
    private final CachingWeatherRepository    repository;
    private final IGeocodingRepository        geocoding;
    private final ApiKeyStore                 apiKeyStore;

    private WeatherDependencies(WeatherSourceManager sourceManager,
                                WeatherParameterPreferences parameterPreferences,
                                WeatherRepositoryImpl networkRepository,
                                CachingWeatherRepository repository,
                                IGeocodingRepository geocoding,
                                ApiKeyStore apiKeyStore) {
        this.sourceManager        = sourceManager;
        this.parameterPreferences = parameterPreferences;
        this.networkRepository    = networkRepository;
        this.repository           = repository;
        this.geocoding            = geocoding;
        this.apiKeyStore          = apiKeyStore;
    }

    /**
     * Build the graph. Call exactly once, from {@code WeatherMapComponent.onCreate}.
     *
     * @param mapView the host MapView; {@code mapView.getContext()} is the host
     *                activity context that every storage-backed object below needs.
     */
    public static WeatherDependencies create(final MapView mapView) {
        final Context hostContext = mapView.getContext();

        final WeatherSourceManager sourceManager =
                WeatherSourceManager.getInstance(hostContext);
        // Register the v2 JSON-driven sources after the built-in Java ones.
        sourceManager.registerV2Sources(hostContext);
        Log.d(TAG, "v2 sources registered; total sources: " + sourceManager.getSourceCount());

        final Map<String, IWeatherRemoteSource> sources = new HashMap<>();
        for (WeatherSourceManager.SourceEntry entry : sourceManager.getAvailableEntries()) {
            IWeatherRemoteSource src = sourceManager.getSourceById(entry.sourceId);
            if (src != null) sources.put(entry.sourceId, src);
        }

        final WeatherParameterPreferences parameterPreferences =
                new WeatherParameterPreferences(hostContext);

        final WeatherRepositoryImpl networkRepository =
                new WeatherRepositoryImpl(sources, sourceManager.getActiveSourceId());
        networkRepository.setParameterPreferences(parameterPreferences);

        final CachingWeatherRepository repository = new CachingWeatherRepository(
                networkRepository,
                WeatherDatabase.getInstance(hostContext).weatherDao(),
                parameterPreferences);
        repository.purgeExpired();

        final IGeocodingRepository geocoding = new NominatimGeocodingSource();

        // One place API keys are written and read (finding F35). Built here so
        // the settings screens receive it rather than each inventing its own.
        final ApiKeyStore apiKeyStore = new PreferencesApiKeyStore(hostContext);

        Log.d(TAG, "Dependency graph built — " + sources.size()
                + " sources, active=" + sourceManager.getActiveSourceId());

        return new WeatherDependencies(sourceManager, parameterPreferences,
                networkRepository, repository, geocoding, apiKeyStore);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /** The source registry. Prefer this over {@code WeatherSourceManager.getInstance}. */
    public WeatherSourceManager sourceManager() { return sourceManager; }

    /** User's selected Open-Meteo parameter set, backed by the host's SharedPreferences. */
    public WeatherParameterPreferences parameterPreferences() { return parameterPreferences; }

    /**
     * The network-only repository. Exposed solely so the source spinner can call
     * {@code setActiveSource(...)}; read paths should use {@link #repository()}.
     */
    public WeatherRepositoryImpl networkRepository() { return networkRepository; }

    /** The cache-through repository every read path should use. */
    public CachingWeatherRepository repository() { return repository; }

    /** Reverse geocoding for the location header. */
    public IGeocodingRepository geocoding() { return geocoding; }

    /** Per-source API keys. The settings screens read and write through this. */
    public ApiKeyStore apiKeyStore() { return apiKeyStore; }
}
