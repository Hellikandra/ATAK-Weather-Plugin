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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cache-first IWeatherRepository that wraps WeatherRepositoryImpl.
 *
 * ── Read path ────────────────────────────────────────────────────────────────
 *
 *  1. Check Room on a background thread for a fresh snapshot (within TTL).
 *  2a. Hit  → emit domain model from cache; post CacheStatus.CACHED badge.
 *  2b. Miss → fetch from API → write Room → emit domain model; clear badge.
 *
 * ── Offline path ──────────────────────────────────────────────────────────────
 *
 *  If the API call fails AND an expired snapshot exists for this key,
 *  serve the stale data with a "Cached HH:MM" badge rather than showing an error.
 *
 * ── Stale-on-param-change path ────────────────────────────────────────────────
 *
 *  OpenMeteoSource.isStale() is checked before the cache lookup. If true the
 *  cache is bypassed unconditionally and the API is called fresh.
 *
 * ── Wind profile ──────────────────────────────────────────────────────────────
 *
 *  Not cached (wind data changes rapidly and the table is large). Always live.
 *
 * ── CacheStatus LiveData ──────────────────────────────────────────────────────
 *
 *  The WeatherViewModel observes getCacheStatus() to show/hide the badge.
 *  FRESH  = no badge
 *  CACHED = "Cached HH:MM"
 *  STALE  = "Cached HH:MM ⚠" (serving expired data because offline)
 */
public class CachingWeatherRepository implements IWeatherRepository {

    private static final String TAG = "CachingWeatherRepo";

    // ── Cache status broadcast ────────────────────────────────────────────────

    public enum CacheStatus { FRESH, CACHED, STALE }

    public interface CacheStatusListener {
        void onCacheStatus(CacheStatus status, String label);
    }

    private CacheStatusListener cacheStatusListener;

    public void setCacheStatusListener(CacheStatusListener l) {
        this.cacheStatusListener = l;
    }

    private void postStatus(CacheStatus status, String label) {
        if (cacheStatusListener != null) {
            mainHandler.post(() -> cacheStatusListener.onCacheStatus(status, label));
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private final WeatherRepositoryImpl        network;
    private final WeatherDao                   dao;
    private final WeatherParameterPreferences  prefs;
    private final ExecutorService              executor = Executors.newSingleThreadExecutor();
    private final Handler                      mainHandler = new Handler(Looper.getMainLooper());

    // LocationSource context set by WeatherViewModel before each load
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

    // ── IWeatherRepository ───────────────────────────────────────────────────

    @Override
    public void getCurrentWeather(double lat, double lon,
                                  Callback<WeatherModel> callback) {
        String hash   = CachePolicy.paramHash(prefs);
        String source = currentSource.name();

        executor.execute(() -> {
            // Fast path: source marked stale after param change → bypass cache
            boolean forceRefresh = network.isStaleForCurrentSource();

            if (!forceRefresh) {
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
                    // Write to cache asynchronously (already on executor thread via network callback)
                    executor.execute(() -> {
                        WeatherSnapshot snap = CacheMapper.toSnapshot(
                                result, currentSource, hash,
                                null,  // display name written separately via geocoding
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
                            postStatus(CacheStatus.STALE, CachePolicy.cachedLabel(stale.fetchedAt) + " ⚠");
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
        String hash   = CachePolicy.paramHash(prefs);
        String source = currentSource.name();

        executor.execute(() -> {
            if (!network.isStaleForCurrentSource()) {
                WeatherSnapshot snap = dao.findFreshSnapshot(
                        CachePolicy.roundCoord(lat), CachePolicy.roundCoord(lon),
                        source, hash, System.currentTimeMillis());
                if (snap != null) {
                    List<DailyEntry> rows = dao.getDailyEntries(snap.id);
                    if (!rows.isEmpty()) {
                        mainHandler.post(() ->
                                callback.onSuccess(CacheMapper.dailyToDomain(rows)));
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
        String hash   = CachePolicy.paramHash(prefs);
        String source = currentSource.name();

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

    /**
     * Wind profile is never cached — always live network.
     */
    @Override
    public void getWindProfile(double lat, double lon,
                               Callback<List<WindProfileModel>> callback) {
        network.getWindProfile(lat, lon, callback);
    }

    /** Purge all expired rows. Call on plugin open. */
    public void purgeExpired() {
        executor.execute(() -> dao.purgeExpired(System.currentTimeMillis()));
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
