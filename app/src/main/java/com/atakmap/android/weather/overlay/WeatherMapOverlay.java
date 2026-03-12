package com.atakmap.android.weather.overlay;

import android.content.Context;

import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.overlay.DefaultMapGroupOverlay;
import com.atakmap.android.weather.plugin.R;

/**
 * WeatherMapOverlay — registers the Weather MapGroup with the ATAK Overlay Manager.
 *
 * ── Overlay hierarchy ─────────────────────────────────────────────────────────
 *
 *   weather.overlay  (MapOverlayParent — "Weather")
 *   └── weather.marker  (WeatherMapOverlay / DefaultMapGroupOverlay — "WX Markers")
 *       └── [weather Marker items — wx_self, wx_centre_*]
 *
 *   The wind overlay is managed separately by WindMarkerManager and registered
 *   under the same parent:
 *   └── weather.wind    (WindMapOverlay / DefaultMapGroupOverlay — "Wind Markers")
 *       └── [wind Marker items — wx_wind_*]
 *
 * WHY A PARENT:
 *   Using MapOverlayParent.getOrAddParent() creates a "Weather" folder in the
 *   ATAK Overlay Manager. Child overlays (weather.marker, weather.wind) are
 *   individually toggleable sub-items beneath it. This avoids polluting the
 *   top-level Markers or Other sections.
 *
 * ── Registration (done in WeatherMapComponent) ────────────────────────────────
 *
 *   parent  = MapOverlayParent.getOrAddParent(view, PARENT_ID, "Weather", iconUri, 0, false)
 *   overlay = new WeatherMapOverlay(view, context)
 *   view.getMapOverlayManager().addOverlay(parent, overlay)
 *
 * ── Unregistration ────────────────────────────────────────────────────────────
 *
 *   view.getMapOverlayManager().removeOverlay(parent, overlay)
 */
public class WeatherMapOverlay extends DefaultMapGroupOverlay {

    /** Stable reverse-DNS ID for the parent "Weather" folder in Overlay Manager. */
    public static final String PARENT_ID   = "weather.overlay";

    /** Stable ID for this child overlay (weather markers). */
    public static final String OVERLAY_ID  = "weather.marker";

    /** MapGroup friendly name — shown in the Overlay Manager item label. */
    public static final String GROUP_NAME  = "WX Markers";

    public WeatherMapOverlay(final MapView mapView, final Context pluginContext) {
        super(mapView,
                OVERLAY_ID,
                getOrCreateGroup(mapView),
                buildIconUri(pluginContext),
                null);   // null filter = all items visible in hierarchy
    }

    /**
     * Returns the MapGroup that WeatherMarkerManager adds markers into.
     */
    public MapGroup getWeatherGroup() {
        return getRootGroup();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static MapGroup getOrCreateGroup(final MapView mapView) {
        final MapGroup root = mapView.getRootGroup();
        MapGroup group = root.findMapGroup(GROUP_NAME);
        if (group == null) {
            group = root.addGroup(GROUP_NAME);
        }
        return group;
    }

    public static String buildIconUri(final Context pluginContext) {
        return "android.resource://"
                + pluginContext.getPackageName()
                + "/" + R.drawable.ic_launcher;
    }
}
