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
import com.atakmap.android.weather.overlay.heatmap.HeatmapMapOverlay;
import com.atakmap.android.weather.overlay.heatmap.HeatmapOverlayManager;
import com.atakmap.android.weather.overlay.radar.RadarMapOverlay;
import com.atakmap.android.weather.overlay.radar.RadarOverlayManager;
import com.atakmap.android.weather.overlay.wind.WindEffectShape;
import com.atakmap.android.weather.overlay.weather.WeatherBitmapWidget;
import com.atakmap.android.weather.overlay.weather.WeatherHudWidget;
import com.atakmap.android.weather.overlay.wind.WindHudWidget;
import com.atakmap.android.weather.util.ThemeManager;
import com.atakmap.android.weather.overlay.aviation.SigmetOverlayManager;
import com.atakmap.android.weather.overlay.lightning.LightningOverlayManager;
import com.atakmap.android.weather.overlay.cbrn.CbrnOverlayManager;
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
 *   ├── weather.radar    (RadarMapOverlay   — precipitation radar tiles)
 *   └── weather.heatmap  (HeatmapMapOverlay — forecast heatmap overlay)  ← NEW (Sprint 11)
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
    private WeatherMapOverlay  weatherOverlay;
    private WindMapOverlay     windOverlay;
    private RadarMapOverlay    radarOverlay;
    private HeatmapMapOverlay  heatmapOverlay;

    // ── Managers ──────────────────────────────────────────────────────────────
    private RadarOverlayManager   radarManager;
    private RadarTileCache        radarTileCache;
    private HeatmapOverlayManager heatmapManager;
    private WindHudWidget         windHudWidget;
    private WeatherHudWidget      weatherHudWidget;
    private WeatherBitmapWidget   bitmapWidget;      // Sprint 13: Approach B widget

    /** BroadcastReceiver for the Bitmap HUD toggle intent (Sprint 13 — S13.5). */
    private android.content.BroadcastReceiver bitmapHudToggleReceiver;

    // ── Sprint 14: R&D overlay managers ──────────────────────────────────────
    private SigmetOverlayManager    sigmetManager;
    private LightningOverlayManager lightningManager;
    private CbrnOverlayManager      cbrnManager;
    private com.atakmap.android.weather.overlay.heatmap.HeatmapLegendWidget heatmapLegendWidget;
    private com.atakmap.android.weather.overlay.wind.WindArrowOverlayView   windArrowOverlay;
    private com.atakmap.android.weather.overlay.wind.WindParticleLayer       windParticleLayer;

    // ── CoT integration (Sprint 3) ──────────────────────────────────────────
    private com.atakmap.android.weather.cot.WeatherCotImporter cotImporter;

    // ── DDR ───────────────────────────────────────────────────────────────────
    private WeatherDropDownReceiver ddr;
    private WeatherMenuFactory      menuFactory;

    /** BroadcastReceiver for the HUD toggle intent sent by the DDR WIND tab button. */
    private android.content.BroadcastReceiver hudToggleReceiver;

    /** BroadcastReceiver for the Weather HUD toggle intent (Sprint 7 — S7.3). */
    private android.content.BroadcastReceiver weatherHudToggleReceiver;

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

        // HeatmapOverlayManager (Sprint 11) — forecast heatmap overlay
        heatmapManager = new HeatmapOverlayManager(view, context);
        heatmapOverlay = new HeatmapMapOverlay(view, context, heatmapManager);

        view.getMapOverlayManager().addOverlay(overlayParent, weatherOverlay);
        view.getMapOverlayManager().addOverlay(overlayParent, windOverlay);
        view.getMapOverlayManager().addOverlay(overlayParent, radarOverlay);
        view.getMapOverlayManager().addOverlay(overlayParent, heatmapOverlay);
        Log.d(TAG, "Weather overlays registered: marker + wind + radar + heatmap");

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
        // Register v2 JSON-driven sources (after built-in Java sources)
        sourceMgr.registerV2Sources(view.getContext());
        Log.d(TAG, "v2 sources registered; total sources: " + sourceMgr.getSourceCount());

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

        // ── Step 4: WindEffectShape (shared between DDR and overlays) ────────
        //
        // WindEffectShape needs the WindMapOverlay — create it here so the DDR
        // and overlay coordinators share the same shape instance.
        final WindEffectShape windEffectShape = new WindEffectShape(view, windOverlay);

        // HUD widgets fully retired — no WindHudWidget, WeatherHudWidget, or
        // WeatherBitmapWidget are created or attached. See git history for
        // the original HUD code (removed because HUDs had no user interaction
        // and the bitmap widget crashed with JNI NativeLayer null pointer).
        windHudWidget = null;
        weatherHudWidget = null;
        bitmapWidget = null;
        hudToggleReceiver = null;
        weatherHudToggleReceiver = null;
        bitmapHudToggleReceiver = null;
        Log.d(TAG, "HUD widgets retired — not created or attached");

        // ── Step 4d: Theme detection (Sprint 13 — S13.3) ──────────────────────
        ThemeManager.detectAtakTheme(view.getContext());

        // ── Step 4e: Sprint 14 R&D Overlay Managers ──────────────────────────
        sigmetManager = new SigmetOverlayManager(view);
        lightningManager = new LightningOverlayManager(view);
        cbrnManager = new CbrnOverlayManager(view);

        // Heatmap legend widget (WIDGETS RenderStack — like Elevation Tool)
        heatmapLegendWidget = new com.atakmap.android.weather.overlay.heatmap.HeatmapLegendWidget(view, context);
        heatmapLegendWidget.attach();

        // Wind arrow overlay (transparent View on MapView)
        windArrowOverlay = new com.atakmap.android.weather.overlay.wind.WindArrowOverlayView(context, view);
        windArrowOverlay.attach();

        // Wind particle flow overlay (Windy.com style) — GL rendered on MAP_SURFACE_OVERLAYS
        // Force GLWindParticleLayer class load so the static GLLayerSpi2 registers
        // BEFORE we add the layer to the render stack.
        try { Class.forName("com.atakmap.android.weather.overlay.wind.GLWindParticleLayer"); }
        catch (ClassNotFoundException ignored) {}
        windParticleLayer = new com.atakmap.android.weather.overlay.wind.WindParticleLayer("Wind Particles");
        windParticleLayer.setVisible(false); // off by default, user enables in Overlays tab
        view.addLayer(MapView.RenderStack.MAP_SURFACE_OVERLAYS, windParticleLayer);

        Log.d(TAG, "Sprint 14+ managers + overlays created");

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
        ddr.setHeatmapManager(heatmapManager);
        ddr.setSigmetManager(sigmetManager);
        ddr.setLightningManager(lightningManager);
        ddr.setCbrnManager(cbrnManager);
        ddr.setHeatmapLegendWidget(heatmapLegendWidget);
        ddr.setWindArrowOverlay(windArrowOverlay);
        ddr.setWindParticleLayer(windParticleLayer);

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

        // ── Step 6: CoT integration (Sprint 3) ────────────────────────────────
        //
        // Register the CoT importer to receive incoming weather/wind observations
        // from other TAK users on the network. Uses CotServiceRemote.CotEventListener.
        cotImporter = new com.atakmap.android.weather.cot.WeatherCotImporter(
                view, weatherOverlay, windOverlay);
        cotImporter.register();
        Log.d(TAG, "WeatherCotImporter registered");

        // Register placement tool
        com.atakmap.android.weather.util.WeatherPlaceTool.register(context, view);

        // Load saved unit system from preferences (Sprint 7 + Tool Prefs fix)
        com.atakmap.android.weather.infrastructure.preferences
                .WeatherPreferenceFragment.loadSavedUnitSystem(view.getContext());

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

        // Unregister CoT importer (Sprint 3)
        if (cotImporter != null) {
            cotImporter.unregister();
            cotImporter = null;
            Log.d(TAG, "WeatherCotImporter unregistered");
        }

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

        if (weatherHudToggleReceiver != null) {
            com.atakmap.android.ipc.AtakBroadcast.getInstance()
                    .unregisterReceiver(weatherHudToggleReceiver);
            weatherHudToggleReceiver = null;
        }

        if (weatherHudWidget != null) {
            weatherHudWidget.detach();
            weatherHudWidget = null;
            Log.d(TAG, "WeatherHudWidget detached");
        }

        // Sprint 13: Detach bitmap widget
        if (bitmapHudToggleReceiver != null) {
            com.atakmap.android.ipc.AtakBroadcast.getInstance()
                    .unregisterReceiver(bitmapHudToggleReceiver);
            bitmapHudToggleReceiver = null;
        }
        if (bitmapWidget != null) {
            bitmapWidget.detach();
            bitmapWidget = null;
            Log.d(TAG, "WeatherBitmapWidget detached");
        }

        // Clear 3D wind shapes before overlay disconnects
        if (ddr != null) ddr.clearWindShapes();

        // Dispose Sprint 14 managers
        if (sigmetManager != null) {
            sigmetManager.dispose();
            sigmetManager = null;
            Log.d(TAG, "SigmetOverlayManager disposed");
        }
        if (lightningManager != null) {
            lightningManager.dispose();
            lightningManager = null;
            Log.d(TAG, "LightningOverlayManager disposed");
        }
        if (cbrnManager != null) {
            cbrnManager.dispose();
            cbrnManager = null;
            Log.d(TAG, "CbrnOverlayManager disposed");
        }

        // Dispose heatmap manager (Sprint 11)
        if (heatmapManager != null) {
            heatmapManager.dispose();
            heatmapManager = null;
            Log.d(TAG, "HeatmapOverlayManager disposed");
        }

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
                if (windOverlay    != null) view.getMapOverlayManager().removeOverlay(p, windOverlay);
                if (radarOverlay   != null) view.getMapOverlayManager().removeOverlay(p, radarOverlay);
                if (heatmapOverlay != null) view.getMapOverlayManager().removeOverlay(p, heatmapOverlay);
                view.getMapOverlayManager().removeOverlay(p);
            }
            Log.d(TAG, "Weather overlays unregistered");
            weatherOverlay  = null;
            windOverlay     = null;
            radarOverlay    = null;
            heatmapOverlay  = null;
        }

        // Detach heatmap legend widget and wind arrow overlay
        if (heatmapLegendWidget != null) {
            heatmapLegendWidget.detach();
            heatmapLegendWidget = null;
        }
        if (windArrowOverlay != null) {
            windArrowOverlay.detach();
            windArrowOverlay = null;
        }
        if (windParticleLayer != null) {
            MapView mv = MapView.getMapView();
            if (mv != null) mv.removeLayer(MapView.RenderStack.MAP_SURFACE_OVERLAYS, windParticleLayer);
            windParticleLayer = null;
        }

        WeatherDatabase.destroyInstance();
        Log.d(TAG, "WeatherDatabase closed");

        super.onDestroyImpl(context, view);
    }

    /** Accessor so DDR / other components can push weather data to the HUD. */
    public WeatherHudWidget getWeatherHudWidget() { return weatherHudWidget; }

    /** Accessor so DDR / other components can push weather data to the bitmap widget. */
    public WeatherBitmapWidget getBitmapWidget() { return bitmapWidget; }

    // ── Sprint 14 accessors ───────────────────────────────────────────────────

    /** @return the SIGMET overlay manager (Sprint 14), or null if not created */
    public SigmetOverlayManager getSigmetManager() { return sigmetManager; }

    /** @return the lightning overlay manager (Sprint 14), or null if not created */
    public LightningOverlayManager getLightningManager() { return lightningManager; }

    /** @return the CBRN dispersion overlay manager (Sprint 14), or null if not created */
    public CbrnOverlayManager getCbrnManager() { return cbrnManager; }
}
