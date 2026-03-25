package com.atakmap.android.weather.data.remote;

import android.content.Context;
import android.content.SharedPreferences;

import com.atakmap.android.weather.data.remote.schema.GenericApiSource;
import com.atakmap.android.weather.data.remote.schema.WeatherSourceDefinitionV2;
import com.atakmap.coremap.log.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WeatherSourceManager — registry and selector for IWeatherRemoteSource implementations.
 *
 * ── Purpose ───────────────────────────────────────────────────────────────────
 *
 * Decouples the active weather data provider from the rest of the application.
 * The CONF tab Spinner calls setActiveSourceId(); all data loads then use
 * getActiveSource(). Adding a new provider only requires calling register().
 *
 * ── How to add a new provider ─────────────────────────────────────────────────
 *
 *   1. Implement IWeatherRemoteSource (getSourceId(), getDisplayName(), etc.)
 *   2. Call WeatherSourceManager.register(new MySource()) before use.
 *      WeatherMapComponent.onCreate() is a good place.
 *   3. The Spinner in CONF tab auto-populates from getAvailableEntries().
 *
 * ── SharedPreferences persistence ─────────────────────────────────────────────
 *
 * The selected source ID is stored under key "wx_source_id" in the
 * plugin's default SharedPreferences.  Survives app restart.
 */
public class WeatherSourceManager {

    private static final String TAG             = "WeatherSourceManager";
    private static final String PREFS_NAME      = "WeatherToolPrefs";
    private static final String KEY_SOURCE_ID   = "wx_source_id";

    // ── Source display entry (shown in Spinner) ───────────────────────────────
    public static class SourceEntry {
        public final String sourceId;
        public final String displayName;
        public SourceEntry(String sourceId, String displayName) {
            this.sourceId    = sourceId;
            this.displayName = displayName;
        }
        @Override public String toString() { return displayName; } // Spinner uses toString()
    }

    // ── Singleton ─────────────────────────────────────────────────────────────

    private static WeatherSourceManager instance;

    public static WeatherSourceManager getInstance(Context ctx) {
        if (instance == null) {
            Context appCtx = (ctx != null) ? ctx.getApplicationContext() : null;
            if (appCtx == null) appCtx = ctx;  // last resort
            instance = new WeatherSourceManager(appCtx);
        }
        return instance;
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final Context          appContext;
    private final SharedPreferences prefs;
    /** Ordered map: sourceId → source.  Insertion order = Spinner order. */
    private final Map<String, IWeatherRemoteSource> sources = new LinkedHashMap<>();

    private WeatherSourceManager(Context ctx) {
        appContext = ctx;
        prefs      = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // Register built-in providers — order = Spinner display order.
        register(new OpenMeteoSource());          // index 0 — default (GFS global)
        register(new OpenMeteoECMWFSource());     // index 1 — ECMWF pressure levels
        register(new OpenMeteoDWDSource());       // index 2 — DWD ICON high-res Europe
        register(new AviationWeatherSource());    // index 3 — AWC METAR real obs
    }

    // ── Registration ──────────────────────────────────────────────────────────

    /**
     * Register a weather source.  The first registered source becomes the
     * default if no preference has been saved yet.
     */
    public void register(IWeatherRemoteSource source) {
        sources.put(source.getSourceId(), source);
    }

    /**
     * Scan v2 JSON definitions from assets and external storage and register
     * any weather sources that are not already registered (hardcoded Java
     * sources take priority). Call this AFTER registering built-in Java sources.
     *
     * @param context Android context for asset/file access
     */
    public void registerV2Sources(Context context) {
        List<WeatherSourceDefinitionV2> v2Defs = SourceDefinitionLoader.loadV2Sources(context);
        int registered = 0;
        for (WeatherSourceDefinitionV2 def : v2Defs) {
            if (def.isWeatherSource()) {
                String id = def.getSourceId();
                if (id == null || id.isEmpty()) continue;
                // Only register if not already registered — v1 Java source takes priority
                if (getSourceById(id) == null) {
                    register(new GenericApiSource(def, context));
                    registered++;
                    Log.d(TAG, "Registered v2 source: " + id + " (" + def.getDisplayName() + ")");
                } else {
                    Log.d(TAG, "Skipped v2 source (already registered): " + id);
                }
            }
        }
        Log.d(TAG, "registerV2Sources complete: " + registered + " new sources from "
                + v2Defs.size() + " v2 definitions");
    }

    // ── Active source ─────────────────────────────────────────────────────────

    /** Returns the currently active source, falling back to the first registered. */
    public IWeatherRemoteSource getActiveSource() {
        String id = prefs.getString(KEY_SOURCE_ID, null);
        if (id != null && sources.containsKey(id)) return sources.get(id);
        // fallback: first registered
        if (!sources.isEmpty()) return sources.values().iterator().next();
        throw new IllegalStateException("No weather sources registered");
    }

    /** Persist and activate a source by ID. */
    public void setActiveSourceId(String sourceId) {
        prefs.edit().putString(KEY_SOURCE_ID, sourceId).apply();
    }

    public String getActiveSourceId() {
        String id = prefs.getString(KEY_SOURCE_ID, null);
        if (id == null && !sources.isEmpty()) return sources.keySet().iterator().next();
        return id;
    }

    // ── Spinner support ───────────────────────────────────────────────────────

    /** Returns ordered list of SourceEntry for populating the Spinner. */
    public List<SourceEntry> getAvailableEntries() {
        List<SourceEntry> list = new ArrayList<>();
        for (IWeatherRemoteSource s : sources.values())
            list.add(new SourceEntry(s.getSourceId(), s.getDisplayName()));
        return Collections.unmodifiableList(list);
    }

    /** Returns the IWeatherRemoteSource for the given ID, or null if not registered. */
    public IWeatherRemoteSource getSourceById(String sourceId) {
        return sources.get(sourceId);
    }

    /** Total number of registered sources. */
    public int getSourceCount() { return sources.size(); }

    /** Returns the index of the active source in getAvailableEntries(). */
    public int getActiveSourceIndex() {
        String activeId = getActiveSourceId();
        int i = 0;
        for (String id : sources.keySet()) {
            if (id.equals(activeId)) return i;
            i++;
        }
        return 0;
    }

    /** Returns the index of any source by ID, or -1 if not found. */
    public int getIndexForSourceId(String sourceId) {
        int i = 0;
        for (String id : sources.keySet()) {
            if (id.equals(sourceId)) return i;
            i++;
        }
        return -1;
    }
}
