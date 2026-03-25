package com.atakmap.android.weather.infrastructure.preferences;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.Preference;
import android.widget.Toast;

import com.atakmap.android.gui.PanListPreference;
import com.atakmap.android.preference.PluginPreferenceFragment;
import com.atakmap.android.weather.plugin.R;
import com.atakmap.android.weather.util.UnitSystem;
import com.atakmap.android.weather.util.WeatherUnitConverter;
import com.atakmap.coremap.log.Log;

/**
 * ATAK Tool Preferences fragment for the WeatherTool plugin.
 *
 * Accessed via ATAK → Settings → Tool Preferences → WeatherTool.
 * Manages:
 *   • Unit system preset (Metric / Imperial / Aviation)
 *   • Individual unit selection (temp, wind, pressure, visibility, altitude, precip)
 *   • Data management (clear radar cache, heatmap cache, forecast DB, all)
 */
public class WeatherPreferenceFragment extends PluginPreferenceFragment {

    private static Context staticPluginContext;
    private static final String TAG = "WeatherPreferenceFragment";

    // ── SharedPreferences keys ──────────────────────────────────────────────
    public static final String KEY_UNIT_SYSTEM     = "weather_unit_system";
    public static final String KEY_UNIT_TEMP       = "weather_unit_temp";
    public static final String KEY_UNIT_WIND       = "weather_unit_wind";
    public static final String KEY_UNIT_PRESSURE   = "weather_unit_pressure";
    public static final String KEY_UNIT_VISIBILITY = "weather_unit_visibility";
    public static final String KEY_UNIT_ALTITUDE   = "weather_unit_altitude";
    public static final String KEY_UNIT_PRECIP     = "weather_unit_precip";

    // Database file names (must match the values in the cache/recorder classes)
    private static final String DB_RADAR_CACHE    = "radar_tile_cache.db";
    private static final String DB_HEATMAP_CACHE  = "heatmap_cache.db";
    private static final String DB_FORECAST_HIST  = "forecast_history.db";

    public WeatherPreferenceFragment() {
        super(staticPluginContext, R.xml.preferences);
    }

    @SuppressLint("ValidFragment")
    public WeatherPreferenceFragment(final Context pluginContext) {
        super(pluginContext, R.xml.preferences);
        staticPluginContext = pluginContext;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── Unit System preset ──────────────────────────────────────────────
        wireUnitSystemPreset();

        // ── Individual unit preferences ─────────────────────────────────────
        wireIndividualUnit(KEY_UNIT_TEMP, "setTempUnit");
        wireIndividualUnit(KEY_UNIT_WIND, "setWindUnit");
        wireIndividualUnit(KEY_UNIT_PRESSURE, "setPressureUnit");
        wireIndividualUnit(KEY_UNIT_VISIBILITY, "setVisUnit");
        wireIndividualUnit(KEY_UNIT_ALTITUDE, "setAltUnit");
        wireIndividualUnit(KEY_UNIT_PRECIP, "setPrecipUnit");

        // ── Cache management buttons ────────────────────────────────────────
        wireCacheButton("clear_radar_cache", DB_RADAR_CACHE, "Radar tile cache cleared");
        wireCacheButton("clear_heatmap_cache", DB_HEATMAP_CACHE, "Heatmap cache cleared");
        wireCacheButton("clear_forecast_db", DB_FORECAST_HIST, "Forecast history cleared");
        wireClearAllButton();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Unit System Preset — cascades to all individual prefs
    // ═══════════════════════════════════════════════════════════════════════════

    private void wireUnitSystemPreset() {
        PanListPreference unitPref = (PanListPreference) findPreference(KEY_UNIT_SYSTEM);
        if (unitPref == null) return;

        updateListSummary(unitPref, unitPref.getValue());

        unitPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                String val = (String) newValue;
                Log.d(TAG, "Unit system changed to: " + val);

                try {
                    UnitSystem system = UnitSystem.valueOf(val);
                    WeatherUnitConverter.setUnitSystem(system);

                    // Cascade: update individual prefs to match the preset
                    cascadePresetToIndividual(system);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Unknown unit system: " + val);
                }

                updateListSummary((PanListPreference) preference, val);
                return true;
            }
        });
    }

    /**
     * When a preset is selected, update the 6 individual PanListPreference values
     * so the UI stays in sync with the converter state.
     */
    private void cascadePresetToIndividual(UnitSystem system) {
        // Read back what the converter now has after setUnitSystem()
        setIndividualPrefValue(KEY_UNIT_TEMP, WeatherUnitConverter.getTempUnit());
        setIndividualPrefValue(KEY_UNIT_WIND, WeatherUnitConverter.getWindUnit());
        setIndividualPrefValue(KEY_UNIT_PRESSURE, WeatherUnitConverter.getPressureUnit());
        setIndividualPrefValue(KEY_UNIT_VISIBILITY, WeatherUnitConverter.getVisUnit());
        setIndividualPrefValue(KEY_UNIT_ALTITUDE, WeatherUnitConverter.getAltUnit());
        setIndividualPrefValue(KEY_UNIT_PRECIP, WeatherUnitConverter.getPrecipUnit());
    }

    private void setIndividualPrefValue(String key, String value) {
        PanListPreference pref = (PanListPreference) findPreference(key);
        if (pref != null) {
            pref.setValue(value);
            updateListSummary(pref, value);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Individual Unit Preferences
    // ═══════════════════════════════════════════════════════════════════════════

    private void wireIndividualUnit(final String key, final String setterName) {
        PanListPreference pref = (PanListPreference) findPreference(key);
        if (pref == null) return;

        updateListSummary(pref, pref.getValue());

        pref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                String val = (String) newValue;
                Log.d(TAG, key + " changed to: " + val);

                // Apply to converter
                applyUnitSetter(setterName, val);

                updateListSummary((PanListPreference) preference, val);
                return true;
            }
        });
    }

    /** Reflectionless dispatch to the appropriate WeatherUnitConverter setter. */
    private static void applyUnitSetter(String setterName, String value) {
        switch (setterName) {
            case "setTempUnit":     WeatherUnitConverter.setTempUnit(value); break;
            case "setWindUnit":     WeatherUnitConverter.setWindUnit(value); break;
            case "setPressureUnit": WeatherUnitConverter.setPressureUnit(value); break;
            case "setVisUnit":      WeatherUnitConverter.setVisUnit(value); break;
            case "setAltUnit":      WeatherUnitConverter.setAltUnit(value); break;
            case "setPrecipUnit":   WeatherUnitConverter.setPrecipUnit(value); break;
            default: Log.w(TAG, "Unknown setter: " + setterName);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Cache Management
    // ═══════════════════════════════════════════════════════════════════════════

    private void wireCacheButton(String prefKey, final String dbName, final String toastMsg) {
        Preference pref = findPreference(prefKey);
        if (pref == null) return;

        pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                confirmAndDelete(dbName, toastMsg);
                return true;
            }
        });
    }

    private void wireClearAllButton() {
        Preference pref = findPreference("clear_all_caches");
        if (pref == null) return;

        pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                new AlertDialog.Builder(getActivity())
                        .setTitle("Clear All Data")
                        .setMessage("Delete all cached radar tiles, heatmap grids, and forecast history?\n\nThis cannot be undone.")
                        .setPositiveButton("Clear All", (dialog, which) -> {
                            Context ctx = getActivity();
                            if (ctx == null) return;
                            int count = 0;
                            if (ctx.deleteDatabase(DB_RADAR_CACHE))   count++;
                            if (ctx.deleteDatabase(DB_HEATMAP_CACHE)) count++;
                            if (ctx.deleteDatabase(DB_FORECAST_HIST)) count++;
                            Toast.makeText(ctx,
                                    count + " database(s) cleared", Toast.LENGTH_SHORT).show();
                            Log.d(TAG, "Cleared all caches: " + count + " databases");
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                return true;
            }
        });
    }

    private void confirmAndDelete(final String dbName, final String toastMsg) {
        new AlertDialog.Builder(getActivity())
                .setTitle("Clear Cache")
                .setMessage("Delete " + dbName + "?\nThis cannot be undone.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    Context ctx = getActivity();
                    if (ctx == null) return;
                    boolean deleted = ctx.deleteDatabase(dbName);
                    String msg = deleted ? toastMsg : "Nothing to clear";
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
                    Log.d(TAG, dbName + " delete result: " + deleted);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    /** Show the human-readable label as summary text for a PanListPreference. */
    private void updateListSummary(PanListPreference pref, String value) {
        if (pref == null || value == null) return;
        CharSequence[] entries = pref.getEntries();
        CharSequence[] values = pref.getEntryValues();
        if (entries != null && values != null) {
            for (int i = 0; i < values.length; i++) {
                if (values[i].toString().equals(value)) {
                    pref.setSummary(entries[i]);
                    return;
                }
            }
        }
        pref.setSummary(value);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Static: Load saved prefs at startup
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Load saved unit preferences and apply to WeatherUnitConverter.
     * Call this once during WeatherMapComponent.onCreate().
     *
     * Order: (1) apply preset, then (2) apply individual overrides.
     * This lets the user change one unit without the preset resetting it on restart.
     */
    public static void loadSavedUnitSystem(Context context) {
        try {
            SharedPreferences prefs = android.preference.PreferenceManager
                    .getDefaultSharedPreferences(context);

            // 1. Apply preset (sets all units to preset defaults)
            String preset = prefs.getString(KEY_UNIT_SYSTEM, "METRIC");
            UnitSystem system = UnitSystem.valueOf(preset);
            WeatherUnitConverter.setUnitSystem(system);
            Log.d(TAG, "Loaded unit system preset: " + system);

            // 2. Apply individual overrides (if the user customised after preset)
            String temp = prefs.getString(KEY_UNIT_TEMP, null);
            String wind = prefs.getString(KEY_UNIT_WIND, null);
            String pressure = prefs.getString(KEY_UNIT_PRESSURE, null);
            String vis = prefs.getString(KEY_UNIT_VISIBILITY, null);
            String alt = prefs.getString(KEY_UNIT_ALTITUDE, null);
            String precip = prefs.getString(KEY_UNIT_PRECIP, null);

            if (temp != null)     WeatherUnitConverter.setTempUnit(temp);
            if (wind != null)     WeatherUnitConverter.setWindUnit(wind);
            if (pressure != null) WeatherUnitConverter.setPressureUnit(pressure);
            if (vis != null)      WeatherUnitConverter.setVisUnit(vis);
            if (alt != null)      WeatherUnitConverter.setAltUnit(alt);
            if (precip != null)   WeatherUnitConverter.setPrecipUnit(precip);

            Log.d(TAG, "Loaded individual units — temp:" + WeatherUnitConverter.getTempUnit()
                    + " wind:" + WeatherUnitConverter.getWindUnit()
                    + " pressure:" + WeatherUnitConverter.getPressureUnit()
                    + " vis:" + WeatherUnitConverter.getVisUnit()
                    + " alt:" + WeatherUnitConverter.getAltUnit()
                    + " precip:" + WeatherUnitConverter.getPrecipUnit());

        } catch (Exception e) {
            Log.w(TAG, "Failed to load unit preferences, using METRIC", e);
            WeatherUnitConverter.setUnitSystem(UnitSystem.METRIC);
        }
    }

    @Override
    public String getSubTitle() {
        return getSubTitle("Tool Preferences", "WeatherTool Preferences");
    }
}
