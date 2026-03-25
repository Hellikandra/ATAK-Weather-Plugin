package com.atakmap.android.weather.overlay.wind;

import android.content.Context;
import android.graphics.Color;

import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.weather.domain.model.LocationSnapshot;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.overlay.WindMapOverlay;
import com.atakmap.android.util.IconUtilities;
import com.atakmap.android.weather.plugin.R;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.Locale;

/**
 * WindMarkerManager — places and removes wind observation markers in the Wind MapGroup.
 *
 * <h3>Changes vs original</h3>
 * <ul>
 *   <li>{@link #UID_PREFIX} promoted from {@code private} to {@code public} so
 *       {@link com.atakmap.android.weather.WeatherDropDownReceiver} and
 *       {@link com.atakmap.android.weather.overlay.wind.WindHudWidget} can
 *       reference it without a magic literal string.</li>
 * </ul>
 *
 * Marker UID scheme:
 * <pre>
 *   "wx_wind_{lat4}_{lon4}"   — wind profile marker at a picked lat/lon
 *   "wx_wind_self"            — wind profile marker at self-marker location
 * </pre>
 *
 * All markers are placed in the "Wind Markers" MapGroup owned by WindMapOverlay,
 * which sits under the "Weather" parent in the Overlay Manager.
 *
 * A marker is NOT a MapItem drawn by a GL renderer — it uses MapGroup.createMarker()
 * which is the correct ATAK plugin API. Markers appear in the default TAK renderer.
 *
 * It is placed in the same "Weather" MapGroup (shared overlay) and uses the
 * ATAK Marker type so the built-in radial context menu appears on long press.
 */
public class WindMarkerManager {

    private static final String TAG = "WindMarkerManager";

    /**
     * UID prefix shared across WindMarkerManager, WindHudWidget, and
     * WeatherDropDownReceiver. Delegates to {@link com.atakmap.android.weather.util.WeatherConstants#UID_WIND_PREFIX}.
     */
    public static final String UID_PREFIX = com.atakmap.android.weather.util.WeatherConstants.UID_WIND_PREFIX;

    private final MapView        mapView;
    private final Context        context;
    private final WindMapOverlay overlay;

    public WindMarkerManager(MapView mapView,
                             Context context,
                             WindMapOverlay overlay) {
        this.mapView = mapView;
        this.context = context;
        this.overlay = overlay;
    }

    public WindMapOverlay getOverlay() { return overlay; }

    /**
     * Place (or replace) a wind marker at the given location.
     * If a marker with the same UID already exists it is removed first.
     */
    public void placeMarker(LocationSnapshot location, WeatherModel weather) {
        if (location == null || weather == null) return;
        MapGroup group = overlay.getWindGroup();
        if (group == null) return;

        final String uid      = buildUid(location);
        final String callsign = buildCallsign(location);

        // Remove existing marker with same UID (idempotent placement)
        removeMarker(uid);

        GeoPoint point = new GeoPoint(location.getLatitude(), location.getLongitude());
        Marker marker = group.createMarker(point, uid);
        if (marker == null) {
            Log.w(TAG, "createMarker returned null — group may be detached");
            return;
        }

        marker.setType("b-m-p-s-m");   // spot/met marker type
        marker.setMetaString("callsign",  callsign);
        marker.setMetaString("wx_source", location.getSource().name());
        marker.setMetaDouble("latitude",  location.getLatitude());
        marker.setMetaDouble("longitude", location.getLongitude());
        marker.setMetaString("wind_speed",     String.format(Locale.US, "%.1f kt", weather.getWindSpeed()));
        marker.setMetaString("wind_direction", String.format(Locale.US, "%.0f°",   weather.getWindDirection()));
        marker.setMetaString("detail",    buildDetail(location, weather));
        marker.setTitle(callsign);
        marker.setMovable(false);

        // Set wind marker icon
        try {
            IconUtilities.setIcon(context, marker, R.drawable.ic_wind_marker, false);
            marker.setMetaInteger("iconWidth", 28);
            marker.setMetaInteger("iconHeight", 28);
        } catch (Exception e) {
            Log.w(TAG, "setIcon failed: " + e.getMessage());
        }

        Log.d(TAG, "Wind marker placed: uid=" + uid + " at " + point);
    }

    public void removeMarker(String uid) {
        if (uid == null) return;
        MapGroup group = overlay.getWindGroup();
        if (group == null) return;
        Marker existing = (Marker) group.deepFindUID(uid);
        if (existing != null) {
            group.removeItem(existing);
            Log.d(TAG, "Wind marker removed: uid=" + uid);
        }
    }

    public void removeAllMarkers() {
        MapGroup group = overlay.getWindGroup();
        if (group != null) group.clearItems();
    }

    /**
     * Return the number of wind markers currently in the overlay group.
     */
    public int getMarkerCount() {
        final MapGroup group = overlay.getWindGroup();
        return group != null ? group.getItemCount() : 0;
    }

    /** Expose the underlying MapGroup for marker list iteration. */
    public MapGroup getMapGroup() {
        return overlay.getWindGroup();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildUid(LocationSnapshot location) {
        switch (location.getSource()) {
            case SELF_MARKER:
                return UID_PREFIX + "_self";
            default:
                return String.format(Locale.US,
                        UID_PREFIX + "_%.4f_%.4f",
                        location.getLatitude(), location.getLongitude());
        }
    }

    private String buildCallsign(LocationSnapshot location) {
        String name = location.getDisplayName();
        if (name != null && !name.isEmpty()) return "Wind: " + name;
        return String.format(Locale.US, "Wind %.3fN %.3fE",
                location.getLatitude(), location.getLongitude());
    }

    private String buildDetail(LocationSnapshot location, WeatherModel weather) {
        return String.format(Locale.US,
                "Wind %.1f kt @ %.0f deg | %s",
                weather.getWindSpeed(),
                weather.getWindDirection(),
                location.getCoordsLabel());
    }
}
