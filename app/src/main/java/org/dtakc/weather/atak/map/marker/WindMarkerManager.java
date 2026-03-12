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
import org.dtakc.weather.atak.domain.model.WeatherModel;
import org.dtakc.weather.atak.map.overlay.WindMarkerOverlay;
import org.dtakc.weather.atak.plugin.R;

import java.util.Locale;

/**
 * Places wind profile markers on the map.
 * ISS-05 fix: UID prefix "org.dtakc.weather.wind.*"
 */
public final class WindMarkerManager {

    private static final String TAG    = "WindMarkerManager";
    private static final String UID_NS = "org.dtakc.weather.wind";

    private final MapView          mapView;
    private final Context          pluginContext;
    private final WindMarkerOverlay overlay;

    public WindMarkerManager(MapView mapView, Context pluginContext,
                             WindMarkerOverlay overlay) {
        this.mapView       = mapView;
        this.pluginContext = pluginContext;
        this.overlay       = overlay;
    }

    public WindMarkerOverlay getOverlay() { return overlay; }

    /** Build namespace-qualified UID (ISS-05). */
    public static String uidFor(double lat, double lon) {
        return String.format(Locale.US, "%s.%.4f_%.4f", UID_NS, lat, lon);
    }

    public void placeMarker(LocationSnapshot snapshot, WeatherModel weather) {
        if (snapshot == null || weather == null) return;
        mapView.post(() -> {
            try { doPlace(snapshot, weather); }
            catch (Exception e) { Log.e(TAG, "placeMarker failed", e); }
        });
    }

    public void removeMarker(String uid) {
        mapView.post(() -> {
            MapGroup g = overlay.getWindGroup();
            if (g == null) return;
            MapItem item = g.deepFindUID(uid);
            if (item != null) item.removeFromGroup();
        });
    }

    private void doPlace(LocationSnapshot snapshot, WeatherModel weather) {
        MapGroup group = overlay.getWindGroup();
        if (group == null) return;
        String uid = uidFor(snapshot.getLatitude(), snapshot.getLongitude());
        MapItem existing = group.deepFindUID(uid);
        if (existing != null) existing.removeFromGroup();

        GeoPoint point = new GeoPoint(snapshot.getLatitude(), snapshot.getLongitude());
        Marker marker  = group.createMarker(point, uid);
        if (marker == null) return;

        marker.setType("b-m-p-s-p-wx-wind");
        marker.setMetaString("callsign",  "WIND · " + snapshot.getDisplayName());
        marker.setMetaString("how",       "m-g");
        marker.setMetaString("wx_source", snapshot.getSource().name());
        marker.setMetaString("latitude",  String.valueOf(snapshot.getLatitude()));
        marker.setMetaString("longitude", String.valueOf(snapshot.getLongitude()));
        IconUtilities.setIcon(pluginContext, marker, R.drawable.ic_launcher, false);
        marker.persist(mapView.getMapEventDispatcher(), null, getClass());
    }
}
