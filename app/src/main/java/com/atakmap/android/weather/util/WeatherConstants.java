package com.atakmap.android.weather.util;

/**
 * Single source of truth for all string constants used across the WeatherTool plugin.
 *
 * <h3>Why this class exists (Sprint 2 — S2.1)</h3>
 * Before this extraction, preference keys, intent actions, marker UIDs, CoT types,
 * and meta-string keys were scattered as {@code static final String} fields across
 * {@code WeatherDropDownReceiver}, {@code WeatherMarkerManager},
 * {@code WindMarkerManager}, {@code WindHudWidget}, and {@code WeatherParameterPreferences}.
 * Consolidating them here:
 * <ul>
 *   <li>Eliminates magic-string duplication — every reference is a single constant.</li>
 *   <li>Makes rename/refactor trivial — change in one place.</li>
 *   <li>Enables ArchUnit rules (S2.3) to verify no raw string literals leak back in.</li>
 * </ul>
 *
 * <h3>Naming convention</h3>
 * <pre>
 *   ACTION_*   — Intent / broadcast action strings
 *   EXTRA_*    — Intent extra keys
 *   PREF_*     — SharedPreferences file names
 *   KEY_*      — SharedPreferences keys within a prefs file
 *   UID_*      — Marker UID prefixes
 *   COT_*      — Cursor-on-Target type strings
 *   META_*     — Marker metaString key names
 * </pre>
 */
public final class WeatherConstants {

    private WeatherConstants() { /* non-instantiable */ }

    // ── Intent Actions ─────────────────────────────────────────────────────────

    /** Show the WeatherTool drop-down panel. */
    public static final String ACTION_SHOW_PLUGIN   = "com.atakmap.android.weather.SHOW_PLUGIN";

    /** Share a weather marker via CoT. */
    public static final String ACTION_SHARE_MARKER  = "com.atakmap.android.weather.SHARE_MARKER";

    /** Remove a weather marker by UID. */
    public static final String ACTION_REMOVE_MARKER = "com.atakmap.android.weather.REMOVE_MARKER";

    /** Toggle the wind HUD widget visibility. */
    public static final String ACTION_TOGGLE_HUD    = "com.atakmap.android.weather.TOGGLE_WIND_HUD";

    // ── Intent Extras ──────────────────────────────────────────────────────────

    /** UID of the marker to act on (used with SHARE_MARKER / REMOVE_MARKER). */
    public static final String EXTRA_TARGET_UID    = "targetUID";

    /** Tab index to select when opening the drop-down. */
    public static final String EXTRA_REQUESTED_TAB = "requestedTab";

    // ── SharedPreferences — file names ─────────────────────────────────────────

    /** Preferences file for user-selected Open-Meteo parameters. */
    public static final String PREF_PARAMETERS  = "weather_parameters";

    /** Preferences file for active weather source selection. */
    public static final String PREF_SOURCES     = "weather_sources";

    // ── SharedPreferences — keys ───────────────────────────────────────────────

    /** Key: selected hourly parameters (StringSet). */
    public static final String KEY_HOURLY  = "selected_hourly";

    /** Key: selected daily parameters (StringSet). */
    public static final String KEY_DAILY   = "selected_daily";

    /** Key: selected current parameters (StringSet). */
    public static final String KEY_CURRENT = "selected_current";

    // ── Marker UID prefixes ────────────────────────────────────────────────────

    /** UID for the self-location weather marker. */
    public static final String UID_WX_SELF = "wx_self";

    /** UID prefix for wind markers ({@code "wx_wind_self"}, {@code "wx_wind_lat_lon"}). */
    public static final String UID_WIND_PREFIX = "wx_wind";

    // ── CoT types ──────────────────────────────────────────────────────────────

    /** CoT type for weather observation markers (friendly/environmental/weather). */
    public static final String COT_WEATHER_OBS = "a-n-G-E-V-c";

    /** CoT type for wind/met spot markers. */
    public static final String COT_WIND_SPOT   = "b-m-p-s-m";

    // ── Marker meta-string keys ────────────────────────────────────────────────

    public static final String META_CALLSIGN   = "callsign";
    public static final String META_HOW        = "how";
    public static final String META_DETAIL     = "detail";
    public static final String META_WX_SOURCE  = "wx_source";
    public static final String META_WX_TIMESTAMP  = "wx_timestamp";
    public static final String META_WX_TEMP_MAX   = "wx_temp_max";
    public static final String META_WX_TEMP_MIN   = "wx_temp_min";
    public static final String META_WX_HUMIDITY   = "wx_humidity";
    public static final String META_WX_WIND_SPEED = "wx_wind_speed";
    public static final String META_WX_WIND_DIR   = "wx_wind_dir";
    public static final String META_WX_PRESSURE   = "wx_pressure";
    public static final String META_WX_ICAO_ID    = "wx_icao_id";
    public static final String META_WX_FLT_CAT    = "wx_flt_cat";
    public static final String META_WX_RAW_METAR  = "wx_raw_metar";

    // ── Overlay identifiers ────────────────────────────────────────────────────

    /** Overlay Manager parent folder ID. */
    public static final String OVERLAY_PARENT_ID   = "weather.overlay";

    /** Overlay Manager parent folder display name. */
    public static final String OVERLAY_PARENT_NAME = "Weather";

    /** Radar overlay identifier. */
    public static final String OVERLAY_RADAR_ID    = "weather.radar";

    /** Radar overlay display name. */
    public static final String OVERLAY_RADAR_NAME  = "WX Radar";

    /** Heatmap overlay identifier (Sprint 11). */
    public static final String OVERLAY_HEATMAP_ID   = "weather.heatmap";

    /** Heatmap overlay display name (Sprint 11). */
    public static final String OVERLAY_HEATMAP_NAME = "WX Heatmap";

    // ── Default values ─────────────────────────────────────────────────────────

    /** Default forecast days for Open-Meteo API. */
    public static final int DEFAULT_FORECAST_DAYS  = 7;

    /** Default forecast hours for Open-Meteo API. */
    public static final int DEFAULT_FORECAST_HOURS = 168;

    /** How (generation method) for weather markers — machine-generated. */
    public static final String HOW_MACHINE_GENERATED = "m-g";
}
