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
 * ── Flow ──────────────────────────────────────────────────────────────────────
 *
 *   1. Caller closes the drop-down (collapses the panel so the map is visible).
 *   2. Caller calls MapPointPicker.pick(mapView, callback).
 *   3. ATAK fires MAP_CONFIRMED_CLICK when the user taps the map.
 *      (MAP_CONFIRMED_CLICK, not MAP_CLICK, avoids the first tap of a double-tap.)
 *   4. This class converts the screen PointF → GeoPoint via
 *      mapView.inverseWithElevation() (deprecated but the only plugin-accessible
 *      screen→geo API) and delivers it to the callback.
 *   5. The listener unregisters itself after the first valid tap.
 *
 * ── Cancellation ──────────────────────────────────────────────────────────────
 *
 *   Call cancel() if the user reopens the drop-down without picking a point,
 *   or if the component is destroyed.  Safe to call multiple times.
 */
public class MapPointPicker implements MapEventDispatcher.MapEventDispatchListener {

    private static final String TAG = "MapPointPicker";

    /** Receives the picked geographic point on the main thread. */
    public interface Callback {
        void onPointPicked(GeoPoint point);
    }

    private final MapView mapView;
    private final Callback callback;
    private volatile boolean active = true;

    private MapPointPicker(MapView mapView, Callback callback) {
        this.mapView  = mapView;
        this.callback = callback;
        mapView.getMapEventDispatcher()
                .addMapEventListener(MapEvent.MAP_CONFIRMED_CLICK, this);
        Log.d(TAG, "registered — waiting for tap");
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
        if (!MapEvent.MAP_CONFIRMED_CLICK.equals(event.getType())) return;

        unregister();

        final PointF screen = event.getPointF();
        if (screen == null) {
            Log.w(TAG, "MAP_CONFIRMED_CLICK: null PointF — ignoring");
            return;
        }

        // inverseWithElevation is the only screen→geo API accessible from a plugin.
        // The modern MapRenderer2.inverse path is not reachable without casting
        // to internal types.  @SuppressWarnings silences the IDE deprecation warning.
        @SuppressWarnings("deprecation")
        GeoPointMetaData gpmd = mapView.inverseWithElevation(screen.x, screen.y);
        GeoPoint geo = (gpmd != null) ? gpmd.get() : null;

        if (geo == null || !geo.isValid()) {
            Log.w(TAG, "inverseWithElevation returned invalid point");
            return;
        }

        Log.d(TAG, "point picked: " + geo.getLatitude() + ", " + geo.getLongitude());
        callback.onPointPicked(geo);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void unregister() {
        active = false;
        mapView.getMapEventDispatcher()
                .removeMapEventListener(MapEvent.MAP_CONFIRMED_CLICK, this);
    }
}
