package org.dtakc.weather.atak.map.overlay;

import android.content.Context;

import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.overlay.DefaultMapGroupOverlay;

import org.dtakc.weather.atak.plugin.R;

/**
 * Registers the WX Markers MapGroup with the ATAK Overlay Manager.
 * Renamed from WeatherMapOverlay to match the overlay hierarchy:
 *   weather.overlay (parent) → weather.marker (this overlay)
 */
public final class WeatherMarkerOverlay extends DefaultMapGroupOverlay {

    public static final String PARENT_ID  = "weather.overlay";
    public static final String OVERLAY_ID = "weather.marker";
    public static final String GROUP_NAME = "WX Markers";

    public WeatherMarkerOverlay(MapView mapView, Context pluginContext) {
        super(mapView, OVERLAY_ID, getOrCreateGroup(mapView),
              buildIconUri(pluginContext), null);
    }

    public MapGroup getWeatherGroup() { return getRootGroup(); }

    private static MapGroup getOrCreateGroup(MapView mv) {
        MapGroup root  = mv.getRootGroup();
        MapGroup group = root.findMapGroup(GROUP_NAME);
        return group != null ? group : root.addGroup(GROUP_NAME);
    }

    public static String buildIconUri(Context ctx) {
        return "android.resource://" + ctx.getPackageName() + "/" + R.drawable.ic_launcher;
    }
}
