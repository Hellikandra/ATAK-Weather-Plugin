package com.atakmap.android.weather.infrastructure.preferences;

import android.content.Context;
import android.content.SharedPreferences;

import com.atakmap.android.weather.domain.model.WeatherParameter;

import java.util.HashSet;
import java.util.Set;

/**
 * Persists the user's selected Open-Meteo parameter set across sessions.
 *
 * Backed by SharedPreferences. Each WeatherParameter is stored by its
 * enum name as a member of a string set per category key.
 */
public class WeatherParameterPreferences {

    private static final String PREFS_NAME    = "weather_parameters";
    private static final String KEY_HOURLY    = "selected_hourly";
    private static final String KEY_DAILY     = "selected_daily";
    private static final String KEY_CURRENT   = "selected_current";

    private final SharedPreferences prefs;

    public WeatherParameterPreferences(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Returns true if the given parameter is currently selected. */
    public boolean isSelected(WeatherParameter param) {
        Set<String> selected = getSelected(param.category);
        return selected.contains(param.name());
    }

    /** Persist a parameter selection change. */
    public void setSelected(WeatherParameter param, boolean selected) {
        Set<String> current = new HashSet<>(getSelected(param.category));
        if (selected) current.add(param.name());
        else          current.remove(param.name());
        prefs.edit().putStringSet(keyFor(param.category), current).apply();
    }

    /** Returns all currently selected parameters for a category. */
    public Set<WeatherParameter> getSelectedParameters(WeatherParameter.Category category) {
        Set<String> names = getSelected(category);
        Set<WeatherParameter> result = new HashSet<>();
        for (WeatherParameter p : WeatherParameter.values()) {
            if (p.category == category && names.contains(p.name())) {
                result.add(p);
            }
        }
        return result;
    }

    /**
     * Build the hourly= query string fragment from selected hourly params.
     * e.g. "&hourly=temperature_2m,windspeed_10m,weathercode"
     */
    public String buildHourlyQueryParam() {
        return buildQueryParam(WeatherParameter.Category.HOURLY, "hourly");
    }

    public String buildDailyQueryParam() {
        return buildQueryParam(WeatherParameter.Category.DAILY, "daily");
    }

    public String buildCurrentQueryParam() {
        return buildQueryParam(WeatherParameter.Category.CURRENT, "current_weather");
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private Set<String> getSelected(WeatherParameter.Category category) {
        String key = keyFor(category);
        if (prefs.contains(key)) {
            return prefs.getStringSet(key, new HashSet<>());
        }
        // First run — return defaults
        Set<String> defaults = new HashSet<>();
        for (WeatherParameter p : WeatherParameter.values()) {
            if (p.category == category && p.defaultOn) {
                defaults.add(p.name());
            }
        }
        return defaults;
    }

    private String buildQueryParam(WeatherParameter.Category category, String paramName) {
        Set<WeatherParameter> selected = getSelectedParameters(category);
        if (selected.isEmpty()) return "";

        // Deduplicate API keys (some params share a key across categories)
        Set<String> keys = new HashSet<>();
        for (WeatherParameter p : selected) keys.add(p.apiKey);

        StringBuilder sb = new StringBuilder("&").append(paramName).append("=");
        boolean first = true;
        for (String key : keys) {
            if (!first) sb.append(',');
            sb.append(key);
            first = false;
        }
        return sb.toString();
    }

    private static String keyFor(WeatherParameter.Category category) {
        switch (category) {
            case HOURLY:  return KEY_HOURLY;
            case DAILY:   return KEY_DAILY;
            case CURRENT: return KEY_CURRENT;
            default:      return KEY_HOURLY;
        }
    }
}
