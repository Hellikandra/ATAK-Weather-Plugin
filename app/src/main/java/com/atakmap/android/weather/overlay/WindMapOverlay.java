package com.atakmap.android.weather.overlay;

import android.content.Context;

import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.overlay.DefaultMapGroupOverlay;

/**
 * WindMapOverlay — registers the Wind Markers MapGroup with the ATAK Overlay Manager.
 *
 * Sits alongside WeatherMapOverlay under the shared "weather.overlay" parent:
 *
 *   weather.overlay  (MapOverlayParent — "Weather")
 *   ├── weather.marker  (WeatherMapOverlay — "WX Markers")
 *   └── weather.wind    (WindMapOverlay    — "Wind Markers")
 *
 * The user can independently toggle weather markers and wind barb markers
 * from the Overlay Manager without affecting each other.
 *
 * Registration (done in WeatherMapComponent):
 *   parent = MapOverlayParent.getOrAddParent(view, PARENT_ID, ...)
 *   view.getMapOverlayManager().addOverlay(parent, new WindMapOverlay(view, ctx))
 */
public class WindMapOverlay extends DefaultMapGroupOverlay {

    public static final String OVERLAY_ID = "weather.wind";
    public static final String GROUP_NAME = "Wind Markers";

    public WindMapOverlay(final MapView mapView, final Context pluginContext) {
        super(mapView,
                OVERLAY_ID,
                getOrCreateGroup(mapView),
                WeatherMapOverlay.buildIconUri(pluginContext),
                null);
    }

    public MapGroup getWindGroup() {
        return getRootGroup();
    }

    private static MapGroup getOrCreateGroup(final MapView mapView) {
        final MapGroup root = mapView.getRootGroup();
        MapGroup group = root.findMapGroup(GROUP_NAME);
        if (group == null) {
            group = root.addGroup(GROUP_NAME);
        }
        return group;
    }
}
