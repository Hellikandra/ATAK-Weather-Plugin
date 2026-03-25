package com.atakmap.android.weather.util;

import android.content.Context;
import android.os.Bundle;

import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.toolbar.Tool;
import com.atakmap.android.toolbar.ToolManagerBroadcastReceiver;
import com.atakmap.android.toolbar.widgets.TextContainer;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

/**
 * WeatherPlaceTool — ATAK Tool for tapping a map point to place a weather or
 * wind marker.
 *
 * ── Why Tool instead of MapPointPicker ────────────────────────────────────────
 *
 * MapPointPicker used addMapEventListenerToBase() + closeDropDown(), which
 * caused intermittent failures:
 *
 *   1. The touch-up event from the tap that closed the dropdown sometimes
 *      fired MAP_CLICK before DEBOUNCE_MS elapsed, landing on the wrong
 *      point (partially mitigated by the 350 ms debounce but not eliminated).
 *
 *   2. closeDropDown() collapses the panel but does NOT stop ATAK's normal
 *      item-click handling, so tapping a map item (marker, shape) could trigger
 *      both a panel navigation AND the WeatherPlace callback.
 *
 * The Tool API solves both issues cleanly:
 *
 *   • onToolBegin() calls mapView.getMapEventDispatcher().pushListeners()
 *     followed by clearListeners() for MAP_CLICK, ITEM_CLICK, MAP_LONG_PRESS,
 *     ITEM_LONG_PRESS — this gives the tool EXCLUSIVE control of all taps.
 *
 *   • mapView.getMapTouchController().setToolActive(true) disables the map's
 *     own item-selection pipeline so no competing listeners fire.
 *
 *   • Tool.findPoint(MapEvent) is the canonical screen→geo conversion used by
 *     all ATAK core tools; it avoids the stale-PointF problem by resolving the
 *     point at the moment of the event, not at registration time.
 *
 *   • TextContainer.getInstance().displayPrompt() shows a persistent on-screen
 *     instruction banner (the same one used by Draw, Route, etc.).
 *
 * ── Registration ──────────────────────────────────────────────────────────────
 *
 * Register ONCE during component init:
 *
 *   WeatherPlaceTool.register(pluginContext, mapView);
 *
 * Then trigger via:
 *
 *   WeatherPlaceTool.start(mapView, WeatherPlaceTool.Mode.WEATHER, callback);
 *   WeatherPlaceTool.start(mapView, WeatherPlaceTool.Mode.WIND,    callback);
 *
 * ── Lifecycle ─────────────────────────────────────────────────────────────────
 *
 * The tool ends itself after ONE tap (one-shot). Cancel via:
 *
 *   WeatherPlaceTool.cancel(mapView);
 *
 * ── Thread safety ─────────────────────────────────────────────────────────────
 *
 * All MapEventDispatcher callbacks fire on the main thread. The callback
 * interface is also delivered on the main thread.
 */
public class WeatherPlaceTool extends Tool
        implements MapEventDispatcher.MapEventDispatchListener {

    private static final String TAG = "WeatherPlaceTool";

    /** Tool identifier registered with ToolManagerBroadcastReceiver. */
    public static final String TOOL_NAME = "weather_place_tool";

    /** Bundle key: which mode ("weather" or "wind") this invocation serves. */
    public static final String KEY_MODE = "weather_place_mode";

    // ── Mode ──────────────────────────────────────────────────────────────────

    public enum Mode { WEATHER, WIND, CBRN }

    // ── Callback ──────────────────────────────────────────────────────────────

    /** Receives the picked GeoPoint on the main thread. */
    public interface Callback {
        void onPointPicked(GeoPoint point, Mode mode);
        default void onCancelled(Mode mode) {}
    }

    // ── Singleton instance (re-used per invocation) ────────────────────────

    private static WeatherPlaceTool instance;

    private final Context pluginContext;
    private Callback activeCallback;
    private Mode     activeMode;

    // ── Construction / registration ───────────────────────────────────────────

    private WeatherPlaceTool(Context pluginContext, MapView mapView) {
        super(mapView, TOOL_NAME);
        this.pluginContext = pluginContext;
        ToolManagerBroadcastReceiver.getInstance().registerTool(TOOL_NAME, this);
        Log.d(TAG, "registered");
    }

    /**
     * Create and register the tool with ATAK's ToolManager.
     * Must be called once from WeatherMapComponent.onStart() or equivalent.
     *
     * @param pluginContext plugin's Context (for string resources)
     * @param mapView       ATAK MapView
     */
    public static void register(Context pluginContext, MapView mapView) {
        if (instance == null) {
            instance = new WeatherPlaceTool(pluginContext, mapView);
        }
    }

    /** Unregister and destroy the singleton. Call from WeatherMapComponent.onStop(). */
    public static void unregister() {
        if (instance != null) {
            ToolManagerBroadcastReceiver.getInstance().unregisterTool(TOOL_NAME);
            instance = null;
        }
    }

    // ── Public trigger API ────────────────────────────────────────────────────

    /**
     * Activate the placement tool for one tap.
     *
     * @param mapView  ATAK MapView
     * @param mode     WEATHER or WIND — determines the on-screen prompt
     * @param callback receives the picked point
     */
    public static void start(MapView mapView, Mode mode, Callback callback) {
        if (instance == null) {
            Log.e(TAG, "start() called before register() — ignoring");
            return;
        }
        instance.activeCallback = callback;
        instance.activeMode     = mode;

        Bundle extras = new Bundle();
        extras.putString(KEY_MODE, mode.name());
        ToolManagerBroadcastReceiver.getInstance().startTool(TOOL_NAME, extras);
    }

    /** Cancel a pending placement without invoking the callback. */
    public static void cancel(MapView mapView) {
        if (instance != null) instance.requestEndTool();
    }

    /** Returns true if the tool is currently waiting for a tap. */
    public static boolean isActive() {
        return instance != null && instance.getActive();
    }

    // ── Tool lifecycle ────────────────────────────────────────────────────────

    @Override
    public boolean onToolBegin(Bundle extras) {
        // Push current listener stack — restores on popListeners in onToolEnd
        _mapView.getMapEventDispatcher().pushListeners();

        // Clear competing listeners so we get exclusive tap control
        _mapView.getMapEventDispatcher().clearListeners(MapEvent.MAP_CLICK);
        _mapView.getMapEventDispatcher().clearListeners(MapEvent.MAP_LONG_PRESS);
        _mapView.getMapEventDispatcher().clearListeners(MapEvent.ITEM_CLICK);
        _mapView.getMapEventDispatcher().clearListeners(MapEvent.ITEM_LONG_PRESS);

        // Register OUR listener on the now-empty MAP_CLICK slot
        _mapView.getMapEventDispatcher().addMapEventListener(MapEvent.MAP_CLICK, this);
        _mapView.getMapEventDispatcher().addMapEventListener(MapEvent.ITEM_CLICK, this);

        // Tell ATAK's touch controller that a tool is running — disables
        // the normal item-selection / long-press handling pipeline
        _mapView.getMapTouchController().setToolActive(true);

        // Show persistent banner with mode-specific instruction
        String prompt;
        if (activeMode == Mode.WIND) {
            prompt = pluginContext.getString(com.atakmap.android.weather.plugin.R.string.tool_prompt_wind);
        } else if (activeMode == Mode.CBRN) {
            prompt = "Tap the map to place CBRN release point";
        } else {
            prompt = pluginContext.getString(com.atakmap.android.weather.plugin.R.string.tool_prompt_weather);
        }
        TextContainer.getInstance().displayPrompt(prompt);

        Log.d(TAG, "tool begun — mode=" + activeMode);
        return true;
    }

    @Override
    protected void onToolEnd() {
        _mapView.getMapTouchController().setToolActive(false);
        _mapView.getMapEventDispatcher().clearListeners();
        _mapView.getMapEventDispatcher().popListeners();
        TextContainer.getInstance().closePrompt();
        Log.d(TAG, "tool ended");
    }

    @Override
    public void dispose() {
        // Nothing to release — singleton is long-lived
    }

    // ── MapEventDispatchListener ──────────────────────────────────────────────

    @Override
    public void onMapEvent(MapEvent event) {
        String type = event.getType();
        if (!MapEvent.MAP_CLICK.equals(type) && !MapEvent.ITEM_CLICK.equals(type)) return;

        // findPoint() is the canonical screen→geo method in the Tool base class.
        // It handles both map taps and item taps correctly.
        GeoPointMetaData gpmd = findPoint(event);
        if (gpmd == null) {
            Log.w(TAG, "findPoint() returned null — ignoring event");
            return;
        }

        GeoPoint geo = gpmd.get();
        if (geo == null || !geo.isValid()) {
            Log.w(TAG, "findPoint() returned invalid GeoPoint");
            return;
        }

        Log.d(TAG, "point picked: " + geo.getLatitude() + ", " + geo.getLongitude()
                + "  mode=" + activeMode);

        // Capture and clear callback before ending tool (prevents re-entrancy)
        Callback cb   = activeCallback;
        Mode     mode = activeMode;
        activeCallback = null;

        // End tool first (restores listener stack), THEN fire callback
        requestEndTool();
        if (cb != null) cb.onPointPicked(geo, mode);
    }
}
