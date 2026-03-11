package com.atakmap.android.weather;

import android.content.Context;
import android.content.Intent;

import com.atakmap.android.dropdown.DropDownMapComponent;
import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.menu.MapMenuReceiver;
import com.atakmap.android.overlay.MapOverlayParent;
import com.atakmap.android.weather.overlay.WindMapOverlay;
import com.atakmap.android.weather.data.cache.WeatherDatabase;
import com.atakmap.android.weather.overlay.WeatherMapOverlay;
import com.atakmap.android.weather.overlay.WeatherMenuFactory;
import com.atakmap.android.weather.overlay.marker.WeatherMarkerManager;
import com.atakmap.android.weather.overlay.wind.WindMarkerManager;
import com.atakmap.android.weather.plugin.R;
import com.atakmap.app.preferences.ToolsPreferenceFragment;
import com.atakmap.coremap.log.Log;

/**
 * WeatherMapComponent — ATAK MapComponent lifecycle entry point.
 *
 * ── Responsibilities ──────────────────────────────────────────────────────────
 *
 *  1. Register WeatherMapOverlay with MapOverlayManager so the "Weather" group
 *     is in the ATAK render tree and markers are visible on the map.
 *
 *  2. Construct WeatherMarkerManager (which needs both the mapView and the
 *     overlay) and inject it into WeatherDropDownReceiver.
 *
 *  3. Register WeatherDropDownReceiver for the SHOW_PLUGIN intent.
 *
 *  4. On destroy: unregister the overlay, close the Room database.
 *
 * ── Why overlay registration must happen first ────────────────────────────────
 *
 * WeatherMarkerManager calls group.createMarker() which adds items to the
 * WeatherMapOverlay's group. The group must be registered with the overlay
 * system BEFORE any marker is placed, otherwise the marker exists in an
 * orphaned group that is not connected to the renderer.
 *
 * Order: overlay → manager → receiver (this exact sequence is required).
 */
public class WeatherMapComponent extends DropDownMapComponent {

    private static final String TAG = "WeatherMapComponent";

    private WeatherMapOverlay weatherOverlay;
    private WindMapOverlay           windOverlay;
    private WeatherDropDownReceiver  ddr;
    private WeatherMenuFactory menuFactory;

    @Override
    public void onCreate(final Context context, Intent intent, final MapView view) {
        context.setTheme(R.style.ATAKPluginTheme);
        super.onCreate(context, intent, view);

        // Step 1 — Create a "Weather" parent folder in the Overlay Manager,
        // then register two child overlays:
        //
        //   weather.overlay  (MapOverlayParent — "Weather" folder)
        //   ├── weather.marker  (WeatherMapOverlay — weather obs markers)
        //   └── weather.wind    (WindMapOverlay    — wind barb markers)
        //
        // getOrAddParent() is idempotent: safe across hot-swaps and re-installs.
        final String iconUri = WeatherMapOverlay.buildIconUri(context);
        final MapOverlayParent overlayParent = MapOverlayParent.getOrAddParent(
                view,
                WeatherMapOverlay.PARENT_ID,   // "weather.overlay"
                "Weather",
                iconUri,
                0,
                false);
        weatherOverlay = new WeatherMapOverlay(view, context);
        windOverlay    = new WindMapOverlay(view, context);
        view.getMapOverlayManager().addOverlay(overlayParent, weatherOverlay);
        view.getMapOverlayManager().addOverlay(overlayParent, windOverlay);
        Log.d(TAG, "Weather overlays registered: parent=" + WeatherMapOverlay.PARENT_ID
                + " children=[" + WeatherMapOverlay.OVERLAY_ID
                + ", " + WindMapOverlay.OVERLAY_ID + "]");

        // Step 2 — Create the marker manager, injecting the overlay reference.
        final WeatherMarkerManager markerManager =
                new WeatherMarkerManager(view, context, weatherOverlay);

        // Step 2b — Register the programmatic radial menu factory.
        //
        // registerMenu(type, path) and setRadialMenu(path) BOTH resolve through
        // MapAssets which uses the ATAK host app's AssetManager — plugin APK
        // assets are invisible to it, causing FileNotFoundException.
        //
        // registerMapMenuFactory() is the correct plugin API: the factory builds
        // a MapMenuWidget programmatically at tap-time. No file I/O needed.
        // The factory checks the marker UID prefix ("wx_*") before responding.
        menuFactory = new WeatherMenuFactory(context);
        MapMenuReceiver.getInstance().registerMapMenuFactory(menuFactory);
        Log.d(TAG, "WeatherMenuFactory registered");

        // Step 3 — Create and register the drop-down receiver, injecting manager.
        final WindMarkerManager windMarkerManager =
                new WindMarkerManager(view, context, windOverlay);
        ddr = new WeatherDropDownReceiver(view, context, markerManager, windMarkerManager);
        final DocumentedIntentFilter ddFilter = new DocumentedIntentFilter();
        ddFilter.addAction(WeatherDropDownReceiver.SHOW_PLUGIN);
        registerDropDownReceiver(ddr, ddFilter);
        Log.d(TAG, "WeatherDropDownReceiver registered");

        // Optional: register preference fragment
        ToolsPreferenceFragment.register(
                new ToolsPreferenceFragment.ToolPreference(
                        context.getString(R.string.app_name) + " Preferences",
                        "Settings for the Weather Plugin",
                        "weatherPreference",
                        context.getResources().getDrawable(R.drawable.ic_launcher, null),
                        new com.atakmap.android.weather.infrastructure.preferences
                                .WeatherPreferenceFragment(context)));
    }

    @Override
    protected void onDestroyImpl(final Context context, final MapView view) {
        // Unregister menu factory
        if (menuFactory != null) {
            MapMenuReceiver.getInstance().unregisterMapMenuFactory(menuFactory);
            Log.d(TAG, "WeatherMenuFactory unregistered");
        }

        // Unregister overlay — removes it from the Overlay Manager and
        // disconnects the group from the render pipeline.
        if (weatherOverlay != null) {
            // Remove child overlays from parent, then remove parent folder
            MapOverlayParent p = MapOverlayParent.getParent(
                    view, WeatherMapOverlay.PARENT_ID);
            if (p != null) {
                if (weatherOverlay != null)
                    view.getMapOverlayManager().removeOverlay(p, weatherOverlay);
                if (windOverlay != null)
                    view.getMapOverlayManager().removeOverlay(p, windOverlay);
                view.getMapOverlayManager().removeOverlay(p);
            }
            Log.d(TAG, "WeatherMapOverlay unregistered");
            weatherOverlay = null;
        }

        // Close Room database singleton.
        WeatherDatabase.destroyInstance();
        Log.d(TAG, "WeatherDatabase closed");

        super.onDestroyImpl(context, view);
    }
}
