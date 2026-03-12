package com.atakmap.android.weather.util;

import android.graphics.PointF;

import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

/**
 * MapPointPicker — one-shot map-tap listener used by "Drop Weather Marker".
 *
 * ── Why addMapEventListenerToBase, not addMapEventListener ────────────────────
 *
 * MapEventDispatcher has a listener STACK.  When a DropDownReceiver opens it
 * calls pushListeners() and when it closes popListeners() — so any listener
 * registered with addMapEventListener() is on the CURRENT (top) set and gets
 * discarded by the pop.
 *
 * addMapEventListenerToBase() registers on the BASE set that persists across
 * push/pop operations.  This is required for map-tap-after-dropdown-close flows.
 *
 * ── Why MAP_CLICK, not MAP_CONFIRMED_CLICK ────────────────────────────────────
 *
 * MAP_CONFIRMED_CLICK is designed to distinguish single from double-tap. In
 * practice the 300 ms wait before it fires can cause the PointF screen coordinate
 * to become stale if the map redraws (e.g. after the dropdown collapses and the
 * MapView relays out to full-screen).  MAP_CLICK fires immediately with the
 * raw touch position, giving a correct screen coord to feed to
 * inverseWithElevation().
 *
 * ── Debounce ──────────────────────────────────────────────────────────────────
 *
 * closeDropDown() sends a touch-up event that fires MAP_CLICK ~0–100 ms after
 * pick() is called.  We suppress any MAP_CLICK that arrives within DEBOUNCE_MS
 * of picker creation so only the user's INTENTIONAL tap registers.
 *
 * ── Thread safety ─────────────────────────────────────────────────────────────
 *
 * onMapEvent() fires on the main thread (ATAK's MapEventDispatcher dispatches
 * on the UI thread).  The callback is delivered on the same thread.
 */
public class MapPointPicker implements MapEventDispatcher.MapEventDispatchListener {

    private static final String TAG         = "MapPointPicker";
    /** Ignore MAP_CLICK events that arrive within this many ms of registration. */
    private static final long   DEBOUNCE_MS = 350L;

    /** Receives the picked geographic point on the main thread. */
    public interface Callback {
        void onPointPicked(GeoPoint point);
    }

    private final MapView  mapView;
    private final Callback callback;
    private final long     registeredAt = System.currentTimeMillis();
    private volatile boolean active = true;

    private MapPointPicker(MapView mapView, Callback callback) {
        this.mapView  = mapView;
        this.callback = callback;
        // Use the BASE listener set so it persists after the dropdown's popListeners()
        mapView.getMapEventDispatcher()
                .addMapEventListenerToBase(MapEvent.MAP_CLICK, this);
        Log.d(TAG, "registered (base) — waiting for tap");
    }

    /**
     * Start a one-shot point-pick session.
     *
     * @param mapView  ATAK MapView
     * @param callback receives the GeoPoint when the user taps the map
     * @return the picker instance — call cancel() to abort
     */
    public static MapPointPicker pick(MapView mapView, Callback callback) {
        return new MapPointPicker(mapView, callback);
    }

    /** Abort without invoking the callback. */
    public void cancel() {
        unregister();
        Log.d(TAG, "cancelled");
    }

    // ── MapEventDispatchListener ──────────────────────────────────────────────

    @Override
    public void onMapEvent(MapEvent event) {
        if (!active) return;
        if (!MapEvent.MAP_CLICK.equals(event.getType())) return;

        // Debounce: ignore events that fire within DEBOUNCE_MS of registration.
        // These are typically the touch-up event from the tap that caused
        // closeDropDown() to be called, not the user's intended placement tap.
        long age = System.currentTimeMillis() - registeredAt;
        if (age < DEBOUNCE_MS) {
            Log.d(TAG, "MAP_CLICK suppressed (debounce: " + age + "ms < " + DEBOUNCE_MS + "ms)");
            return;
        }

        unregister();

        final PointF screen = event.getPointF();
        if (screen == null) {
            Log.w(TAG, "MAP_CLICK: null PointF — ignoring");
            return;
        }

        // inverseWithElevation() is the only screen→geo API accessible from a
        // plugin without casting to internal ATAK types.
        // Deprecated since 4.1 but functional; the replacement (MapRenderer2.inverse)
        // is not reachable from plugin code in this SDK.
        @SuppressWarnings("deprecation")
        GeoPointMetaData gpmd = mapView.inverseWithElevation(screen.x, screen.y);
        GeoPoint geo = (gpmd != null) ? gpmd.get() : null;

        if (geo == null || !geo.isValid()) {
            Log.w(TAG, "inverseWithElevation returned invalid point for screen "
                    + screen.x + "," + screen.y);
            return;
        }

        Log.d(TAG, "point picked: " + geo.getLatitude() + ", " + geo.getLongitude());
        callback.onPointPicked(geo);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void unregister() {
        active = false;
        mapView.getMapEventDispatcher()
                .removeMapEventListenerFromBase(MapEvent.MAP_CLICK, this);
    }
}
