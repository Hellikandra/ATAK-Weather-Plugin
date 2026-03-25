package com.atakmap.android.weather.overlay.marker;

import android.content.Context;

import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.util.IconUtilities;
import com.atakmap.android.weather.overlay.WeatherMenuFactory;
import com.atakmap.android.weather.domain.model.LocationSnapshot;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.overlay.WeatherMapOverlay;
import com.atakmap.android.weather.util.WeatherUnitConverter;
import com.atakmap.android.weather.util.WmoCodeMapper;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.Locale;

/**
 * WeatherMarkerManager — places and manages weather markers on the ATAK map.
 *
 * ── Why the previous version was invisible ────────────────────────────────────
 *
 * Two separate problems caused the invisible marker:
 *
 * Problem 1 — Unregistered group (fixed by WeatherMapOverlay):
 *   The old code created a MapGroup via getRootGroup().addGroup() and called
 *   group.addItem(new Marker(...)). A group not wrapped by a registered
 *   MapOverlay is not in the ATAK render tree and therefore never drawn.
 *   WeatherMapOverlay registers our "Weather" group with addMarkersOverlay()
 *   which connects it to the render pipeline.
 *
 * Problem 2 — Wrong marker creation API (fixed here):
 *   The old code called `new Marker(point, uid)` directly. Constructing a
 *   Marker this way bypasses ATAK's CoT and persistence lifecycle, meaning
 *   the marker exists as a Java object but is unknown to the renderer.
 *
 * ── Correct approach ──────────────────────────────────────────────────────────
 *
 * MapGroup.createMarker(GeoPoint, uid) is the proper API:
 *   - Creates a Marker AND atomically adds it to the group in one call
 *   - The group is already registered with the renderer via WeatherMapOverlay
 *   - The marker is immediately visible on the map
 *
 * After createMarker() we call marker.persist() to fire ATAK's persistence
 * event so the marker survives app restart (stored in the CoT database).
 * IconUtilities.setIcon() then sets the WMO icon with adapt=false so ATAK
 * never overwrites our custom icon when the marker type is evaluated.
 *
 * ── Marker design ─────────────────────────────────────────────────────────────
 *
 * Type:      "a-n-G-E-V-c"  (friendly environmental/weather observation point)
 * Callsign:  "WX · <locationName>"
 * Icon:      WMO weather icon from the plugin's drawable resources
 * Detail:    "Details" in the radial menu fires SHOW_PLUGIN to reopen the plugin
 *
 * ── UID scheme ────────────────────────────────────────────────────────────────
 *
 * SELF_MARKER  → "wx_self"
 * MAP_CENTRE   → "wx_centre_<lat4dp>_<lon4dp>"
 *
 * Dropping a new marker for the same UID removes the old one first.
 */
public class WeatherMarkerManager {

    private static final String TAG      = "WeatherMarkerManager";
    private static final String UID_SELF = com.atakmap.android.weather.util.WeatherConstants.UID_WX_SELF;

    /**
     * CoT type for weather observation markers.
     * Declared public so WeatherMapComponent can register the radial menu for this type.
     * Delegates to {@link com.atakmap.android.weather.util.WeatherConstants#COT_WEATHER_OBS}.
     */
    public static final String MARKER_TYPE = com.atakmap.android.weather.util.WeatherConstants.COT_WEATHER_OBS;

    /**
     * Radial menu is provided by WeatherMenuFactory (registered via
     * MapMenuReceiver.registerMapMenuFactory). No static XML needed here.
     * @see WeatherMenuFactory
     */

    private final MapView           mapView;
    private final Context           pluginContext;
    private final WeatherMapOverlay overlay;

    public WeatherMarkerManager(final MapView mapView,
                                final Context pluginContext,
                                final WeatherMapOverlay overlay) {
        this.mapView       = mapView;
        this.pluginContext = pluginContext;
        this.overlay       = overlay;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Drop or update a weather marker at the snapshot's position.
     * Thread-safe — posts to the main thread internally.
     */
    public void placeMarker(final LocationSnapshot snapshot,
                            final WeatherModel weather) {
        if (snapshot == null || weather == null) return;
        mapView.post(() -> {
            try {
                doPlaceMarker(snapshot, weather);
            } catch (Exception e) {
                Log.e(TAG, "placeMarker failed", e);
            }
        });
    }

    /** Remove a single weather marker by UID. */
    public void removeMarker(final String uid) {
        mapView.post(() -> {
            final MapGroup group = overlay.getWeatherGroup();
            if (group == null) return;
            final MapItem existing = group.deepFindUID(uid);
            if (existing != null) existing.removeFromGroup();
        });
    }

    /** Remove all weather markers from the overlay group. */
    public void removeAllMarkers() {
        mapView.post(() -> {
            final MapGroup group = overlay.getWeatherGroup();
            if (group != null) group.clearItems();
        });
    }

    /**
     * Return the number of weather markers currently in the overlay group.
     * Must be called on the main thread (or accept a slightly stale count
     * if called from a background thread — MapGroup.getItemCount() is
     * synchronized internally).
     */
    public int getMarkerCount() {
        final MapGroup group = overlay.getWeatherGroup();
        return group != null ? group.getItemCount() : 0;
    }

    /** Expose the underlying MapGroup for marker list iteration. */
    public MapGroup getMapGroup() {
        return overlay.getWeatherGroup();
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void doPlaceMarker(final LocationSnapshot snapshot,
                               final WeatherModel weather) {
        final MapGroup group = overlay.getWeatherGroup();
        if (group == null) {
            Log.e(TAG, "Weather group is null — overlay not registered yet");
            return;
        }

        final String uid = buildUid(snapshot);

        // Remove existing marker with same UID if present
        final MapItem existing = group.deepFindUID(uid);
        if (existing != null) {
            existing.removeFromGroup();
            Log.d(TAG, "Removed existing marker uid=" + uid);
        }

        final GeoPoint point = new GeoPoint(
                snapshot.getLatitude(), snapshot.getLongitude());

        // MapGroup.createMarker() is the correct API:
        // it creates the Marker AND adds it to the group atomically.
        // Because the group is registered with MapOverlayManager via
        // WeatherMapOverlay, the marker appears on the map immediately.
        final Marker marker = group.createMarker(point, uid);
        if (marker == null) {
            Log.e(TAG, "createMarker() returned null for uid=" + uid);
            return;
        }

        // ── Configure the marker ──────────────────────────────────────────────
        // Use WeatherConstants for all meta-string keys (S2.1)
        marker.setType(MARKER_TYPE);
        marker.setTitle(buildCallsign(snapshot, weather));
        marker.setMetaString(com.atakmap.android.weather.util.WeatherConstants.META_CALLSIGN,      buildCallsign(snapshot, weather));
        marker.setMetaString(com.atakmap.android.weather.util.WeatherConstants.META_HOW,            com.atakmap.android.weather.util.WeatherConstants.HOW_MACHINE_GENERATED);
        marker.setMetaString(com.atakmap.android.weather.util.WeatherConstants.META_DETAIL,         buildDetail(snapshot, weather));
        marker.setMetaString(com.atakmap.android.weather.util.WeatherConstants.META_WX_SOURCE,      snapshot.getSource().name());
        marker.setMetaString(com.atakmap.android.weather.util.WeatherConstants.META_WX_TIMESTAMP,   weather.getRequestTimestamp());
        marker.setMetaString(com.atakmap.android.weather.util.WeatherConstants.META_WX_TEMP_MAX,    String.valueOf(weather.getTemperatureMax()));
        marker.setMetaString(com.atakmap.android.weather.util.WeatherConstants.META_WX_TEMP_MIN,    String.valueOf(weather.getTemperatureMin()));
        marker.setMetaString(com.atakmap.android.weather.util.WeatherConstants.META_WX_HUMIDITY,    String.valueOf(weather.getHumidity()));
        marker.setMetaString(com.atakmap.android.weather.util.WeatherConstants.META_WX_WIND_SPEED,  String.valueOf(weather.getWindSpeed()));
        marker.setMetaString(com.atakmap.android.weather.util.WeatherConstants.META_WX_WIND_DIR,    String.valueOf(weather.getWindDirection()));
        marker.setMetaString(com.atakmap.android.weather.util.WeatherConstants.META_WX_PRESSURE,    String.valueOf(weather.getPressure()));
        // AWC METAR-specific (empty strings for Open-Meteo source — harmless)
        marker.setMetaString(com.atakmap.android.weather.util.WeatherConstants.META_WX_ICAO_ID,     weather.getIcaoId());
        marker.setMetaString(com.atakmap.android.weather.util.WeatherConstants.META_WX_FLT_CAT,     weather.getFlightCategory());
        marker.setMetaString(com.atakmap.android.weather.util.WeatherConstants.META_WX_RAW_METAR,   weather.getRawMetar());

        // Radial menu is provided by WeatherMenuFactory registered in
        // WeatherMapComponent.onCreate(). No per-marker setup needed.

        marker.setClickable(true);
        marker.setVisible(true);

        // ── Set the WMO weather icon (day/night aware) ────────────────────────
        // adapt=false: ATAK must never replace this icon automatically when
        // the marker type changes. We manage the icon lifecycle ourselves.
        final WmoCodeMapper.WmoInfo wmoInfo = WmoCodeMapper.resolve(
                weather.getWeatherCode(),
                snapshot.getLatitude(), snapshot.getLongitude());
        try {
            IconUtilities.setIcon(pluginContext, marker, wmoInfo.drawableResId, false);
            // Reduce marker icon size (32x32dp instead of default 48)
            marker.setMetaInteger("iconWidth", 32);
            marker.setMetaInteger("iconHeight", 32);
        } catch (Exception e) {
            Log.w(TAG, "setIcon failed, marker uses type-default icon: " + e.getMessage());
        }

        // ── Persist to CoT database ───────────────────────────────────────────
        // Dispatches a persist event so the marker survives ATAK restart.
        marker.persist(mapView.getMapEventDispatcher(), null, getClass());

        Log.d(TAG, "Weather marker placed: uid=" + uid
                + "  pos=" + snapshot.getLatitude()
                + "," + snapshot.getLongitude());
    }

    // ── Builders ──────────────────────────────────────────────────────────────

    private String buildUid(final LocationSnapshot snapshot) {
        switch (snapshot.getSource()) {
            case SELF_MARKER:
                return UID_SELF;
            default:
                return String.format(Locale.US, "wx_centre_%.4f_%.4f",
                        snapshot.getLatitude(), snapshot.getLongitude());
        }
    }

    private String buildCallsign(final LocationSnapshot snapshot,
                                 final WeatherModel weather) {
        // For AWC METAR: "EBLG · IFR · WX"
        // For Open-Meteo: "WX · Liège" or "WX · 50.63N 5.57E"
        if (weather.isMetarSource()) {
            String cat = weather.getFlightCategory();
            String id  = weather.getIcaoId();
            return id + " \u00b7 " + (cat.isEmpty() ? "WX" : cat + " \u00b7 WX");
        }
        String name = snapshot.getDisplayName();
        if (name == null || name.isEmpty() || "null".equals(name)) {
            // Fallback to coordinates when no display name available
            name = String.format(Locale.US, "%.2f\u00b0N %.2f\u00b0E",
                    snapshot.getLatitude(), snapshot.getLongitude());
        }
        return "WX \u00b7 " + name;
    }

    private String buildDetail(final LocationSnapshot snapshot,
                               final WeatherModel weather) {
        // Compact one-liner shown in the map callout bubble on single tap
        // Uses unit-aware formatters so it respects Tool Preferences
        String cardinal = WeatherUnitConverter.degreesToCardinal(weather.getWindDirection());
        return WeatherUnitConverter.fmtTempRange(
                        weather.getTemperatureMin(), weather.getTemperatureMax())
                + "  \ud83d\udca7" + WeatherUnitConverter.fmtHumidity(weather.getHumidity())
                + "  \ud83d\udca8" + WeatherUnitConverter.fmtWind(weather.getWindSpeed())
                + " " + cardinal
                + "  " + snapshot.getCoordsLabel();
    }
}
