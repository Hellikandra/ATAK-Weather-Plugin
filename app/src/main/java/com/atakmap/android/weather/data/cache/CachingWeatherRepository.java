package com.atakmap.android.weather.data.cache;

import android.os.Handler;
import android.os.Looper;

import com.atakmap.android.weather.data.WeatherRepositoryImpl;
import com.atakmap.android.weather.data.cache.entity.DailyEntry;
import com.atakmap.android.weather.data.cache.entity.HourlyEntry;
import com.atakmap.android.weather.data.cache.entity.WeatherSnapshot;
import com.atakmap.android.weather.domain.model.DailyForecastModel;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.LocationSource;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.model.WindProfileModel;
import com.atakmap.android.weather.domain.repository.IWeatherRepository;
import com.atakmap.android.weather.infrastructure.preferences.WeatherParameterPreferences;
import com.atakmap.coremap.log.Log;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cache-first {@link IWeatherRepository} that wraps {@link WeatherRepositoryImpl}.
 *
 * <h3>Read path</h3>
 * <ol>
 *   <li>Check Room on a background thread for a fresh snapshot (within TTL).</li>
 *   <li>Hit  → emit domain model from cache; post {@link CacheStatus#CACHED} badge.</li>
 *   <li>Miss → fetch from API → write Room → emit domain model; clear badge.</li>
 * </ol>
 *
 * <h3>Offline path</h3>
 * If the API call fails AND an expired snapshot exists, serve stale data with
 * a {@link CacheStatus#STALE} badge rather than surfacing an error to the user.
 *
 * <h3>Wind profile</h3>
 * Not cached in Room (data changes rapidly). An in-memory
 * {@link ConcurrentHashMap} with a 30-minute TTL is used instead.
 *
 * <h3>Refactoring changes (vs original)</h3>
 * <ul>
 *   <li>Now implements {@link CacheStatusProvider} — removes the
 *       {@code instanceof CachingWeatherRepository} check from WeatherViewModel.</li>
 *   <li>{@code windCache} changed from {@code HashMap} to {@code ConcurrentHashMap}
 *       to eliminate the data-race on the (potentially multi-threaded) executor.</li>
 *   <li>{@code postStatus()} centralised — all paths go through the same helper.</li>
 * </ul>
 */
public class CachingWeatherRepository implements IWeatherRepository, CacheStatusProvider {

    private static final String TAG = "CachingWeatherRepo";

    // ── Cache status broadcast ─────────────────────────────────────────────────

    public enum CacheStatus { FRESH, CACHED, STALE }

    public interface CacheStatusListener {
        void onCacheStatus(CacheStatus status, String label);
    }

    private CacheStatusListener cacheStatusListener;

    /**
     * {@inheritDoc}
     *
     * <p>Implements {@link CacheStatusProvider} — WeatherViewModel uses
     * {@code instanceof CacheStatusProvider} instead of the concrete class.</p>
     */
    @Override
    public void setCacheStatusListener(CacheStatusListener listener) {
        this.cacheStatusListener = listener;
    }

    private void postStatus(CacheStatus status, String label) {
        if (cacheStatusListener != null) {
            mainHandler.post(() -> cacheStatusListener.onCacheStatus(status, label));
        }
    }

    // ── State ──────────────────────────────────────────────────────────────────

    private final WeatherRepositoryImpl       network;
    private final WeatherDao                  dao;
    private final WeatherParameterPreferences prefs;
    private final ExecutorService             executor    = Executors.newSingleThreadExecutor();
    private final Handler                     mainHandler = new Handler(Looper.getMainLooper());

    /** LocationSource context set by WeatherViewModel before each load. */
    private LocationSource currentSource = LocationSource.SELF_MARKER;

    public CachingWeatherRepository(WeatherRepositoryImpl network,
                                    WeatherDao dao,
                                    WeatherParameterPreferences prefs) {
        this.network = network;
        this.dao     = dao;
        this.prefs   = prefs;
    }

    /** Call before loadWeather() so cached rows are keyed by the right source. */
    public void setCurrentSource(LocationSource source) {
        this.currentSource = source;
    }

    // ── IWeatherRepository ────────────────────────────────────────────────────

    @Override
    public void getCurrentWeather(double lat, double lon,
                                  Callback<WeatherModel> callback) {
        final String hash   = CachePolicy.paramHash(prefs);
        final String source = currentSource.name();

        executor.execute(() -> {
            // Fast path: source marked stale after param change → bypass cache
            if (!network.isStaleForCurrentSource()) {
                WeatherSnapshot cached = dao.findFreshSnapshot(
                        CachePolicy.roundCoord(lat), CachePolicy.roundCoord(lon),
                        source, hash, System.currentTimeMillis());

                if (cached != null) {
                    Log.d(TAG, "Cache HIT — current weather");
                    postStatus(CacheStatus.CACHED, CachePolicy.cachedLabel(cached.fetchedAt));
                    mainHandler.post(() -> callback.onSuccess(CacheMapper.toDomain(cached)));
                    return;
                }
            }

            // Cache miss or forced refresh → hit the network
            network.getCurrentWeather(lat, lon, new Callback<WeatherModel>() {
                @Override public void onSuccess(WeatherModel result) {
                    executor.execute(() -> {
                        WeatherSnapshot snap = CacheMapper.toSnapshot(
                                result, currentSource, hash,
                                null,   // display name written separately via geocoding
                                System.currentTimeMillis());
                        snap.lat = CachePolicy.roundCoord(lat);
                        snap.lon = CachePolicy.roundCoord(lon);
                        dao.insertSnapshot(snap);
                    });
                    postStatus(CacheStatus.FRESH, "");
                    callback.onSuccess(result);
                }

                @Override public void onError(String message) {
                    // Offline fallback — serve expired data with stale badge
                    executor.execute(() -> {
                        WeatherSnapshot stale = dao.findLatestSnapshot(
                                CachePolicy.roundCoord(lat), CachePolicy.roundCoord(lon), source);
                        if (stale != null) {
                            Log.w(TAG, "Network error — serving stale cache");
                            postStatus(CacheStatus.STALE,
                                    CachePolicy.cachedLabel(stale.fetchedAt) + " ⚠");
                            mainHandler.post(() -> callback.onSuccess(CacheMapper.toDomain(stale)));
                        } else {
                            mainHandler.post(() -> callback.onError(message));
                        }
                    });
                }
            });
        });
    }

    @Override
    public void getDailyForecast(double lat, double lon,
                                 Callback<List<DailyForecastModel>> callback) {
        final String hash   = CachePolicy.paramHash(prefs);
        final String source = currentSource.name();

        executor.execute(() -> {
            if (!network.isStaleForCurrentSource()) {
                WeatherSnapshot snap = dao.findFreshSnapshot(
                        CachePolicy.roundCoord(lat), CachePolicy.roundCoord(lon),
                        source, hash, System.currentTimeMillis());
                if (snap != null) {
                    List<DailyEntry> rows = dao.getDailyEntries(snap.id);
                    if (!rows.isEmpty()) {
                        mainHandler.post(() -> callback.onSuccess(CacheMapper.dailyToDomain(rows)));
                        return;
                    }
                }
            }

            network.getDailyForecast(lat, lon, new Callback<List<DailyForecastModel>>() {
                @Override public void onSuccess(List<DailyForecastModel> result) {
                    executor.execute(() -> {
                        WeatherSnapshot snap = dao.findLatestSnapshot(
                                CachePolicy.roundCoord(lat), CachePolicy.roundCoord(lon), source);
                        if (snap != null && snap.paramHash.equals(hash)) {
                            List<DailyEntry> entities = CacheMapper.dailyToEntities(result);
                            for (DailyEntry e : entities) e.snapshotId = snap.id;
                            dao.insertDailyEntries(entities);
                        }
                    });
                    callback.onSuccess(result);
                }
                @Override public void onError(String msg) {
                    executor.execute(() -> {
                        WeatherSnapshot stale = dao.findLatestSnapshot(
                                CachePolicy.roundCoord(lat), CachePolicy.roundCoord(lon), source);
                        if (stale != null) {
                            List<DailyEntry> rows = dao.getDailyEntries(stale.id);
                            if (!rows.isEmpty()) {
                                mainHandler.post(() ->
                                        callback.onSuccess(CacheMapper.dailyToDomain(rows)));
                                return;
                            }
                        }
                        mainHandler.post(() -> callback.onError(msg));
                    });
                }
            });
        });
    }

    @Override
    public void getHourlyForecast(double lat, double lon,
                                  Callback<List<HourlyEntryModel>> callback) {
        final String hash   = CachePolicy.paramHash(prefs);
        final String source = currentSource.name();

        executor.execute(() -> {
            if (!network.isStaleForCurrentSource()) {
                WeatherSnapshot snap = dao.findFreshSnapshot(
                        CachePolicy.roundCoord(lat), CachePolicy.roundCoord(lon),
                        source, hash, System.currentTimeMillis());
                if (snap != null) {
                    List<HourlyEntry> rows = dao.getHourlyEntries(snap.id);
                    if (!rows.isEmpty()) {
                        mainHandler.post(() ->
                                callback.onSuccess(CacheMapper.hourlyToDomain(rows)));
                        return;
                    }
                }
            }

            network.getHourlyForecast(lat, lon, new Callback<List<HourlyEntryModel>>() {
                @Override public void onSuccess(List<HourlyEntryModel> result) {
                    executor.execute(() -> {
                        WeatherSnapshot snap = dao.findLatestSnapshot(
                                CachePolicy.roundCoord(lat), CachePolicy.roundCoord(lon), source);
                        if (snap != null && snap.paramHash.equals(hash)) {
                            List<HourlyEntry> entities = CacheMapper.hourlyToEntities(result);
                            for (HourlyEntry e : entities) e.snapshotId = snap.id;
                            dao.insertHourlyEntries(entities);
                        }
                    });
                    callback.onSuccess(result);
                }
                @Override public void onError(String msg) {
                    executor.execute(() -> {
                        WeatherSnapshot stale = dao.findLatestSnapshot(
                                CachePolicy.roundCoord(lat), CachePolicy.roundCoord(lon), source);
                        if (stale != null) {
                            List<HourlyEntry> rows = dao.getHourlyEntries(stale.id);
                            if (!rows.isEmpty()) {
                                mainHandler.post(() ->
                                        callback.onSuccess(CacheMapper.hourlyToDomain(rows)));
                                return;
                            }
                        }
                        mainHandler.post(() -> callback.onError(msg));
                    });
                }
            });
        });
    }

    // ── Wind profile in-memory cache ─────────────────────────────────────────
    //
    // ConcurrentHashMap replaces the original HashMap to eliminate the data-race:
    // getWindProfile() can be called from the main thread (cache hit path) while
    // the executor thread may concurrently write a new entry after a network fetch.

    private static final long WIND_TTL_MS = 30 * 60 * 1000L;  // 30 minutes

    private static class WindCacheEntry {
        final List<WindProfileModel> data;
        final long                   fetchedAt;

        WindCacheEntry(List<WindProfileModel> data, long fetchedAt) {
            this.data      = data;
            this.fetchedAt = fetchedAt;
        }

        boolean isFresh() {
            return (System.currentTimeMillis() - fetchedAt) < WIND_TTL_MS;
        }
    }

    /** Key: {@code "lat_lon"} rounded to 2 decimal places (~1 km grid). */
    private final ConcurrentHashMap<String, WindCacheEntry> windCache =
            new ConcurrentHashMap<>();

    private static String windKey(double lat, double lon) {
        return String.format(Locale.US, "%.2f_%.2f", lat, lon);
    }

    /**
     * Wind profile with 30-minute in-memory TTL cache.
     *
     * <p>Cache hits are served synchronously on the calling thread (main thread
     * via WindProfileViewModel) — no executor dispatch for cache hits.
     * On a miss the network call proceeds and populates the cache on success.</p>
     */
    @Override
    public void getWindProfile(double lat, double lon,
                               Callback<List<WindProfileModel>> callback) {
        final String key    = windKey(lat, lon);
        WindCacheEntry cached = windCache.get(key);
        if (cached != null && cached.isFresh()) {
            Log.d(TAG, "Wind profile cache HIT — " + key);
            callback.onSuccess(cached.data);
            return;
        }

        Log.d(TAG, "Wind profile cache MISS — fetching from network");
        network.getWindProfile(lat, lon, new Callback<List<WindProfileModel>>() {
            @Override public void onSuccess(List<WindProfileModel> result) {
                windCache.put(key, new WindCacheEntry(result, System.currentTimeMillis()));
                callback.onSuccess(result);
            }
            @Override public void onError(String message) {
                // Serve stale data on network error rather than surfacing it
                WindCacheEntry stale = windCache.get(key);
                if (stale != null) {
                    Log.w(TAG, "Wind profile network error — serving stale cache");
                    callback.onSuccess(stale.data);
                } else {
                    callback.onError(message);
                }
            }
        });
    }

    /** Purge all expired Room rows. Call on plugin open. */
    public void purgeExpired() {
        executor.execute(() -> dao.purgeExpired(System.currentTimeMillis()));
    }

    /** Evict the in-memory wind profile cache. Call on plugin dispose. */
    public void clearWindCache() {
        windCache.clear();
    }

    /** Update the display name on the most recent snapshot for a position. */
    public void updateDisplayName(double lat, double lon, LocationSource source, String name) {
        executor.execute(() -> {
            WeatherSnapshot snap = dao.findLatestSnapshot(
                    CachePolicy.roundCoord(lat), CachePolicy.roundCoord(lon), source.name());
            if (snap != null) {
                snap.locationDisplayName = name;
                dao.insertSnapshot(snap);  // REPLACE strategy updates in place
            }
        });
    }
}
