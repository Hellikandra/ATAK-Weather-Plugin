package org.dtakc.weather.atak.map.marker;

import android.content.Context;

import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.util.IconUtilities;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import org.dtakc.weather.atak.domain.model.LocationSnapshot;
import org.dtakc.weather.atak.domain.model.LocationSource;
import org.dtakc.weather.atak.domain.model.WeatherModel;
import org.dtakc.weather.atak.map.overlay.WeatherMarkerOverlay;
import org.dtakc.weather.atak.util.WmoCodeMapper;

import java.util.Locale;

/**
 * Places and manages weather observation markers on the ATAK map.
 *
 * ISS-05 fix: UIDs are namespace-qualified — "org.dtakc.weather.obs.*"
 *             instead of the flat "wx_*" prefix, eliminating UID collisions
 *             with other plugins that happen to use the same short prefix.
 *
 * UID scheme:
 *   SELF_MARKER  → "org.dtakc.weather.obs.self"
 *   MAP_CENTRE   → "org.dtakc.weather.obs.ctr.<lat4dp>_<lon4dp>"
 */
public final class WeatherMarkerManager {

    private static final String TAG         = "WeatherMarkerManager";
    public  static final String MARKER_TYPE = "b-m-p-s-p-wx";    // TAK weather obs CoT type
    private static final String UID_NS      = "org.dtakc.weather.obs";
    private static final String UID_SELF    = UID_NS + ".self";

    private final MapView              mapView;
    private final Context              pluginContext;
    private final WeatherMarkerOverlay overlay;

    public WeatherMarkerManager(MapView mapView, Context pluginContext,
                                WeatherMarkerOverlay overlay) {
        this.mapView       = mapView;
        this.pluginContext = pluginContext;
        this.overlay       = overlay;
    }

    /** Drop or update a weather marker at the snapshot's position. Thread-safe. */
    public void placeMarker(LocationSnapshot snapshot, WeatherModel weather) {
        if (snapshot == null || weather == null) return;
        mapView.post(() -> {
            try { doPlace(snapshot, weather); }
            catch (Exception e) { Log.e(TAG, "placeMarker failed", e); }
        });
    }

    /** Remove a single marker by UID. */
    public void removeMarker(String uid) {
        mapView.post(() -> {
            MapGroup g = overlay.getWeatherGroup();
            if (g == null) return;
            MapItem item = g.deepFindUID(uid);
            if (item != null) item.removeFromGroup();
        });
    }

    /** Remove all weather markers. */
    public void removeAllMarkers() {
        mapView.post(() -> {
            MapGroup g = overlay.getWeatherGroup();
            if (g != null) g.clearItems();
        });
    }

    /** Build the namespace-qualified UID for a snapshot (ISS-05). */
    public static String uidFor(LocationSnapshot snapshot) {
        if (snapshot.getSource() == LocationSource.SELF_MARKER) return UID_SELF;
        return String.format(Locale.US, "%s.ctr.%.4f_%.4f",
                UID_NS, snapshot.getLatitude(), snapshot.getLongitude());
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void doPlace(LocationSnapshot snapshot, WeatherModel weather) {
        MapGroup group = overlay.getWeatherGroup();
        if (group == null) { Log.e(TAG, "Weather MapGroup is null"); return; }

        String uid = uidFor(snapshot);
        MapItem existing = group.deepFindUID(uid);
        if (existing != null) existing.removeFromGroup();

        GeoPoint point = new GeoPoint(snapshot.getLatitude(), snapshot.getLongitude());
        Marker marker  = group.createMarker(point, uid);
        if (marker == null) { Log.e(TAG, "createMarker returned null"); return; }

        marker.setType(MARKER_TYPE);
        marker.setMetaString("callsign",   "WX · " + snapshot.getDisplayName());
        marker.setMetaString("how",        "m-g");
        marker.setMetaString("latitude",   String.valueOf(snapshot.getLatitude()));
        marker.setMetaString("longitude",  String.valueOf(snapshot.getLongitude()));
        marker.setMetaString("wx_source",  snapshot.getSource().name());
        marker.setMetaString("wx_temp",    String.format(Locale.US, "%.1f°C", weather.temperatureMax));
        marker.setMetaString("wx_wind",    String.format(Locale.US, "%.1f m/s %d°",
                weather.windSpeed, (int) weather.windDirection));
        marker.setMetaString("remarks",    buildRemarks(weather));

        WmoCodeMapper.WmoInfo wmoInfo = WmoCodeMapper.resolve(weather.weatherCode);
        if (wmoInfo != null && wmoInfo.drawableResId != 0) {
            IconUtilities.setIcon(pluginContext, marker, wmoInfo.drawableResId, false);
        }

        marker.persist(mapView.getMapEventDispatcher(), null, getClass());
        Log.d(TAG, "Placed weather marker: " + uid);
    }

    private static String buildRemarks(WeatherModel w) {
        return String.format(Locale.US,
                "T: %.1f°C  RH: %.0f%%  P: %.0f hPa  Wind: %.1f m/s %d°",
                w.temperatureMax, w.humidity, w.pressure, w.windSpeed, (int) w.windDirection);
    }
}
