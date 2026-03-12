package org.dtakc.weather.atak.data.preferences;

import android.content.Context;
import android.content.SharedPreferences;

import org.dtakc.weather.atak.domain.model.WeatherParameter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Persists the user's selected Open-Meteo parameter set across sessions.
 *
 * ── Sprint 2 additions ────────────────────────────────────────────────────────
 *
 * 1. ChangeListener interface
 *    OpenMeteoSource registers itself via registerChangeListener() so it
 *    is notified immediately when the user taps a parameter in Tab 4.
 *    The source marks itself stale; the next refresh picks up the new set.
 *
 * 2. buildXxxQueryParam() enforce MINIMUM_REQUIRED
 *    Even if the user deselects a parser-critical field, the query builder
 *    re-adds it from WeatherParameter.MINIMUM_REQUIRED_*. The UI shows it
 *    unchecked but the network request always includes it.
 *
 * 3. buildCurrentQueryParam() key corrected
 *    Open-Meteo API v1 uses "current" not "current_weather" for the
 *    current-conditions variable list.
 *
 * 4. getParametersForSource(List<WeatherParameter>)
 *    Returns only the parameters that a given source supports, filtered
 *    from the full WeatherParameter enum. Used by ParametersView when
 *    getSupportedParameters() is available.
 */
public class WeatherParameterPreferences {

    private static final String PREFS_NAME  = "weather_parameters";
    private static final String KEY_HOURLY  = "selected_hourly";
    private static final String KEY_DAILY   = "selected_daily";
    private static final String KEY_CURRENT = "selected_current";

    // ── Change notification ───────────────────────────────────────────────────

    public interface ChangeListener {
        /** Called on the thread that made the setSelected() call. */
        void onParameterSelectionChanged();
    }

    private final List<ChangeListener> changeListeners = new ArrayList<>();

    public void registerChangeListener(ChangeListener listener) {
        if (!changeListeners.contains(listener)) changeListeners.add(listener);
    }

    public void unregisterChangeListener(ChangeListener listener) {
        changeListeners.remove(listener);
    }

    private void notifyListeners() {
        for (ChangeListener l : changeListeners) l.onParameterSelectionChanged();
    }

    // ── Core fields ───────────────────────────────────────────────────────────

    private final SharedPreferences prefs;

    public WeatherParameterPreferences(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ── Selection persistence ─────────────────────────────────────────────────

    /** Returns true if the given parameter is currently selected. */
    public boolean isSelected(WeatherParameter param) {
        return getSelectedNames(param.category).contains(param.name());
    }

    /** Persist a parameter selection change and notify listeners. */
    public void setSelected(WeatherParameter param, boolean selected) {
        Set<String> current = new HashSet<>(getSelectedNames(param.category));
        if (selected) current.add(param.name());
        else          current.remove(param.name());
        prefs.edit().putStringSet(keyFor(param.category), current).apply();
        notifyListeners();
    }

    /** Returns all currently-selected parameters for a category. */
    public Set<WeatherParameter> getSelectedParameters(WeatherParameter.Category category) {
        Set<String> names = getSelectedNames(category);
        Set<WeatherParameter> result = new LinkedHashSet<>();
        for (WeatherParameter p : WeatherParameter.values()) {
            if (p.category == category && names.contains(p.name())) result.add(p);
        }
        return result;
    }

    // ── URL fragment builders (enforcing minimum required) ────────────────────

    /**
     * Build "&hourly=a,b,c" from selected hourly params.
     * Always includes WeatherParameter.MINIMUM_REQUIRED_HOURLY fields.
     */
    public String buildHourlyQueryParam() {
        return buildQueryParam(WeatherParameter.Category.HOURLY,
                "hourly", WeatherParameter.MINIMUM_REQUIRED_HOURLY);
    }

    /**
     * Build "&daily=a,b,c" from selected daily params.
     * Always includes WeatherParameter.MINIMUM_REQUIRED_DAILY fields.
     */
    public String buildDailyQueryParam() {
        return buildQueryParam(WeatherParameter.Category.DAILY,
                "daily", WeatherParameter.MINIMUM_REQUIRED_DAILY);
    }

    /**
     * Build "&current=a,b,c" from selected current params.
     * NOTE: Open-Meteo API v1 uses "current" (not "current_weather").
     * Always includes WeatherParameter.MINIMUM_REQUIRED_CURRENT fields.
     */
    public String buildCurrentQueryParam() {
        return buildQueryParam(WeatherParameter.Category.CURRENT,
                "current", WeatherParameter.MINIMUM_REQUIRED_CURRENT);
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private Set<String> getSelectedNames(WeatherParameter.Category category) {
        String key = keyFor(category);
        if (prefs.contains(key)) {
            // getStringSet returns a copy-on-read set; wrap it to be safe
            return new HashSet<>(prefs.getStringSet(key, new HashSet<>()));
        }
        // First run — initialise from defaultOn flags
        Set<String> defaults = new HashSet<>();
        for (WeatherParameter p : WeatherParameter.values()) {
            if (p.category == category && p.defaultOn) defaults.add(p.name());
        }
        return defaults;
    }

    /**
     * Build a query fragment merging user selections + required minimums.
     * Deduplicates by apiKey (some params share a key across categories).
     */
    private String buildQueryParam(WeatherParameter.Category category,
                                   String paramName,
                                   Set<WeatherParameter> required) {
        // Start with user selections
        Set<WeatherParameter> selected = getSelectedParameters(category);
        // Merge required params (union, order-stable)
        Set<WeatherParameter> merged = new LinkedHashSet<>(selected);
        merged.addAll(required);

        if (merged.isEmpty()) return "";

        // Deduplicate apiKeys
        Set<String> seen = new LinkedHashSet<>();
        for (WeatherParameter p : merged) seen.add(p.apiKey);

        StringBuilder sb = new StringBuilder("&").append(paramName).append("=");
        boolean first = true;
        for (String k : seen) {
            if (!first) sb.append(',');
            sb.append(k);
            first = false;
        }
        return sb.toString();
    }

    private static String keyFor(WeatherParameter.Category category) {
        switch (category) {
            case DAILY:   return KEY_DAILY;
            case CURRENT: return KEY_CURRENT;
            default:      return KEY_HOURLY;
        }
    }
}
