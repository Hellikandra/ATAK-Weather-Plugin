package com.atakmap.android.weather;

import android.content.Context;
import android.content.Intent;

import com.atakmap.android.dropdown.DropDownMapComponent;
import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.menu.MapMenuReceiver;
import com.atakmap.android.overlay.MapOverlayParent;
import com.atakmap.android.weather.data.WeatherRepositoryImpl;
import com.atakmap.android.weather.data.cache.CachingWeatherRepository;
import com.atakmap.android.weather.data.cache.WeatherDatabase;
import com.atakmap.android.weather.data.geocoding.NominatimGeocodingSource;
import com.atakmap.android.weather.data.remote.IWeatherRemoteSource;
import com.atakmap.android.weather.data.remote.WeatherSourceManager;
import com.atakmap.android.weather.domain.repository.IGeocodingRepository;
import com.atakmap.android.weather.infrastructure.preferences.WeatherParameterPreferences;
import com.atakmap.android.weather.overlay.WeatherMapOverlay;
import com.atakmap.android.weather.overlay.WindMapOverlay;
import com.atakmap.android.weather.overlay.WeatherMenuFactory;
import com.atakmap.android.weather.overlay.marker.WeatherMarkerManager;
import com.atakmap.android.weather.data.cache.RadarTileCache;
import com.atakmap.android.weather.overlay.radar.RadarMapOverlay;
import com.atakmap.android.weather.overlay.radar.RadarOverlayManager;
import com.atakmap.android.weather.overlay.wind.WindEffectShape;
import com.atakmap.android.weather.overlay.wind.WindHudWidget;
import com.atakmap.android.weather.overlay.wind.WindMarkerManager;
import com.atakmap.android.weather.plugin.R;
import com.atakmap.android.weather.presentation.viewmodel.WindProfileViewModel;
import com.atakmap.app.preferences.ToolsPreferenceFragment;
import com.atakmap.coremap.log.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * WeatherMapComponent — ATAK MapComponent lifecycle entry point.
 *
 * <h3>Overlay hierarchy registered here</h3>
 * <pre>
 *   weather.overlay  (MapOverlayParent — "Weather" folder)
 *   ├── weather.marker   (WeatherMapOverlay — wx observation markers)
 *   ├── weather.wind     (WindMapOverlay    — wind barb markers)
 *   └── weather.radar    (RadarMapOverlay   — precipitation radar tiles)  ← NEW
 * </pre>
 *
 * <h3>RenderStack layers registered here</h3>
 * <pre>
 *   MapView.RenderStack.WIDGETS  ← WindHudWidget persistent map overlay  ← NEW
 * </pre>
 *
 * <h3>Changes vs original</h3>
 * <ul>
 *   <li>{@link RadarOverlayManager} created once here and injected into both
 *       the new {@link RadarMapOverlay} (for Overlay Manager toggle) and the
 *       DDR's {@link com.atakmap.android.weather.presentation.view.RadarTabCoordinator}
 *       (for DDR Show/Hide buttons). The {@code ActiveStateListener} keeps both in sync.</li>
 *   <li>{@link WindHudWidget} created and attached here. It observes the shared
 *       {@link WindProfileViewModel} that the DDR also uses, so scrubbing the hour
 *       slider in either UI updates both simultaneously.</li>
 *   <li>{@link WindProfileViewModel} is created here (previously inside DDR's
 *       {@code initDependencies}) so the HUD and DDR share the same instance.</li>
 * </ul>
 *
 * <h3>Wiring order (strict)</h3>
 * <ol>
 *   <li>Overlay registration (overlays must exist before any markers are placed).</li>
 *   <li>Manager creation (marker managers need the overlay groups).</li>
 *   <li>ViewModel creation (needs the repository from step 2).</li>
 *   <li>HUD attachment (needs the ViewModel and WindEffectShape).</li>
 *   <li>DDR registration (receives managers and ViewModel).</li>
 * </ol>
 */
public class WeatherMapComponent extends DropDownMapComponent {

    private static final String TAG = "WeatherMapComponent";

    // ── Overlay Manager entries ───────────────────────────────────────────────
    private WeatherMapOverlay weatherOverlay;
    private WindMapOverlay    windOverlay;
    private RadarMapOverlay   radarOverlay;

    // ── Managers ──────────────────────────────────────────────────────────────
    private RadarOverlayManager radarManager;
    private RadarTileCache      radarTileCache;
    private WindHudWidget       windHudWidget;

    // ── DDR ───────────────────────────────────────────────────────────────────
    private WeatherDropDownReceiver ddr;
    private WeatherMenuFactory      menuFactory;

    /** BroadcastReceiver for the HUD toggle intent sent by the DDR WIND tab button. */
    private android.content.BroadcastReceiver hudToggleReceiver;

    @Override
    public void onCreate(final Context context, Intent intent, final MapView view) {
        context.setTheme(R.style.ATAKPluginTheme);
        super.onCreate(context, intent, view);

        // ── Step 1: Overlay Manager registrations ─────────────────────────────
        //
        // Create the "Weather" parent folder and three child overlays.
        // Registration order within the parent does not matter for rendering,
        // but MUST happen before any MapGroup.createMarker() calls.
        final String iconUri = WeatherMapOverlay.buildIconUri(context);
        final MapOverlayParent overlayParent = MapOverlayParent.getOrAddParent(
                view,
                WeatherMapOverlay.PARENT_ID,
                "Weather",
                iconUri,
                0,
                false);

        weatherOverlay = new WeatherMapOverlay(view, context);
        windOverlay    = new WindMapOverlay(view, context);

        // RadarOverlayManager is created first so it can be injected into
        // RadarMapOverlay (for the Overlay Manager toggle).
        // L2 disk cache (RadarTileCache) is created and injected here — separate
        // SQLite DB from weather_cache.db.  Pattern mirrors ATAK's TileProxy +
        // MBTilesContainer architecture (see Report 04).
        radarManager = new RadarOverlayManager(view);
        radarTileCache = new RadarTileCache(view.getContext());
        radarManager.setDiskCache(radarTileCache);
        radarOverlay = new RadarMapOverlay(view, context, radarManager);

        view.getMapOverlayManager().addOverlay(overlayParent, weatherOverlay);
        view.getMapOverlayManager().addOverlay(overlayParent, windOverlay);
        view.getMapOverlayManager().addOverlay(overlayParent, radarOverlay);
        Log.d(TAG, "Weather overlays registered: marker + wind + radar");

        // ── Step 2: Managers ──────────────────────────────────────────────────
        final WeatherMarkerManager markerManager =
                new WeatherMarkerManager(view, context, weatherOverlay);
        final WindMarkerManager windMarkerManager =
                new WindMarkerManager(view, context, windOverlay);

        menuFactory = new WeatherMenuFactory(context);
        MapMenuReceiver.getInstance().registerMapMenuFactory(menuFactory);
        Log.d(TAG, "WeatherMenuFactory registered");

        // ── Step 3: Shared repository + ViewModel ─────────────────────────────
        //
        // WindProfileViewModel is created HERE so WindHudWidget and the DDR share
        // the same instance — scrubbing the hour slider in either UI updates both.
        WeatherSourceManager sourceMgr = WeatherSourceManager.getInstance(view.getContext());
        Map<String, IWeatherRemoteSource> sources = new HashMap<>();
        for (WeatherSourceManager.SourceEntry entry : sourceMgr.getAvailableEntries()) {
            IWeatherRemoteSource src = sourceMgr.getSourceById(entry.sourceId);
            if (src != null) sources.put(entry.sourceId, src);
        }

        WeatherParameterPreferences paramPrefs = new WeatherParameterPreferences(context);
        WeatherRepositoryImpl networkRepo = new WeatherRepositoryImpl(sources, sourceMgr.getActiveSourceId());
        networkRepo.setParameterPreferences(paramPrefs);
        CachingWeatherRepository cachingRepo = new CachingWeatherRepository(
                networkRepo,
                WeatherDatabase.getInstance(view.getContext()).weatherDao(),
                paramPrefs);
        cachingRepo.purgeExpired();

        final WindProfileViewModel windViewModel = new WindProfileViewModel(cachingRepo);

        // ── Step 4: WindHudWidget (WIDGETS RenderStack) ───────────────────────
        //
        // WindEffectShape needs the WindMapOverlay — create it here so HUD
        // and DDR share the same shape instance (avoids duplicate cone layers).
        final WindEffectShape windEffectShape = new WindEffectShape(view, windOverlay);
        windHudWidget = new WindHudWidget(view, context, windViewModel, windEffectShape);
        windHudWidget.attach();
        Log.d(TAG, "WindHudWidget attached to WIDGETS RenderStack");

        // Register a receiver so the DDR's "Hide Wind HUD" button can toggle
        // WindHudWidget.setVisible() on the component-level instance.
        hudToggleReceiver = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context ctx, android.content.Intent i) {
                boolean show = i.getBooleanExtra("visible", true);
                if (windHudWidget != null) windHudWidget.setVisible(show);
            }
        };
        com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter hudFilter =
                new com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter();
        hudFilter.addAction(com.atakmap.android.weather.overlay.wind.WindHudWidget.ACTION_TOGGLE_HUD);
        com.atakmap.android.ipc.AtakBroadcast.getInstance()
                .registerReceiver(hudToggleReceiver, hudFilter);

        // ── Step 5: DDR registration ──────────────────────────────────────────
        //
        // Pass the shared windViewModel and windEffectShape so the DDR uses the
        // same ViewModel state as the HUD. The DDR's initDependencies() creates
        // its own WeatherViewModel (for weather data) but delegates wind to these.
        ddr = new WeatherDropDownReceiver(
                view, context,
                markerManager, windMarkerManager,
                windViewModel, windEffectShape,
                radarManager);

        // Wire the Overlay Manager toggle ↔ DDR Show/Hide buttons.
        // When the user toggles radar in the Overlay Manager, the DDR status
        // label updates; when the DDR's Show button is pressed, the Overlay
        // Manager checkbox stays in sync via radarManager.isActive().
        radarManager.setActiveStateListener(isActive -> {
            // Notify the DDR so it can update the status label in the CONF tab.
            ddr.onRadarActiveChanged(isActive);
        });

        final DocumentedIntentFilter ddFilter = new DocumentedIntentFilter();
        ddFilter.addAction(WeatherDropDownReceiver.SHOW_PLUGIN);
        registerDropDownReceiver(ddr, ddFilter);
        Log.d(TAG, "WeatherDropDownReceiver registered");

        // Register placement tool
        com.atakmap.android.weather.util.WeatherPlaceTool.register(context, view);

        // Register preference fragment
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
        com.atakmap.android.weather.util.WeatherPlaceTool.unregister();

        if (menuFactory != null) {
            MapMenuReceiver.getInstance().unregisterMapMenuFactory(menuFactory);
        }

        // Detach HUD and remove from WIDGETS stack
        if (hudToggleReceiver != null) {
            com.atakmap.android.ipc.AtakBroadcast.getInstance()
                    .unregisterReceiver(hudToggleReceiver);
            hudToggleReceiver = null;
        }

        if (windHudWidget != null) {
            windHudWidget.detach();
            windHudWidget = null;
            Log.d(TAG, "WindHudWidget detached");
        }

        // Clear 3D wind shapes before overlay disconnects
        if (ddr != null) ddr.clearWindShapes();

        // Dispose radar manager (stops tile downloads, removes overlay view)
        if (radarManager != null) {
            radarManager.dispose();
            radarManager = null;
        }

        // Dispose radar tile cache (flush pending writes, close SQLite)
        if (radarTileCache != null) {
            radarTileCache.dispose();
            radarTileCache = null;
            Log.d(TAG, "RadarTileCache disposed");
        }

        // Remove all child overlays, then parent folder
        if (weatherOverlay != null) {
            MapOverlayParent p = MapOverlayParent.getParent(view, WeatherMapOverlay.PARENT_ID);
            if (p != null) {
                view.getMapOverlayManager().removeOverlay(p, weatherOverlay);
                if (windOverlay   != null) view.getMapOverlayManager().removeOverlay(p, windOverlay);
                if (radarOverlay  != null) view.getMapOverlayManager().removeOverlay(p, radarOverlay);
                view.getMapOverlayManager().removeOverlay(p);
            }
            Log.d(TAG, "Weather overlays unregistered");
            weatherOverlay = null;
            windOverlay    = null;
            radarOverlay   = null;
        }

        WeatherDatabase.destroyInstance();
        Log.d(TAG, "WeatherDatabase closed");

        super.onDestroyImpl(context, view);
    }
}
