package org.dtakc.weather.atak.data.local;

import android.os.Handler;
import android.os.Looper;

import org.dtakc.weather.atak.data.local.entity.DailyEntry;
import org.dtakc.weather.atak.data.local.entity.HourlyEntry;
import org.dtakc.weather.atak.data.local.entity.WeatherSnapshot;
import org.dtakc.weather.atak.data.preferences.WeatherParameterPreferences;
import org.dtakc.weather.atak.data.remote.NetworkWeatherRepository;
import org.dtakc.weather.atak.domain.model.DailyForecastModel;
import org.dtakc.weather.atak.domain.model.HourlyEntryModel;
import org.dtakc.weather.atak.domain.model.LocationSource;
import org.dtakc.weather.atak.domain.model.WeatherModel;
import org.dtakc.weather.atak.domain.model.WindProfileModel;
import org.dtakc.weather.atak.domain.repository.IWeatherRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Decorator: cache-first IWeatherRepository wrapping NetworkWeatherRepository.
 *
 * ISS-04 fix: wind profile always dispatched through the executor thread —
 *             no synchronous main-thread cache check. Callback always fires
 *             on the main thread via mainHandler.post().
 * ISS-07 fix: no nested executor.execute() inside network callbacks.
 *             Cache writes use a dedicated writeExecutor (single-thread).
 */
public final class CachingWeatherRepository implements IWeatherRepository {

    public enum CacheStatus { FRESH, CACHED, STALE }

    public interface CacheStatusListener {
        void onCacheStatus(CacheStatus status, String label);
    }

    private CacheStatusListener cacheStatusListener;
    public void setCacheStatusListener(CacheStatusListener l) { cacheStatusListener = l; }

    // ── Executors (ISS-07: separate read/write) ───────────────────────────────
    private final ExecutorService readExecutor  = Executors.newSingleThreadExecutor();
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler   = new Handler(Looper.getMainLooper());

    private final NetworkWeatherRepository   network;
    private final WeatherDao                 dao;
    private final WeatherParameterPreferences prefs;

    private LocationSource currentSource = LocationSource.SELF_MARKER;

    public CachingWeatherRepository(NetworkWeatherRepository network,
                                    WeatherDao dao,
                                    WeatherParameterPreferences prefs) {
        this.network = network;
        this.dao     = dao;
        this.prefs   = prefs;
    }

    public void setCurrentSource(LocationSource source) { currentSource = source; }

    // ── IWeatherRepository ───────────────────────────────────────────────────

    @Override
    public void getCurrentWeather(double lat, double lon, Callback<WeatherModel> cb) {
        String hash   = CachePolicy.paramHash(prefs);
        String source = currentSource.name();
        readExecutor.execute(() -> {
            boolean forceRefresh = network.isStaleForCurrentSource();
            if (!forceRefresh) {
                WeatherSnapshot cached = dao.findFreshSnapshot(
                        CachePolicy.roundCoord(lat), CachePolicy.roundCoord(lon),
                        source, hash, System.currentTimeMillis());
                if (cached != null) {
                    postStatus(CacheStatus.CACHED, CachePolicy.cachedLabel(cached.fetchedAt));
                    mainHandler.post(() -> cb.onSuccess(CacheMapper.toDomain(cached)));
                    return;
                }
            }
            network.getCurrentWeather(lat, lon, new Callback<WeatherModel>() {
                @Override public void onSuccess(WeatherModel result) {
                    // ISS-07: write on separate writeExecutor, NOT nested in readExecutor
                    writeExecutor.execute(() -> {
                        WeatherSnapshot snap = CacheMapper.toSnapshot(
                                result, currentSource, hash, null, System.currentTimeMillis());
                        snap.lat = CachePolicy.roundCoord(lat);
                        snap.lon = CachePolicy.roundCoord(lon);
                        dao.insertSnapshot(snap);
                    });
                    postStatus(CacheStatus.FRESH, "");
                    mainHandler.post(() -> cb.onSuccess(result));
                }
                @Override public void onError(String message) {
                    readExecutor.execute(() -> {
                        WeatherSnapshot stale = dao.findLatestSnapshot(
                                CachePolicy.roundCoord(lat), CachePolicy.roundCoord(lon), source);
                        if (stale != null) {
                            postStatus(CacheStatus.STALE, CachePolicy.cachedLabel(stale.fetchedAt) + " ⚠");
                            mainHandler.post(() -> cb.onSuccess(CacheMapper.toDomain(stale)));
                        } else {
                            mainHandler.post(() -> cb.onError(message));
                        }
                    });
                }
            });
        });
    }

    @Override
    public void getDailyForecast(double lat, double lon, Callback<List<DailyForecastModel>> cb) {
        String hash = CachePolicy.paramHash(prefs); String source = currentSource.name();
        readExecutor.execute(() -> {
            if (!network.isStaleForCurrentSource()) {
                WeatherSnapshot snap = dao.findFreshSnapshot(
                        CachePolicy.roundCoord(lat), CachePolicy.roundCoord(lon),
                        source, hash, System.currentTimeMillis());
                if (snap != null) {
                    List<DailyEntry> rows = dao.getDailyEntries(snap.id);
                    if (!rows.isEmpty()) { mainHandler.post(() -> cb.onSuccess(CacheMapper.dailyToDomain(rows))); return; }
                }
            }
            network.getDailyForecast(lat, lon, new Callback<List<DailyForecastModel>>() {
                @Override public void onSuccess(List<DailyForecastModel> result) {
                    writeExecutor.execute(() -> {
                        WeatherSnapshot snap = dao.findLatestSnapshot(
                                CachePolicy.roundCoord(lat), CachePolicy.roundCoord(lon), source);
                        if (snap != null && snap.paramHash.equals(hash)) {
                            List<DailyEntry> ents = CacheMapper.dailyToEntities(result);
                            for (DailyEntry e : ents) e.snapshotId = snap.id;
                            dao.insertDailyEntries(ents);
                        }
                    });
                    mainHandler.post(() -> cb.onSuccess(result));
                }
                @Override public void onError(String msg) {
                    readExecutor.execute(() -> {
                        WeatherSnapshot stale = dao.findLatestSnapshot(
                                CachePolicy.roundCoord(lat), CachePolicy.roundCoord(lon), source);
                        if (stale != null) {
                            List<DailyEntry> rows = dao.getDailyEntries(stale.id);
                            if (!rows.isEmpty()) { mainHandler.post(() -> cb.onSuccess(CacheMapper.dailyToDomain(rows))); return; }
                        }
                        mainHandler.post(() -> cb.onError(msg));
                    });
                }
            });
        });
    }

    @Override
    public void getHourlyForecast(double lat, double lon, Callback<List<HourlyEntryModel>> cb) {
        String hash = CachePolicy.paramHash(prefs); String source = currentSource.name();
        readExecutor.execute(() -> {
            if (!network.isStaleForCurrentSource()) {
                WeatherSnapshot snap = dao.findFreshSnapshot(
                        CachePolicy.roundCoord(lat), CachePolicy.roundCoord(lon),
                        source, hash, System.currentTimeMillis());
                if (snap != null) {
                    List<HourlyEntry> rows = dao.getHourlyEntries(snap.id);
                    if (!rows.isEmpty()) { mainHandler.post(() -> cb.onSuccess(CacheMapper.hourlyToDomain(rows))); return; }
                }
            }
            network.getHourlyForecast(lat, lon, new Callback<List<HourlyEntryModel>>() {
                @Override public void onSuccess(List<HourlyEntryModel> result) {
                    writeExecutor.execute(() -> {
                        WeatherSnapshot snap = dao.findLatestSnapshot(
                                CachePolicy.roundCoord(lat), CachePolicy.roundCoord(lon), source);
                        if (snap != null && snap.paramHash.equals(hash)) {
                            List<HourlyEntry> ents = CacheMapper.hourlyToEntities(result);
                            for (HourlyEntry e : ents) e.snapshotId = snap.id;
                            dao.insertHourlyEntries(ents);
                        }
                    });
                    mainHandler.post(() -> cb.onSuccess(result));
                }
                @Override public void onError(String msg) {
                    readExecutor.execute(() -> {
                        WeatherSnapshot stale = dao.findLatestSnapshot(
                                CachePolicy.roundCoord(lat), CachePolicy.roundCoord(lon), source);
                        if (stale != null) {
                            List<HourlyEntry> rows = dao.getHourlyEntries(stale.id);
                            if (!rows.isEmpty()) { mainHandler.post(() -> cb.onSuccess(CacheMapper.hourlyToDomain(rows))); return; }
                        }
                        mainHandler.post(() -> cb.onError(msg));
                    });
                }
            });
        });
    }

    // ── Wind profile (ISS-04: fully async, callback always on main thread) ────
    private static final long WIND_TTL_MS = 30 * 60 * 1000L;

    private static class WindCacheEntry {
        final List<WindProfileModel> data;
        final long fetchedAt;
        WindCacheEntry(List<WindProfileModel> data, long ts) { this.data = data; fetchedAt = ts; }
        boolean isFresh() { return (System.currentTimeMillis() - fetchedAt) < WIND_TTL_MS; }
    }

    private final Map<String, WindCacheEntry> windCache = new HashMap<>();

    private static String windKey(double lat, double lon) {
        return String.format(Locale.US, "%.2f_%.2f", lat, lon);
    }

    @Override
    public void getWindProfile(double lat, double lon, Callback<List<WindProfileModel>> cb) {
        // ISS-04 fix: ALL paths (cache hit and miss) go through readExecutor
        //             so callback always fires asynchronously on main thread.
        readExecutor.execute(() -> {
            String key = windKey(lat, lon);
            WindCacheEntry cached = windCache.get(key);
            if (cached != null && cached.isFresh()) {
                mainHandler.post(() -> cb.onSuccess(cached.data));
                return;
            }
            network.getWindProfile(lat, lon, new Callback<List<WindProfileModel>>() {
                @Override public void onSuccess(List<WindProfileModel> result) {
                    windCache.put(key, new WindCacheEntry(result, System.currentTimeMillis()));
                    mainHandler.post(() -> cb.onSuccess(result));
                }
                @Override public void onError(String message) {
                    WindCacheEntry stale = windCache.get(key);
                    if (stale != null) mainHandler.post(() -> cb.onSuccess(stale.data));
                    else               mainHandler.post(() -> cb.onError(message));
                }
            });
        });
    }

    // ── Housekeeping ──────────────────────────────────────────────────────────

    public void purgeExpired() {
        writeExecutor.execute(() -> dao.purgeExpired(System.currentTimeMillis()));
    }

    /** Evict in-memory wind cache. Call on plugin dispose. */
    public void clearWindCache() { windCache.clear(); }

    // ── Private ───────────────────────────────────────────────────────────────

    private void postStatus(CacheStatus status, String label) {
        if (cacheStatusListener != null)
            mainHandler.post(() -> cacheStatusListener.onCacheStatus(status, label));
    }
}
