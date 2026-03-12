package org.dtakc.weather.atak.map.menu;

import android.content.Context;
import android.content.Intent;

import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.menu.MapMenuFactory;
import com.atakmap.android.menu.MapMenuWidget;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.coremap.log.Log;

import org.dtakc.weather.atak.ui.WeatherDropDownController;

/**
 * Programmatic radial-menu factory for weather and wind markers.
 * Registered via MapMenuReceiver.registerMapMenuFactory() in WeatherPluginComponent.
 *
 * Handles all markers whose UID starts with "org.dtakc.weather".
 *
 * Note: ATAK 5.x MenuLayoutWidget requires (MapView, MapAssets, MenuMapAdapter)
 * and does not support addButton(). Menu actions are delivered via broadcast
 * intents triggered from the standard XML menu or by launching the drop-down
 * directly from onLongPress. This implementation delegates to the plugin's
 * drop-down and returns null to let ATAK fall back to the default menu for
 * share/remove operations (which are already available in the standard CoT menu).
 */
public final class WeatherMenuFactory implements MapMenuFactory {

    private static final String TAG = "WeatherMenuFactory";

    private final Context pluginContext;
    private final MapView mapView;

    public WeatherMenuFactory(Context pluginContext, MapView mapView) {
        this.pluginContext = pluginContext;
        this.mapView       = mapView;
    }

    @Override
    public MapMenuWidget create(MapItem item) {
        if (item == null || item.getUID() == null) return null;
        String uid = item.getUID();
        if (!uid.startsWith("org.dtakc.weather")) return null;

        // Open the weather drop-down for this marker and let ATAK
        // handle share/remove through its standard radial menu.
        try {
            Intent intent = new Intent(WeatherDropDownController.SHOW_PLUGIN);
            intent.putExtra(WeatherDropDownController.EXTRA_TARGET_UID, uid);
            AtakBroadcast.getInstance().sendBroadcast(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to broadcast SHOW_PLUGIN for uid=" + uid, e);
        }

        // Return null → ATAK will show its built-in radial menu
        // (which includes Delete, Share CoT, etc.)
        return null;
    }
}
