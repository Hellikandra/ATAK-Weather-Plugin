package org.dtakc.weather.atak.map.overlay;

import android.content.Context;

import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.overlay.DefaultMapGroupOverlay;

import org.dtakc.weather.atak.plugin.R;

/** Registers the Wind Barb MapGroup with the ATAK Overlay Manager. */
public final class WindMarkerOverlay extends DefaultMapGroupOverlay {

    public static final String OVERLAY_ID = "weather.wind";
    public static final String GROUP_NAME = "WX Wind";

    public WindMarkerOverlay(MapView mapView, Context pluginContext) {
        super(mapView, OVERLAY_ID, getOrCreateGroup(mapView),
              WeatherMarkerOverlay.buildIconUri(pluginContext), null);
    }

    public MapGroup getWindGroup() { return getRootGroup(); }

    private static MapGroup getOrCreateGroup(MapView mv) {
        MapGroup root  = mv.getRootGroup();
        MapGroup group = root.findMapGroup(GROUP_NAME);
        return group != null ? group : root.addGroup(GROUP_NAME);
    }
}
