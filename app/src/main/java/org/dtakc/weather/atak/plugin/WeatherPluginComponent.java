package org.dtakc.weather.atak.plugin;

import android.content.Context;
import android.content.Intent;

import com.atakmap.android.dropdown.DropDownMapComponent;
import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.menu.MapMenuReceiver;
import com.atakmap.android.overlay.MapOverlayParent;
import com.atakmap.app.preferences.ToolsPreferenceFragment;
import com.atakmap.coremap.log.Log;

import org.dtakc.weather.atak.data.local.WeatherDatabase;
import org.dtakc.weather.atak.data.remote.WeatherDataSourceRegistry;
import org.dtakc.weather.atak.map.marker.WeatherMarkerManager;
import org.dtakc.weather.atak.map.marker.WindMarkerManager;
import org.dtakc.weather.atak.map.menu.WeatherMenuFactory;
import org.dtakc.weather.atak.map.overlay.WeatherMarkerOverlay;
import org.dtakc.weather.atak.map.overlay.WindMarkerOverlay;
import org.dtakc.weather.atak.map.tool.WeatherPlaceTool;
import org.dtakc.weather.atak.ui.WeatherDropDownController;
import org.dtakc.weather.atak.ui.preferences.WeatherPreferenceFragment;

/**
 * ATAK MapComponent lifecycle entry point for the DTAKC Weather Plugin.
 *
 * Renamed from WeatherMapComponent → WeatherPluginComponent to align with
 * the org.dtakc package convention and clarify its role.
 *
 * Overlay hierarchy registered here:
 *   weather.overlay  (MapOverlayParent — "Weather" folder)
 *   ├── weather.marker  (WeatherMarkerOverlay — WX obs markers)
 *   └── weather.wind    (WindMarkerOverlay    — wind barb markers)
 *
 * Singleton cleanup:
 *   destroyInstance() calls for WeatherDatabase and WeatherDataSourceRegistry
 *   ensure hot-swap reinstalls get clean singletons (ISS-02).
 */
public final class WeatherPluginComponent extends DropDownMapComponent {

    private static final String TAG = "WeatherPluginComponent";

    private WeatherMarkerOverlay  weatherOverlay;
    private WindMarkerOverlay     windOverlay;
    private WeatherDropDownController ddr;
    private WeatherMenuFactory    menuFactory;

    @Override
    public void onCreate(Context context, Intent intent, MapView view) {
        context.setTheme(R.style.ATAKPluginTheme);
        super.onCreate(context, intent, view);

        // ── 1. Overlay registration ───────────────────────────────────────────
        final String iconUri = WeatherMarkerOverlay.buildIconUri(context);
        final MapOverlayParent parent = MapOverlayParent.getOrAddParent(
                view, WeatherMarkerOverlay.PARENT_ID, "Weather", iconUri, 0, false);
        weatherOverlay = new WeatherMarkerOverlay(view, context);
        windOverlay    = new WindMarkerOverlay(view, context);
        view.getMapOverlayManager().addOverlay(parent, weatherOverlay);
        view.getMapOverlayManager().addOverlay(parent, windOverlay);
        Log.d(TAG, "Overlays registered");

        // ── 2. Marker managers ────────────────────────────────────────────────
        final WeatherMarkerManager markerManager =
                new WeatherMarkerManager(view, context, weatherOverlay);
        final WindMarkerManager windMarkerManager =
                new WindMarkerManager(view, context, windOverlay);

        // ── 3. Radial-menu factory (programmatic — no XML assets needed) ──────
        menuFactory = new WeatherMenuFactory(context, view);
        MapMenuReceiver.getInstance().registerMapMenuFactory(menuFactory);
        Log.d(TAG, "MenuFactory registered");

        // ── 4. Map placement tool ─────────────────────────────────────────────
        WeatherPlaceTool.register(context, view);

        // ── 5. Drop-down receiver ─────────────────────────────────────────────
        ddr = new WeatherDropDownController(view, context, markerManager, windMarkerManager);
        DocumentedIntentFilter f = new DocumentedIntentFilter();
        f.addAction(WeatherDropDownController.SHOW_PLUGIN);
        registerDropDownReceiver(ddr, f);
        Log.d(TAG, "DropDownController registered");

        // ── 6. Preference fragment ────────────────────────────────────────────
        ToolsPreferenceFragment.register(
                new ToolsPreferenceFragment.ToolPreference(
                        context.getString(R.string.app_name) + " Preferences",
                        "Settings for the DTAKC Weather Plugin",
                        "dtakcWeatherPreference",
                        context.getResources().getDrawable(R.drawable.ic_launcher, null),
                        new WeatherPreferenceFragment(context)));
    }

    @Override
    protected void onDestroyImpl(Context context, MapView view) {
        WeatherPlaceTool.unregister();

        if (menuFactory != null) {
            MapMenuReceiver.getInstance().unregisterMapMenuFactory(menuFactory);
            menuFactory = null;
        }

        if (ddr != null) {
            ddr.clearWindShapes();
            ddr = null;
        }

        // Remove overlays from manager
        if (weatherOverlay != null) {
            MapOverlayParent p = MapOverlayParent.getParent(view, WeatherMarkerOverlay.PARENT_ID);
            if (p != null) {
                view.getMapOverlayManager().removeOverlay(p, weatherOverlay);
                view.getMapOverlayManager().removeOverlay(p, windOverlay);
                view.getMapOverlayManager().removeOverlay(p);
            }
            weatherOverlay = null;
            windOverlay    = null;
        }

        // Destroy singletons for hot-swap safety (ISS-02)
        WeatherDatabase.destroyInstance();
        WeatherDataSourceRegistry.destroyInstance();
        Log.d(TAG, "Plugin destroyed — singletons cleared");

        super.onDestroyImpl(context, view);
    }
}
