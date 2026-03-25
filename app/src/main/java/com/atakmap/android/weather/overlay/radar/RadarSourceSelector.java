package com.atakmap.android.weather.overlay.radar;

import android.content.Context;
import android.content.SharedPreferences;

import com.atakmap.android.weather.data.remote.SourceDefinitionLoader;
import com.atakmap.android.weather.data.remote.schema.WeatherSourceDefinitionV2;
import com.atakmap.coremap.log.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Manages available radar source definitions and the user's active selection.
 *
 * <p>Loads v2 radar source definitions from bundled assets and external storage
 * via {@link SourceDefinitionLoader#loadAllV2(Context)}, filters for radar-type
 * sources, and persists the user's selection in SharedPreferences.</p>
 *
 * <p>If no v2 radar source is configured (or the persisted selection is invalid),
 * falls back to the first available source (typically RainViewer).</p>
 */
public class RadarSourceSelector {

    private static final String TAG = "RadarSourceSelector";
    private static final String PREFS_FILE = "weather_radar_prefs";
    private static final String KEY_ACTIVE_SOURCE = "active_radar_source";

    private final Context context;
    private final SharedPreferences prefs;
    private List<WeatherSourceDefinitionV2> radarSources = Collections.emptyList();

    public RadarSourceSelector(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }

    /**
     * Load all v2 radar source definitions.
     * Call this during initialization and when refreshing sources.
     */
    public void loadSources() {
        Map<String, WeatherSourceDefinitionV2> allV2 =
                SourceDefinitionLoader.loadAllV2(context);
        List<WeatherSourceDefinitionV2> radar = new ArrayList<>();
        for (WeatherSourceDefinitionV2 def : allV2.values()) {
            if (def.isRadarSource()) {
                radar.add(def);
            }
        }
        this.radarSources = Collections.unmodifiableList(radar);
        Log.d(TAG, "Loaded " + radar.size() + " v2 radar source definitions");
    }

    /**
     * Reload sources after clearing the cache (e.g., when user adds new files).
     */
    public void refreshSources() {
        SourceDefinitionLoader.clearCache();
        loadSources();
    }

    /**
     * Get all available v2 radar source definitions.
     *
     * @return unmodifiable list of radar sources (may be empty if not yet loaded)
     */
    public List<WeatherSourceDefinitionV2> getAvailableSources() {
        return radarSources;
    }

    /**
     * Get the active radar source based on the persisted preference.
     * Falls back to the first available source if the saved ID is not found.
     *
     * @return the active source definition, or null if no sources are loaded
     */
    public WeatherSourceDefinitionV2 getActiveSource() {
        if (radarSources.isEmpty()) return null;

        String activeId = prefs.getString(KEY_ACTIVE_SOURCE, null);
        if (activeId != null) {
            for (WeatherSourceDefinitionV2 def : radarSources) {
                String id = def.getRadarSourceId() != null
                        ? def.getRadarSourceId() : def.getSourceId();
                if (activeId.equals(id)) return def;
            }
        }
        // Fallback to first source (typically RainViewer)
        return radarSources.get(0);
    }

    /**
     * Get the index of the active source within the available sources list.
     *
     * @return index (0-based), or 0 if not found
     */
    public int getActiveSourceIndex() {
        WeatherSourceDefinitionV2 active = getActiveSource();
        if (active == null) return 0;
        String activeId = active.getRadarSourceId() != null
                ? active.getRadarSourceId() : active.getSourceId();
        for (int i = 0; i < radarSources.size(); i++) {
            WeatherSourceDefinitionV2 def = radarSources.get(i);
            String id = def.getRadarSourceId() != null
                    ? def.getRadarSourceId() : def.getSourceId();
            if (activeId != null && activeId.equals(id)) return i;
        }
        return 0;
    }

    /**
     * Set the active radar source by its ID. Persists to SharedPreferences.
     *
     * @param radarSourceId the radarSourceId (or sourceId) to activate
     */
    public void setActiveSourceId(String radarSourceId) {
        prefs.edit().putString(KEY_ACTIVE_SOURCE, radarSourceId).apply();
        Log.d(TAG, "Active radar source set to: " + radarSourceId);
    }

    /**
     * Get a human-readable info line for a source definition.
     * E.g., "RainViewer - Global - Max zoom 7 - 12 frames"
     */
    public static String getSourceInfoText(WeatherSourceDefinitionV2 def, int frameCount) {
        StringBuilder sb = new StringBuilder();
        sb.append(def.getProvider() != null ? def.getProvider() : "unknown");

        if (def.getModel() != null && def.getModel().getCoverage() != null
                && !def.getModel().getCoverage().isEmpty()) {
            sb.append(" \u2022 ").append(def.getModel().getCoverage());
        }

        sb.append(" \u2022 Max zoom ").append(def.getMaxZoom());

        if (frameCount > 0) {
            sb.append(" \u2022 ").append(frameCount).append(" frames");
        }

        return sb.toString();
    }
}
