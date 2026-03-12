package org.dtakc.weather.atak.data.remote;

import android.content.Context;
import android.content.SharedPreferences;

import org.dtakc.weather.atak.data.remote.avwx.AviationWeatherDataSource;
import org.dtakc.weather.atak.data.remote.openmeteo.OpenMeteoDataSource;
import org.dtakc.weather.atak.data.remote.openmeteo.OpenMeteoECMWFSource;
import org.dtakc.weather.atak.data.remote.openmeteo.OpenMeteoDWDSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;   // ISS-15 fix

/**
 * Registry and active-source selector for IWeatherDataSource implementations.
 *
 * ISS-02 fix: destroyInstance() is called from WeatherPluginComponent.onDestroyImpl()
 *             so a hot-swap reinstall always gets a freshly constructed registry.
 * ISS-15 fix: internal map is ConcurrentHashMap — safe for concurrent register() calls.
 */
public final class WeatherDataSourceRegistry {

    private static final String PREFS_NAME    = "dtakc_wx_prefs";
    private static final String KEY_SOURCE_ID = "active_source_id";

    // ── Singleton ─────────────────────────────────────────────────────────────
    private static volatile WeatherDataSourceRegistry INSTANCE;

    public static WeatherDataSourceRegistry getInstance(Context ctx) {
        if (INSTANCE == null) {
            synchronized (WeatherDataSourceRegistry.class) {
                if (INSTANCE == null) {
                    Context appCtx = ctx.getApplicationContext();
                    if (appCtx == null) appCtx = ctx;
                    INSTANCE = new WeatherDataSourceRegistry(appCtx);
                }
            }
        }
        return INSTANCE;
    }

    /** Destroy singleton on plugin unload so hot-swap gets a clean instance (ISS-02). */
    public static void destroyInstance() {
        synchronized (WeatherDataSourceRegistry.class) { INSTANCE = null; }
    }

    // ── Entry type ────────────────────────────────────────────────────────────
    public static final class SourceEntry {
        public final String sourceId;
        public final String displayName;
        SourceEntry(String id, String name) { sourceId = id; displayName = name; }
        @Override public String toString() { return displayName; }
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private final Context appContext;
    private final SharedPreferences prefs;

    /** Ordered list for Spinner display; ConcurrentHashMap for thread-safe lookup. */
    private final List<String>                     order   = new ArrayList<>();
    private final ConcurrentHashMap<String, IWeatherDataSource> sources = new ConcurrentHashMap<>();

    private WeatherDataSourceRegistry(Context ctx) {
        appContext = ctx;
        prefs      = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        register(new OpenMeteoDataSource());
        register(new OpenMeteoECMWFSource());
        register(new OpenMeteoDWDSource());
        register(new AviationWeatherDataSource());
        // Ensure open-meteo is the default on first install (no saved pref yet)
        if (prefs.getString(KEY_SOURCE_ID, null) == null) {
            prefs.edit().putString(KEY_SOURCE_ID, OpenMeteoDataSource.SOURCE_ID).apply();
        }
    }

    /** Register a source. The first registered becomes default if no prefs saved yet. */
    public synchronized void register(IWeatherDataSource src) {
        String id = src.getSourceId();
        if (!order.contains(id)) order.add(id);
        sources.put(id, src);
    }

    public IWeatherDataSource getActiveSource() {
        String id = prefs.getString(KEY_SOURCE_ID, null);
        if (id != null && sources.containsKey(id)) return sources.get(id);
        if (!order.isEmpty()) return sources.get(order.get(0));
        throw new IllegalStateException("No weather sources registered");
    }

    public void setActiveSourceId(String id) {
        prefs.edit().putString(KEY_SOURCE_ID, id).apply();
    }

    public String getActiveSourceId() {
        String id = prefs.getString(KEY_SOURCE_ID, null);
        return (id == null && !order.isEmpty()) ? order.get(0) : id;
    }

    public IWeatherDataSource getSourceById(String id) { return sources.get(id); }

    public List<SourceEntry> getAvailableEntries() {
        List<SourceEntry> list = new ArrayList<>();
        for (String id : order) {
            IWeatherDataSource s = sources.get(id);
            if (s != null) list.add(new SourceEntry(id, s.getDisplayName()));
        }
        return Collections.unmodifiableList(list);
    }

    public int getActiveSourceIndex() {
        String active = getActiveSourceId();
        int i = 0;
        for (String id : order) { if (id.equals(active)) return i; i++; }
        return 0;
    }

    public int getIndexForSourceId(String id) {
        int i = 0;
        for (String s : order) { if (s.equals(id)) return i; i++; }
        return -1;
    }
}
