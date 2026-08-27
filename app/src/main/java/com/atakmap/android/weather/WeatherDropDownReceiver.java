package com.atakmap.android.weather;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.AdapterView;
import android.widget.TextView;
import android.widget.Toast;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.weather.data.WeatherRepositoryImpl;
import com.atakmap.android.weather.data.cache.CachingWeatherRepository;
import com.atakmap.android.weather.data.cache.ForecastRecorder;
import com.atakmap.android.weather.data.remote.IWeatherRemoteSource;
import com.atakmap.android.weather.data.remote.SourceDefinitionLoader;
import com.atakmap.android.weather.data.remote.WeatherSourceDefinition;
import com.atakmap.android.weather.data.remote.WeatherSourceManager;
import com.atakmap.android.weather.overlay.radar.RadarOverlayManager;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.LocationSnapshot;
import com.atakmap.android.weather.domain.model.LocationSource;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.infrastructure.preferences.WeatherParameterPreferences;
import com.atakmap.android.weather.overlay.WeatherMapOverlay;
import com.atakmap.android.weather.overlay.WindMapOverlay;
import com.atakmap.android.weather.overlay.marker.WeatherMarkerManager;
import com.atakmap.android.weather.overlay.wind.WindEffectShape;
import com.atakmap.android.weather.overlay.wind.WindMarkerManager;
import com.atakmap.android.weather.plugin.R;
import com.atakmap.android.weather.domain.model.DailyForecastModel;
import com.atakmap.android.weather.domain.model.WindProfileModel;
import com.atakmap.android.weather.domain.service.BriefingDocument;
import com.atakmap.android.weather.domain.service.BriefingGenerator;
// ComparisonView import removed — comparison section retired from UI
import com.atakmap.android.weather.presentation.view.CurrentWeatherView;
import com.atakmap.android.weather.presentation.view.DailyForecastView;
import com.atakmap.android.weather.presentation.view.ParametersView;
import com.atakmap.android.weather.presentation.view.SourceManagerView;
import com.atakmap.android.weather.presentation.view.ChartCoordinator;
import com.atakmap.android.weather.presentation.view.DashboardCoordinator;
import com.atakmap.android.weather.presentation.view.MarkerTabCoordinator;
import com.atakmap.android.weather.presentation.view.OverlayTabCoordinator;
// RadarTabCoordinator import removed — Sprint 28
import com.atakmap.android.weather.presentation.view.SettingsCoordinator;
import com.atakmap.android.weather.presentation.view.WeatherChartView;
import com.atakmap.android.weather.presentation.view.WindProfileView;
import com.atakmap.android.weather.presentation.view.WindChartView;
import com.atakmap.android.weather.presentation.view.WindTabCoordinator;
import com.atakmap.android.weather.presentation.viewmodel.UiState;
import com.atakmap.android.weather.presentation.viewmodel.WeatherObserverRegistry;
import com.atakmap.android.weather.presentation.viewmodel.WeatherViewModel;
import com.atakmap.android.weather.presentation.viewmodel.WindProfileViewModel;
import com.atakmap.android.weather.data.cache.MissionPrepManager;
// CollapsibleSection import removed — moved to SettingsCoordinator (Sprint 21)
import com.atakmap.android.weather.util.AutoRefreshManager;
import com.atakmap.android.weather.util.MapPointPicker;
import com.atakmap.android.weather.util.WeatherPlaceTool;
import com.atakmap.android.weather.util.WeatherUiUtils;
import com.atakmap.android.weather.util.WmoCodeMapper;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.importexport.CotEventFactory;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ATAK drop-down entry point for the WeatherTool plugin.
 *
 * <h3>Refactoring summary (vs original ~1 865-line version)</h3>
 * <ul>
 *   <li><b>DDR decomposition</b> — radar and wind tab initialisation extracted
 *       to {@link WindTabCoordinator}.</li>
 *   <li><b>Observer registry</b> — the 14 typed observer fields replaced by a
 *       single {@link WeatherObserverRegistry}; {@code removeObservers()} is now
 *       a one-liner.</li>
 *   <li><b>WindSlot encapsulation</b> — DDR no longer mutates
 *       {@code WindSlot.rangeM} / {@code heightM} / {@code sourceId} directly;
 *       it delegates to the new typed ViewModel methods.</li>
 *   <li><b>CacheStatusProvider interface</b> — {@code WeatherViewModel} constructor
 *       no longer imports {@code CachingWeatherRepository}; the cache badge is
 *       wired via the interface.</li>
 *   <li><b>WeatherUiUtils</b> — {@code makeDarkSpinnerAdapter},
 *       {@code isoDayOfWeek}, {@code buildMarkerUid} extracted to utility class.</li>
 *   <li><b>suppressSeekSync dead field removed.</b></li>
 *   <li><b>pendingPick* consolidated</b> — three fields collapsed to a single
 * </ul>
 *
 * <h3>Feature: "Tap Map to Place Weather Marker"</h3>
 * <ol>
 *   <li>User taps "📍 Tap Map to Place Weather Marker" → drop-down collapses,
 *       {@link WeatherPlaceTool} registers for {@code MAP_CONFIRMED_CLICK}.</li>
 *   <li>User taps the map → {@code weatherViewModel.loadWeather()} called at
 *       the picked coordinate.</li>
 *   <li>On success the weather observer auto-places a marker via
 *       {@code markerManager.placeMarker()}.</li>
 *   <li>Drop-down reopens on the Map tab.</li>
 * </ol>
 *
 * <h3>Feature: Wind Effect Drawing</h3>
 * Delegated entirely to {@link WindTabCoordinator}.
 */
public class WeatherDropDownReceiver extends DropDownReceiver
        implements OnStateListener {

    public static final String TAG           = WeatherDropDownReceiver.class.getSimpleName();

    // ── Intent constants (delegated to WeatherConstants — kept as aliases for backward compat) ──
    public static final String SHOW_PLUGIN   = com.atakmap.android.weather.util.WeatherConstants.ACTION_SHOW_PLUGIN;
    public static final String SHARE_MARKER  = com.atakmap.android.weather.util.WeatherConstants.ACTION_SHARE_MARKER;
    public static final String REMOVE_MARKER = com.atakmap.android.weather.util.WeatherConstants.ACTION_REMOVE_MARKER;

    public static final String EXTRA_TARGET_UID    = com.atakmap.android.weather.util.WeatherConstants.EXTRA_TARGET_UID;
    public static final String EXTRA_REQUESTED_TAB = com.atakmap.android.weather.util.WeatherConstants.EXTRA_REQUESTED_TAB;

    // ── Layout ────────────────────────────────────────────────────────────────
    private final View    templateView;
    private final Context pluginContext;
    private final Context appContext;

    /**
     * The plugin's composition root, built once in
     * {@link WeatherMapComponent#onCreate} (finding F20). Every repository,
     * source and preferences object below comes from here rather than being
     * constructed locally.
     */
    private final WeatherDependencies deps;

    // ── ViewModels ────────────────────────────────────────────────────────────
    private WeatherViewModel     weatherViewModel;

    /** Kept so disposeImpl can clear the in-memory wind profile cache. */
    private CachingWeatherRepository cachingRepo;
    /** Kept to apply active-source changes from the PARM spinner. */
    private WeatherRepositoryImpl    networkRepo;

    // ── Observer registry (replaces 14 typed observer fields) ─────────────────
    private final WeatherObserverRegistry observers = new WeatherObserverRegistry();

    // ── Tab coordinators ──────────────────────────────────────────────────────
    // RadarTabCoordinator removed — Sprint 28 (radar controls in OverlayTabCoordinator)
    private WindTabCoordinator   windTabCoordinator;
    private OverlayTabCoordinator overlayTabCoordinator;
    private MarkerTabCoordinator  markerTabCoordinator;

    // ── View helpers ──────────────────────────────────────────────────────────
    private CurrentWeatherView currentWeatherView;   // dashboard (first match)
    private CurrentWeatherView weatherTabView;        // Weather tab (subTabWidget1)
    private DailyForecastView  dailyForecastView;    // dashboard
    private DailyForecastView  weatherTabDailyView;  // Weather tab
    private WindProfileView    windProfileView;
    private ParametersView     parametersView;
    private WeatherChartView   chartView;
    private SeekBar            chartOverlaySeekBar;
    private TextView           fltCatBadge;

    // ── Extracted coordinators (Sprint 21) ────────────────────────────────────
    private DashboardCoordinator dashboardCoordinator;
    private ChartCoordinator     chartCoordinator;
    private SettingsCoordinator  settingsCoordinator;

    // ── Preferences ───────────────────────────────────────────────────────────
    private WeatherParameterPreferences paramPrefs;

    // ── Init guard ────────────────────────────────────────────────────────────
    private boolean initialized = false;

    // ── Navigation state (Sprint 7 — S7.2, refactored Sprint 15) ───────────────
    private View dashboardPanel;
    private View subWeather, subWind, subParm;
    private View subOverlays, subMarkers;  // Sprint 15: new sub-views
    private View currentSubView = null;  // null = dashboard shown

    // ── Topbar navigation (Sprint 15 — S15.3) ────────────────────────────────
    private ImageView navWeather, navWind, navOverlays, navMarkers, navSettings;
    private ImageView btnBack, btnOverflow;
    private TextView topbarTitle;

    // ── Marker managers ───────────────────────────────────────────────────────
    private final WeatherMarkerManager markerManager;
    private final WindMarkerManager    windMarkerManager;

    /**
     * Shared WindProfileViewModel — created in WeatherMapComponent so the
     * WindHudWidget and this DDR observe the same LiveData instance.
     * The DDR does NOT create its own WindProfileViewModel.
     */
    private final WindProfileViewModel windViewModel;

    /**
     * Shared WindEffectShape — same instance used by WindHudWidget and
     * WindTabCoordinator so both draw into the same overlay group.
     */
    private final WindEffectShape sharedWindEffectShape;

    /**
     * RadarOverlayManager — injected from WeatherMapComponent so the DDR
     * Show/Hide buttons and the Overlay Manager toggle act on the same manager.
     */
    private final RadarOverlayManager radarManager;

    /**
     * HeatmapOverlayManager — injected from WeatherMapComponent (Sprint 11)
     * so the CONF tab controls and Overlay Manager toggle share the same instance.
     */
    private com.atakmap.android.weather.overlay.heatmap.HeatmapOverlayManager heatmapManager;

    /**
     * Sprint 14 R&D overlay managers — injected from WeatherMapComponent.
     */
    private com.atakmap.android.weather.overlay.aviation.SigmetOverlayManager sigmetManager;
    private com.atakmap.android.weather.overlay.lightning.LightningOverlayManager lightningManager;
    private com.atakmap.android.weather.overlay.cbrn.CbrnOverlayManager cbrnManager;

    // ── Last known good state ─────────────────────────────────────────────────
    private WeatherModel     lastWeather;
    private LocationSnapshot lastLocation;
    private List<HourlyEntryModel> hourlyCache;
    private List<DailyForecastModel> dailyCache;
    private List<WindProfileModel>   windCache;

    // ── Sprint 12 — Forecast Recorder ────────────────────────────────────────
    private ForecastRecorder forecastRecorder;

    // ── Point-pick state (consolidated from three fields) ─────────────────────



    // ── Last active slot tracking ─────────────────────────────────────────────
    private int    lastActiveSlotIdx  = -1;
    private String lastBoundSourceId  = null;

    // ── HUD toggle state ──────────────────────────────────────────────────────
    /** Tracks whether the HUD is currently visible (toggled from WIND tab). */
    private boolean hudVisible = true;

    // ── Sprint 13: Auto-Refresh ──────────────────────────────────────────────
    private AutoRefreshManager autoRefreshManager;
    private long lastUpdateMs = 0;  // epoch millis of last successful data load

    // ── Sprint 13: Mission Prep ──────────────────────────────────────────────
    private MissionPrepManager missionPrepManager;

    // Dashboard state views delegated to DashboardCoordinator — fields removed (Sprint 27)

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Full constructor — receives shared instances from {@link WeatherMapComponent}.
     *
     * @param windViewModel        shared ViewModel also observed by {@link com.atakmap.android.weather.overlay.wind.WindHudWidget}
     * @param sharedWindEffectShape shared WindEffectShape also used by WindHudWidget
     * @param radarManager         shared RadarOverlayManager also used by RadarMapOverlay
     */
    public WeatherDropDownReceiver(final MapView mapView,
                                   final Context context,
                                   final WeatherDependencies deps,
                                   final WeatherMarkerManager markerManager,
                                   final WindMarkerManager windMarkerManager,
                                   final WindProfileViewModel windViewModel,
                                   final WindEffectShape sharedWindEffectShape,
                                   final RadarOverlayManager radarManager) {
        super(mapView);
        this.pluginContext          = context;
        this.appContext             = mapView.getContext();
        this.deps                   = deps;
        this.markerManager          = markerManager;
        this.windMarkerManager      = windMarkerManager;
        this.windViewModel          = windViewModel;
        this.sharedWindEffectShape  = sharedWindEffectShape;
        this.radarManager           = radarManager;
        templateView = PluginLayoutInflater.inflate(context, R.layout.main_layout, null);
    }

    /**
     * Inject the shared HeatmapOverlayManager (Sprint 11).
     * Called from WeatherMapComponent after construction.
     */
    public void setHeatmapManager(
            com.atakmap.android.weather.overlay.heatmap.HeatmapOverlayManager mgr) {
        this.heatmapManager = mgr;
    }

    /** Accessor for heatmap manager — used by tab coordinators. */
    public com.atakmap.android.weather.overlay.heatmap.HeatmapOverlayManager getHeatmapManager() {
        return heatmapManager;
    }

    /** Inject the shared SigmetOverlayManager (Sprint 14). */
    public void setSigmetManager(
            com.atakmap.android.weather.overlay.aviation.SigmetOverlayManager mgr) {
        this.sigmetManager = mgr;
    }

    /** Accessor for SIGMET manager — used by tab coordinators. */
    public com.atakmap.android.weather.overlay.aviation.SigmetOverlayManager getSigmetManager() {
        return sigmetManager;
    }

    /** Inject the shared LightningOverlayManager (Sprint 14). */
    public void setLightningManager(
            com.atakmap.android.weather.overlay.lightning.LightningOverlayManager mgr) {
        this.lightningManager = mgr;
    }

    /** Accessor for lightning manager — used by tab coordinators. */
    public com.atakmap.android.weather.overlay.lightning.LightningOverlayManager getLightningManager() {
        return lightningManager;
    }

    /** Inject the shared CbrnOverlayManager (Sprint 14). */
    public void setCbrnManager(
            com.atakmap.android.weather.overlay.cbrn.CbrnOverlayManager mgr) {
        this.cbrnManager = mgr;
    }

    /** Accessor for CBRN manager — used by tab coordinators. */
    public com.atakmap.android.weather.overlay.cbrn.CbrnOverlayManager getCbrnManager() {
        return cbrnManager;
    }

    /** Inject the heatmap legend widget for overlay coordinator control. */
    public void setHeatmapLegendWidget(
            com.atakmap.android.weather.overlay.heatmap.HeatmapLegendWidget w) {
        this.heatmapLegendWidget = w;
    }
    private com.atakmap.android.weather.overlay.heatmap.HeatmapLegendWidget heatmapLegendWidget;

    /** Inject the wind arrow overlay for overlay coordinator control. */
    public void setWindArrowOverlay(
            com.atakmap.android.weather.overlay.wind.WindArrowOverlayView v) {
        this.windArrowOverlay = v;
    }
    private com.atakmap.android.weather.overlay.wind.WindArrowOverlayView windArrowOverlay;

    /** Inject the wind particle layer for Windy-style particle flow. */
    public void setWindParticleLayer(
            com.atakmap.android.weather.overlay.wind.WindParticleLayer layer) {
        this.windParticleLayer = layer;
    }
    private com.atakmap.android.weather.overlay.wind.WindParticleLayer windParticleLayer;

    /** Inject the V4 bitmap particle overlay (full-screen rendering). */
    public void setWindParticleView(
            com.atakmap.android.weather.overlay.wind.WindParticleBitmapView view) {
        this.windParticleView = view;
    }
    private com.atakmap.android.weather.overlay.wind.WindParticleBitmapView windParticleView;

    // ── onReceive ─────────────────────────────────────────────────────────────

    @Override
    public void onReceive(final Context context, Intent intent) {
        final String action = intent.getAction();
        if (action == null) return;

        if (SHARE_MARKER.equals(action)) {
            handleShareMarker(intent.getStringExtra(EXTRA_TARGET_UID));
            return;
        }

        if (REMOVE_MARKER.equals(action)) {
            final String uid = intent.getStringExtra(EXTRA_TARGET_UID);
            if (uid != null) {
                if (uid.startsWith(WindMarkerManager.UID_PREFIX) && windMarkerManager != null)
                    windMarkerManager.removeMarker(uid);
                else if (markerManager != null)
                    markerManager.removeMarker(uid);
            }
            return;
        }

        if (!SHOW_PLUGIN.equals(action)) return;

        showDropDown(templateView,
                HALF_WIDTH, FULL_HEIGHT,
                FULL_WIDTH, HALF_HEIGHT,
                false, this);

        if (!initialized) {
            initDependencies();
            initTabs();
            initViewHelpers();
            observeViewModels();
            registerUnitPrefListener();
            com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter extras =
                    new com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter();
            extras.addAction(SHARE_MARKER);
            extras.addAction(REMOVE_MARKER);
            com.atakmap.android.ipc.AtakBroadcast.getInstance()
                    .registerReceiver(this, extras);
            initialized = true;
        }

        final String targetUid  = intent.getStringExtra(EXTRA_TARGET_UID);
        final String requestTab = intent.getStringExtra(EXTRA_REQUESTED_TAB);
        if (targetUid != null) {
            handleMarkerDetails(targetUid, requestTab);
        } else {
            triggerAutoLoad();
        }
    }

    // ── Dependency wiring ─────────────────────────────────────────────────────

    private void initDependencies() {
        // Fix F20 — this method used to build a second, independent copy of the
        // whole data layer: its own source map, WeatherParameterPreferences,
        // WeatherRepositoryImpl and CachingWeatherRepository. The Wind tab (fed
        // from WeatherMapComponent's copy) and the Weather tab (fed from this
        // one) therefore ran on separate caches over the same database. Both now
        // read from the single graph built in WeatherMapComponent.onCreate.
        //
        // Because these are references rather than constructions, this method is
        // idempotent — running it again after disposeImpl() cannot produce a
        // third graph.
        networkRepo = deps.networkRepository();
        paramPrefs  = deps.parameterPreferences();
        cachingRepo = deps.repository();

        weatherViewModel = new WeatherViewModel(cachingRepo, deps.geocoding());
        // windViewModel is injected via constructor — shared with WindHudWidget.
        // Do NOT create a new instance here; that would break the shared state.

        // Sprint 12 (S12.2): Forecast recorder for historical snapshots
        forecastRecorder = ForecastRecorder.getInstance(appContext);
        forecastRecorder.purgeOlderThan(30); // keep 30 days of history
    }

    private void initTabs() {
        // Dashboard + Navigation (Sprint 7 — S7.2, refactored Sprint 15)
        dashboardPanel = templateView.findViewById(R.id.dashboard_panel);
        subWeather     = templateView.findViewById(R.id.subTabWidget1);
        subWind        = templateView.findViewById(R.id.subTabWidget2);
        // subTabWidget3 (tab_config / subMap) retired — controls moved to tab_overlays + tab_markers
        subParm        = templateView.findViewById(R.id.subTabWidget4);
        subOverlays    = templateView.findViewById(R.id.subTabWidget5);
        subMarkers     = templateView.findViewById(R.id.subTabWidget6);

        // ── Topbar wiring (Sprint 15 — S15.3) ───────────────────────────────
        navWeather  = templateView.findViewById(R.id.nav_weather);
        navWind     = templateView.findViewById(R.id.nav_wind);
        navOverlays = templateView.findViewById(R.id.nav_overlays);
        navMarkers  = templateView.findViewById(R.id.nav_markers);
        navSettings = templateView.findViewById(R.id.nav_settings);
        btnBack     = templateView.findViewById(R.id.btn_back);
        btnOverflow = templateView.findViewById(R.id.btn_overflow);
        topbarTitle = templateView.findViewById(R.id.topbar_title);

        // Wire navigation clicks
        if (navWeather  != null) navWeather.setOnClickListener(v -> switchToView(subWeather, "Weather"));
        if (navWind     != null) navWind.setOnClickListener(v -> switchToView(subWind, "Wind"));
        if (navOverlays != null) navOverlays.setOnClickListener(v -> switchToView(subOverlays, "Overlays"));
        if (navMarkers  != null) navMarkers.setOnClickListener(v -> switchToView(subMarkers, "Markers"));
        if (navSettings != null) navSettings.setOnClickListener(v -> switchToView(subParm, "Settings"));
        if (btnBack     != null) btnBack.setOnClickListener(v -> switchToDashboard());

        // Minimize / hide panel button
        ImageView btnMinimize = templateView.findViewById(R.id.btn_minimize);
        if (btnMinimize != null) {
            btnMinimize.setOnClickListener(v -> closeDropDown());
        }

        // Overflow menu
        setupOverflowMenu();

        // ── Sprint 13: Dashboard state views ─────────────────────────────────
        // Dashboard state views now managed by DashboardCoordinator (Sprint 27)
        // errorState/errorMessage managed by DashboardCoordinator (Sprint 27)

        // Retry button
        Button btnRetry = templateView.findViewById(R.id.btn_retry);
        if (btnRetry != null) {
            btnRetry.setOnClickListener(v -> {
                hideErrorState();
                triggerAutoLoad();
            });
        }

        // Check online status for offline badge
        updateOfflineBadge();

        switchToDashboard();
    }

    /**
     * Switch to a specific sub-view from the topbar navigation (Sprint 15).
     * Hides dashboard + all sub-views, shows the target, updates topbar state.
     */
    private void switchToView(View targetView, String title) {
        // Hide dashboard
        if (dashboardPanel != null) dashboardPanel.setVisibility(View.GONE);
        // Hide all sub-views
        if (subWeather  != null) subWeather.setVisibility(View.GONE);
        if (subWind     != null) subWind.setVisibility(View.GONE);
        if (subParm     != null) subParm.setVisibility(View.GONE);
        if (subOverlays != null) subOverlays.setVisibility(View.GONE);
        if (subMarkers  != null) subMarkers.setVisibility(View.GONE);
        // Show target
        if (targetView != null) targetView.setVisibility(View.VISIBLE);
        currentSubView = targetView;
        // Update topbar
        if (topbarTitle != null) topbarTitle.setText(title);
        if (btnBack != null) btnBack.setVisibility(View.VISIBLE);
        // Update active nav icon
        updateNavHighlight(targetView);
    }

    /**
     * Return to the dashboard view from any sub-view (Sprint 15).
     */
    private void switchToDashboard() {
        // Hide all sub-views
        if (subWeather  != null) subWeather.setVisibility(View.GONE);
        if (subWind     != null) subWind.setVisibility(View.GONE);
        if (subParm     != null) subParm.setVisibility(View.GONE);
        if (subOverlays != null) subOverlays.setVisibility(View.GONE);
        if (subMarkers  != null) subMarkers.setVisibility(View.GONE);
        // Show dashboard
        if (dashboardPanel != null) dashboardPanel.setVisibility(View.VISIBLE);
        currentSubView = null;
        // Update topbar
        if (topbarTitle != null) topbarTitle.setText("WeatherTool");
        if (btnBack != null) btnBack.setVisibility(View.GONE);
        updateNavHighlight(null);
    }

    /**
     * Update topbar nav icon alpha to highlight the active view (Sprint 15).
     */
    private void updateNavHighlight(View activeView) {
        float inactive = 0.5f, active = 1.0f;
        if (navWeather  != null) navWeather.setAlpha(activeView == subWeather ? active : inactive);
        if (navWind     != null) navWind.setAlpha(activeView == subWind ? active : inactive);
        if (navOverlays != null) navOverlays.setAlpha(activeView == subOverlays ? active : inactive);
        if (navMarkers  != null) navMarkers.setAlpha(activeView == subMarkers ? active : inactive);
        if (navSettings != null) navSettings.setAlpha(activeView == subParm ? active : inactive);
    }



    private void initViewHelpers() {
        currentWeatherView = new CurrentWeatherView(templateView, pluginContext);
        dailyForecastView  = new DailyForecastView(templateView);

        // Weather tab has duplicate IDs — create separate bindings scoped to its root
        if (subWeather != null) {
            weatherTabView      = new CurrentWeatherView(subWeather, pluginContext);
            weatherTabDailyView = new DailyForecastView(subWeather);
        }
        windProfileView    = new WindProfileView(templateView);

        // ── Sprint 21: Extracted coordinators ─────────────────────────────────
        dashboardCoordinator = new DashboardCoordinator(templateView, appContext);
        chartCoordinator     = new ChartCoordinator(templateView);
        settingsCoordinator  = new SettingsCoordinator(
                templateView, pluginContext, appContext, getMapView());
        settingsCoordinator.setAutoRefreshManager(autoRefreshManager);
        settingsCoordinator.setMissionPrepManager(missionPrepManager);

        // ── Tab coordinators ──────────────────────────────────────────────────
        // sharedWindEffectShape is injected from WeatherMapComponent — the same
        // instance used by WindHudWidget so both draw into the same overlay group.
        // RadarTabCoordinator removed — Sprint 28. Radar controls in OverlayTabCoordinator.
        windTabCoordinator  = new WindTabCoordinator(
                getMapView(), templateView, pluginContext,
                windViewModel, windMarkerManager, sharedWindEffectShape,
                windProfileView);

        // ── Sprint 16: Overlay tab coordinator ───────────────────────────
        View overlayRoot = templateView.findViewById(R.id.subTabWidget5);
        if (overlayRoot != null) {
            overlayTabCoordinator = new OverlayTabCoordinator(
                    overlayRoot, pluginContext, getMapView());
            if (radarManager     != null) overlayTabCoordinator.setRadarManager(radarManager);
            if (heatmapManager   != null) overlayTabCoordinator.setHeatmapManager(heatmapManager);
            if (sigmetManager    != null) overlayTabCoordinator.setSigmetManager(sigmetManager);
            if (lightningManager != null) overlayTabCoordinator.setLightningManager(lightningManager);
            if (cbrnManager      != null) overlayTabCoordinator.setCbrnManager(cbrnManager);
            if (heatmapLegendWidget != null) overlayTabCoordinator.setHeatmapLegendWidget(heatmapLegendWidget);
            if (windArrowOverlay    != null) overlayTabCoordinator.setWindArrowOverlay(windArrowOverlay);
            if (windParticleLayer  != null) overlayTabCoordinator.setWindParticleLayer(windParticleLayer);
            if (windParticleView   != null) overlayTabCoordinator.setWindParticleView(windParticleView);
        }

        // ── Sprint 17: Marker tab coordinator ────────────────────────────
        View markerRoot = templateView.findViewById(R.id.subTabWidget6);
        if (markerRoot != null) {
            markerTabCoordinator = new MarkerTabCoordinator(
                    markerRoot, pluginContext, getMapView());
            markerTabCoordinator.setWeatherMarkerManager(markerManager);
            markerTabCoordinator.setWindMarkerManager(windMarkerManager);
            markerTabCoordinator.setRouteWeatherCallback(
                    new MarkerTabCoordinator.RouteWeatherCallback() {
                @Override public void onSelectRoute() {}
                @Override public void onFetchRouteWeather() {}
                @Override public void onFetchWeatherAtPoints(
                        java.util.List<com.atakmap.coremap.maps.coords.GeoPoint> waypoints,
                        String routeName) {
                    fetchWeatherAlongRoute(waypoints, routeName);
                }
            });
        }

        // ── Refresh buttons ──────────────────────────────────────────────────
        // Dashboard and Weather tab each have an imageButton with the same ID.
        // templateView.findViewById returns the dashboard's (first match).
        // subWeather.findViewById returns the Weather tab's copy.
        View refreshBtn = templateView.findViewById(R.id.imageButton);
        View.OnClickListener onRefreshClick = v -> {
            double lat = getMapView().getCenterPoint().get().getLatitude();
            double lon = getMapView().getCenterPoint().get().getLongitude();
            weatherViewModel.loadWeather(lat, lon, LocationSource.MAP_CENTRE);
            Toast.makeText(pluginContext, R.string.loading_map_centre, Toast.LENGTH_SHORT).show();
        };
        View.OnLongClickListener onRefreshLong = v -> {
            double lat = getMapView().getSelfMarker().getPoint().getLatitude();
            double lon = getMapView().getSelfMarker().getPoint().getLongitude();
            if (lat == 0 && lon == 0) {
                Toast.makeText(pluginContext, R.string.no_gps_fix, Toast.LENGTH_SHORT).show();
                return true;
            }
            weatherViewModel.loadWeather(lat, lon, LocationSource.SELF_MARKER);
            Toast.makeText(pluginContext, R.string.loading_self_marker, Toast.LENGTH_SHORT).show();
            return true;
        };
        refreshBtn.setOnClickListener(onRefreshClick);
        refreshBtn.setOnLongClickListener(onRefreshLong);

        // Wire the Weather tab's own refresh button (same ID, different parent)
        if (subWeather != null) {
            View weatherTabRefresh = subWeather.findViewById(R.id.imageButton);
            if (weatherTabRefresh != null && weatherTabRefresh != refreshBtn) {
                weatherTabRefresh.setOnClickListener(onRefreshClick);
                weatherTabRefresh.setOnLongClickListener(onRefreshLong);
            }
        }

        // Sprint 12 briefing button removed in Sprint 15 — moved to overflow menu

        // ── WindProfileView — Request button ──────────────────────────────────
        windProfileView.setRequestClickListener(v -> {
            closeDropDown();
            WeatherPlaceTool.start(getMapView(), WeatherPlaceTool.Mode.WIND,
                    (pickedPoint, mode) -> {
                        double lat = pickedPoint.getLatitude();
                        double lon = pickedPoint.getLongitude();
                        String srcId = WeatherSourceManager.getInstance(appContext).getActiveSourceId();
                        windViewModel.addSlot(lat, lon, srcId);
                        Intent reopen = new Intent(SHOW_PLUGIN);
                        reopen.putExtra(EXTRA_REQUESTED_TAB, "wind");
                        com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(reopen);
                    });
        });

        windProfileView.setSlotTabListener(new WindProfileView.SlotTabListener() {
            @Override public void onSlotSelected(int slotIndex) {
                windViewModel.setActiveSlot(slotIndex);
            }
            @Override public void onSlotRemoved(int slotIndex) {
                windViewModel.removeSlot(slotIndex);
                windTabCoordinator.clearWindShapes();
            }
        });

        // ── Tab 4 — Parameters ────────────────────────────────────────────────

        // Source Manager (Sprint 8 — S8.5)
        // SourceManagerView needs BOTH contexts (lesson from the v3.1.1 first-cut crash):
        //   pluginContext — LayoutInflater + R.string.*  (resources are in plugin APK)
        //   appContext    — getSharedPreferences         (host has the on-disk data dir)
        View srcMgrRoot = templateView.findViewById(R.id.source_manager_section);
        if (srcMgrRoot != null) {
            SourceManagerView sourceManagerView =
                    new SourceManagerView(srcMgrRoot, pluginContext, appContext);
            sourceManagerView.init();
        }

        parametersView = new ParametersView(templateView, pluginContext, paramPrefs);
        WeatherSourceManager parmSrcMgr = WeatherSourceManager.getInstance(appContext);
        if (parmSrcMgr.getActiveSource() != null)
            rebuildParmsForSource(parmSrcMgr.getActiveSourceId());
        parametersView.setOnChangeListener(() -> {
            Toast.makeText(pluginContext, R.string.params_reloading, Toast.LENGTH_SHORT).show();
            triggerAutoLoad();
        });

        // Refresh-sources button (PARM tab)
        Button btnRefreshSources = templateView.findViewById(R.id.btn_refresh_sources);
        if (btnRefreshSources != null) {
            btnRefreshSources.setOnClickListener(v -> {
                SourceDefinitionLoader.clearCache();
                Map<String, WeatherSourceDefinition> allDefs =
                        SourceDefinitionLoader.loadAll(pluginContext);
                rebuildParmsForSource(WeatherSourceManager.getInstance(appContext).getActiveSourceId());
                Toast.makeText(pluginContext,
                        pluginContext.getString(R.string.sources_refreshed, allDefs.size()),
                        Toast.LENGTH_SHORT).show();
            });
        }

        // ── Tab 1 — Chart ─────────────────────────────────────────────────────
        FrameLayout chartFrame = templateView.findViewById(R.id.chart_frame);
        if (chartFrame != null) {
            chartView = new WeatherChartView(pluginContext);
            chartFrame.addView(chartView, 0);
            // Chart controls delegated to ChartCoordinator (Sprint 21)
            chartOverlaySeekBar = templateView.findViewById(R.id.seekbar_chart_overlay);
            if (chartCoordinator != null) {
                chartCoordinator.init(chartView, chartOverlaySeekBar);
            }
        }
        chartOverlaySeekBar = templateView.findViewById(R.id.seekbar_chart_overlay);
        if (chartOverlaySeekBar != null) {
            chartOverlaySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    if (fromUser) weatherViewModel.selectHour(p);
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
        }

        // ── PARM tab spinners ─────────────────────────────────────────────────
        wireParmSourceSpinner();

        // ── Tab 3 — Map marker controls ───────────────────────────────────────
        initMapTab();

        // Tab 6 (Comparison) retired — section removed from UI layout

        // ── WIND tab: HUD toggle button ───────────────────────────────────────
        // The layout button id "btn_toggle_wind_hud" may not yet exist in the XML.
        // We look it up at runtime via Resources.getIdentifier() so the build
        // never fails on a missing R.id constant. Wire it up if the layout has it.
        int hudBtnId = pluginContext.getResources().getIdentifier(
                "btn_toggle_wind_hud", "id", pluginContext.getPackageName());
        if (hudBtnId != 0) {
            Button btnToggleHud = templateView.findViewById(hudBtnId);
            if (btnToggleHud != null) {
                btnToggleHud.setText(hudVisible ? "Hide Wind HUD" : "Show Wind HUD");
                btnToggleHud.setOnClickListener(v -> {
                    hudVisible = !hudVisible;
                    Intent hudIntent = new Intent("com.atakmap.android.weather.TOGGLE_WIND_HUD");
                    hudIntent.putExtra("visible", hudVisible);
                    com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(hudIntent);
                    btnToggleHud.setText(hudVisible ? "Hide Wind HUD" : "Show Wind HUD");
                });
            }
        }

        // ── Sprint 13: Auto-Refresh setup ────────────────────────────────────
        autoRefreshManager = new AutoRefreshManager(new Handler(Looper.getMainLooper()));
        SharedPreferences wxPrefs = appContext.getSharedPreferences("WeatherToolPrefs", Context.MODE_PRIVATE);
        int savedInterval = wxPrefs.getInt(AutoRefreshManager.PREF_KEY, 0);
        autoRefreshManager.setInterval(savedInterval);
        autoRefreshManager.start(() -> {
            com.atakmap.coremap.log.Log.d(TAG, "Auto-refresh triggered");
            triggerAutoLoad();
        });

        // ── Sprint 13: Mission Prep setup ────────────────────────────────────
        missionPrepManager = new MissionPrepManager(appContext, cachingRepo);

        // ── Sprint 21: All Settings tab wiring delegated to SettingsCoordinator ──
        if (settingsCoordinator != null) {
            settingsCoordinator.init();
        }
    }

    // ── Map tab ───────────────────────────────────────────────────────────────

    /**
     * Wire the marker buttons that live in {@code tab_markers.xml}.
     *
     * <p>This used to also wire a "pick a point and drop a marker" button from
     * {@code tab_config.xml}. That layout was retired and its controls moved to
     * the Markers tab, where {@code MarkerTabCoordinator} owns the live
     * point-picking. The wiring here kept compiling because
     * {@code findViewById} on an absent id returns null rather than failing, so
     * the whole path was a no-op behind a null check. Removed with the layout.
     */
    private void initMapTab() {
        Button btnShareMarker = templateView.findViewById(R.id.btn_share_marker);
        Button btnRemoveAll   = templateView.findViewById(R.id.btn_remove_all_markers);

        if (btnShareMarker != null) {
            btnShareMarker.setOnClickListener(v -> {
                if (lastLocation == null) {
                    Toast.makeText(pluginContext, R.string.map_marker_no_data, Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent shareIntent = new Intent(SHARE_MARKER);
                shareIntent.putExtra(EXTRA_TARGET_UID, WeatherUiUtils.buildMarkerUid(lastLocation));
                com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(shareIntent);
            });
        }

        if (btnRemoveAll != null) {
            btnRemoveAll.setOnClickListener(v -> markerManager.removeAllMarkers());
        }
    }

    // ── LiveData observers ────────────────────────────────────────────────────
    // All registrations go through the WeatherObserverRegistry — no typed fields.
    // Cleanup: observers.removeAll() in disposeImpl().

    @SuppressLint("SetTextI18n")
    private void observeViewModels() {

        observers.add(weatherViewModel.getCurrentWeather(), state -> {
            if (state.isLoading()) {
                currentWeatherView.showLoading();
                if (weatherTabView != null) weatherTabView.showLoading();
                showLoadingState();   // Sprint 13: dashboard loading indicator
            } else if (state.isSuccess() && state.getData() != null) {
                WeatherModel w = state.getData();
                lastWeather = w;
                windTabCoordinator.setLastWeather(w);
                if (markerTabCoordinator != null) markerTabCoordinator.setLastWeather(w);
                if (overlayTabCoordinator != null) overlayTabCoordinator.setLastWeather(w);
                currentWeatherView.bindCurrentWeather(w, w.getRequestTimestamp());
                if (weatherTabView != null) weatherTabView.bindCurrentWeather(w, w.getRequestTimestamp());
                updateFltCatBadge(w);
                if (lastLocation != null) updateChartLocationHeader(lastLocation);

                // Sprint 12 (S12.2): Record snapshot for historical comparison
                if (forecastRecorder != null) {
                    String srcId = WeatherSourceManager.getInstance(appContext).getActiveSourceId();
                    forecastRecorder.recordSnapshot(w, srcId, w.getLatitude(), w.getLongitude());
                }

                // Sprint 13: Update staleness badge + hide loading/error
                lastUpdateMs = System.currentTimeMillis();
                updateStalenesssBadge();
                hideLoadingState();
                hideErrorState();
                updateOfflineBadge();

            } else if (state.isError()) {
                currentWeatherView.showError(state.getErrorMessage());
                if (weatherTabView != null) weatherTabView.showError(state.getErrorMessage());
                showErrorState(state.getErrorMessage());  // Sprint 13: error + retry
            }
        });

        observers.add(weatherViewModel.getActiveLocation(), snapshot -> {
            if (snapshot != null) {
                lastLocation = snapshot;
                currentWeatherView.bindLocation(snapshot);
                if (weatherTabView != null) weatherTabView.bindLocation(snapshot);
                updateChartLocationHeader(snapshot);
            }
        });

        observers.add(weatherViewModel.getDailyForecast(), state -> {
            if (state.isSuccess() && state.getData() != null) {
                dailyCache = state.getData();
                dailyForecastView.bind(state.getData());
                if (weatherTabDailyView != null) weatherTabDailyView.bind(state.getData());
            }
        });

        observers.add(weatherViewModel.getHourlyForecast(), state -> {
            TextView chartStatus = templateView.findViewById(R.id.chart_status_text);
            if (state.isLoading()) {
                if (chartStatus != null) {
                    chartStatus.setText("Loading forecast data…");
                    chartStatus.setVisibility(View.VISIBLE);
                }
            } else if (state.isSuccess() && state.getData() != null) {
                hourlyCache = state.getData();
                if (chartOverlaySeekBar != null) {
                    chartOverlaySeekBar.setMax(hourlyCache.size() - 1);
                    chartOverlaySeekBar.setProgress(0);
                }
                if (chartView != null) { chartView.setData(hourlyCache); chartView.invalidate(); }
                if (windTabCoordinator != null) windTabCoordinator.setHourlyCache(hourlyCache);
                if (overlayTabCoordinator != null) overlayTabCoordinator.setHourlyCache(hourlyCache);
                if (chartStatus != null) chartStatus.setVisibility(View.GONE);
            } else if (state.isError()) {
                if (chartStatus != null) {
                    chartStatus.setText("Chart: " + state.getErrorMessage());
                    chartStatus.setVisibility(View.VISIBLE);
                }
            }
        });

        observers.add(weatherViewModel.getSelectedHour(), index -> {
            if (index == null) return;
            if (chartView != null) { chartView.setSelectedIndex(index); updateChartReadouts(index); }
            if (hourlyCache != null && index >= 0 && index < hourlyCache.size()) {
                HourlyEntryModel entry = hourlyCache.get(index);
                String iso     = entry.getIsoTime();
                String dayName = WeatherUiUtils.isoDayOfWeek(iso);
                String label   = "+" + index + "h  " + dayName + "  " + iso.replace("T", " ");
                currentWeatherView.bindHourlyEntry(entry, label);
                if (weatherTabView != null) weatherTabView.bindHourlyEntry(entry, label);
                TextView tsLabel = templateView.findViewById(R.id.chart_timestamp_label);
                if (tsLabel != null) tsLabel.setText(dayName + "  " + iso.replace("T", " "));
            }
        });

        observers.add(weatherViewModel.getErrorMessage(), msg -> {
            if (msg != null) Toast.makeText(pluginContext, msg, Toast.LENGTH_SHORT).show();
        });

        observers.add(weatherViewModel.getCacheBadge(), badge -> {
            TextView badgeView = templateView.findViewById(R.id.textview_cache_badge);
            if (badgeView == null) return;
            if (badge == null || badge.isEmpty()) {
                badgeView.setVisibility(View.GONE);
            } else {
                badgeView.setText(badge);
                badgeView.setVisibility(View.VISIBLE);
            }
        });

        observers.add(windViewModel.getWindProfile(), state -> {
            if (state.isLoading()) {
                windProfileView.showLoading();
            } else if (state.isSuccess() && state.getData() != null) {
                windCache = state.getData();
                windTabCoordinator.onWindProfilesUpdated(state.getData());
                if (overlayTabCoordinator != null) overlayTabCoordinator.setLastWindProfiles(state.getData());
                windProfileView.bind(state.getData());
            } else if (state.isError()) {
                windProfileView.showError(state.getErrorMessage());
            }
        });

        observers.add(windViewModel.getSlots(), slots -> {
            int activeIdx = windViewModel.getActiveSlotIndex();
            windProfileView.rebuildSlotTabs(slots, activeIdx);
            windTabCoordinator.onSlotsChanged(slots);

            // Re-bind chart altitudes when active slot profiles change
            if (slots != null && activeIdx >= 0 && activeIdx < slots.size()) {
                WindProfileViewModel.WindSlot activeSlot = slots.get(activeIdx);
                if (activeSlot.profiles != null && !activeSlot.profiles.isEmpty()) {
                    boolean slotSwitched    = (activeIdx != lastActiveSlotIdx);
                    boolean profilesChanged = (activeSlot.profiles != null);
                    boolean sourceChanged   = !activeSlot.getSourceId().equals(
                            lastBoundSourceId != null ? lastBoundSourceId : "");
                    if (profilesChanged || slotSwitched || sourceChanged) {
                        lastActiveSlotIdx = activeIdx;
                        lastBoundSourceId = activeSlot.getSourceId();
                        rebindWindChart(activeSlot);
                    }
                }
            }
        });

        observers.add(windViewModel.getActiveSlot(), activeIdx -> {
            windProfileView.rebuildSlotTabs(windViewModel.getSlotList(), activeIdx);
            if (activeIdx == null || activeIdx < 0) return;
            windTabCoordinator.onActiveSlotChanged(activeIdx);
            List<WindProfileViewModel.WindSlot> slots = windViewModel.getSlotList();
            if (activeIdx < slots.size()) {
                WindProfileViewModel.WindSlot slot = slots.get(activeIdx);
                lastActiveSlotIdx = activeIdx;
                lastBoundSourceId = slot.getSourceId();
                if (slot.profiles != null) {
                    windTabCoordinator.onWindProfilesUpdated(slot.profiles);
                    rebindWindChart(slot);
                }
            }
        });

        // Comparison observers removed — section retired from UI layout
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void rebindWindChart(WindProfileViewModel.WindSlot slot) {
        WindChartView wc = windProfileView != null ? windProfileView.getWindChart() : null;
        if (wc == null) return;
        wc.setAltitudesFromProfiles(slot.profiles);
        String srcDisplay = slot.getSourceId();
        IWeatherRemoteSource srcObj = WeatherSourceManager.getInstance(appContext)
                .getSourceById(slot.getSourceId());
        if (srcObj != null) srcDisplay = srcObj.getDisplayName();
        String tierStr = WeatherUiUtils.buildAltitudeTierLabel(slot.profiles);
        wc.setSourceLabel(tierStr.isEmpty() ? srcDisplay : srcDisplay + "  " + tierStr);
        windProfileView.bind(slot.profiles);

        // Rebuild altitude visibility toggles for the new profiles
        if (windTabCoordinator != null) {
            windTabCoordinator.rebuildAltitudeToggles(slot.profiles);
        }
    }

    // ── Route weather fetch ─────────────────────────────────────────────────

    /**
     * Fetch weather at each waypoint along a route using the active source.
     * Results are displayed in the Markers tab's route weather list.
     */
    private void fetchWeatherAlongRoute(
            java.util.List<com.atakmap.coremap.maps.coords.GeoPoint> waypoints,
            String routeName) {
        if (waypoints == null || waypoints.isEmpty()) return;

        String srcId = com.atakmap.android.weather.data.remote.WeatherSourceManager
                .getInstance(appContext).getActiveSourceId();

        java.util.List<String> results = java.util.Collections.synchronizedList(
                new java.util.ArrayList<>());
        // Pre-fill with "fetching..." placeholders
        for (int i = 0; i < waypoints.size(); i++) {
            results.add(String.format(java.util.Locale.US,
                    "WP%d: %.3f\u00b0N %.3f\u00b0E  fetching...",
                    i + 1, waypoints.get(i).getLatitude(),
                    waypoints.get(i).getLongitude()));
        }
        if (markerTabCoordinator != null) {
            markerTabCoordinator.setRouteWeatherResults(results);
        }

        // Chart data accumulator (thread-safe)
        final com.atakmap.android.weather.presentation.view.RouteWeatherChartView.WaypointData[]
                chartData = new com.atakmap.android.weather.presentation.view.RouteWeatherChartView.WaypointData[waypoints.size()];
        final java.util.concurrent.atomic.AtomicInteger completedCount =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final int totalWaypoints = waypoints.size();

        // Fetch each waypoint
        for (int i = 0; i < totalWaypoints; i++) {
            final int idx = i;
            final com.atakmap.coremap.maps.coords.GeoPoint wp = waypoints.get(i);

            weatherViewModel.loadWeatherForPoint(
                    wp.getLatitude(), wp.getLongitude(),
                    new com.atakmap.android.weather.presentation.viewmodel.WeatherViewModel.PointWeatherCallback() {
                        @Override
                        public void onResult(WeatherModel weather) {
                            String line;
                            String wmoLabel = "";
                            if (weather != null) {
                                try {
                                    WmoCodeMapper.WmoInfo wmo = WmoCodeMapper.resolve(
                                            weather.getWeatherCode(),
                                            wp.getLatitude(), wp.getLongitude());
                                    wmoLabel = pluginContext.getString(wmo.labelResId);
                                } catch (Exception ignored) {}

                                String cardinal = com.atakmap.android.weather.util.WeatherUnitConverter
                                        .degreesToCardinal(weather.getWindDirection());
                                line = String.format(java.util.Locale.US,
                                        "WP%d: %s  \uD83D\uDCA8%s %s  \uD83D\uDCA7%.0f%%  %s",
                                        idx + 1,
                                        com.atakmap.android.weather.util.WeatherUnitConverter.fmtTempRange(
                                                weather.getTemperatureMin(), weather.getTemperatureMax()),
                                        com.atakmap.android.weather.util.WeatherUnitConverter.fmtWind(
                                                weather.getWindSpeed()),
                                        cardinal,
                                        weather.getHumidity(),
                                        wmoLabel);

                                // Build chart data point
                                chartData[idx] = new com.atakmap.android.weather.presentation.view
                                        .RouteWeatherChartView.WaypointData(
                                        idx + 1,
                                        weather.getWindSpeed(),
                                        weather.getWindDirection(),
                                        weather.getHumidity(),
                                        weather.getTemperatureMax(),
                                        wmoLabel);
                            } else {
                                line = String.format(java.util.Locale.US,
                                        "WP%d: %.3f\u00b0N %.3f\u00b0E  \u26A0 fetch failed",
                                        idx + 1, wp.getLatitude(), wp.getLongitude());
                            }
                            results.set(idx, line);

                            int done = completedCount.incrementAndGet();

                            // Update the list + chart on the main thread
                            getMapView().post(() -> {
                                if (markerTabCoordinator != null) {
                                    markerTabCoordinator.setRouteWeatherResults(
                                            new java.util.ArrayList<>(results));

                                    // Update chart when all done (or progressively)
                                    if (done == totalWaypoints || done % 3 == 0) {
                                        java.util.List<com.atakmap.android.weather.presentation.view
                                                .RouteWeatherChartView.WaypointData> chartList =
                                                new java.util.ArrayList<>();
                                        for (com.atakmap.android.weather.presentation.view
                                                .RouteWeatherChartView.WaypointData cd : chartData) {
                                            if (cd != null) chartList.add(cd);
                                        }
                                        markerTabCoordinator.setRouteWeatherChartData(chartList);
                                    }
                                }
                            });
                        }
                    });
        }
    }

    // ── Unit preference change listener ──────────────────────────────────────

    /**
     * Register a SharedPreferences listener to refresh all displayed views
     * when unit preferences change. This avoids the need to re-fetch data —
     * all values are stored in SI units and converted at display time.
     */
    private android.content.SharedPreferences.OnSharedPreferenceChangeListener unitPrefListener;

    private void registerUnitPrefListener() {
        android.content.SharedPreferences prefs =
                android.preference.PreferenceManager.getDefaultSharedPreferences(appContext);
        unitPrefListener = (sp, key) -> {
            if (key != null && (key.startsWith("weather_unit_") || key.equals("weather_unit_system"))) {
                // Reload unit settings
                com.atakmap.android.weather.infrastructure.preferences.WeatherPreferenceFragment
                        .loadSavedUnitSystem(appContext);
                // Re-bind all views with cached data using new units
                refreshAllDisplaysForUnitChange();
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(unitPrefListener);
    }

    private void unregisterUnitPrefListener() {
        if (unitPrefListener != null) {
            android.preference.PreferenceManager.getDefaultSharedPreferences(appContext)
                    .unregisterOnSharedPreferenceChangeListener(unitPrefListener);
            unitPrefListener = null;
        }
    }

    /**
     * Re-bind all views using cached data after unit preferences change.
     * No network fetch needed — values are stored in SI, converted at display time.
     */
    private void refreshAllDisplaysForUnitChange() {
        // Dashboard + Weather tab: re-bind current weather
        if (lastWeather != null) {
            currentWeatherView.bindCurrentWeather(lastWeather, lastWeather.getRequestTimestamp());
            if (weatherTabView != null)
                weatherTabView.bindCurrentWeather(lastWeather, lastWeather.getRequestTimestamp());
        }
        // Daily forecast
        if (dailyCache != null) {
            dailyForecastView.bind(dailyCache);
            if (weatherTabDailyView != null) weatherTabDailyView.bind(dailyCache);
        }
        // Hourly chart
        if (hourlyCache != null && chartView != null) {
            chartView.invalidate(); // chart reads from cached data, just redraw
        }
        // Wind profile text table
        if (windProfileView != null && windCache != null) {
            windProfileView.bind(windCache);
        }
    }

    // ── Data triggers ─────────────────────────────────────────────────────────

    private void triggerAutoLoad() {
        double cenLat = getMapView().getCenterPoint().get().getLatitude();
        double cenLon = getMapView().getCenterPoint().get().getLongitude();
        double selfLat = 0.0, selfLon = 0.0;
        try {
            if (getMapView().getSelfMarker() != null) {
                selfLat = getMapView().getSelfMarker().getPoint().getLatitude();
                selfLon = getMapView().getSelfMarker().getPoint().getLongitude();
            }
        } catch (Exception e) {
            com.atakmap.coremap.log.Log.w(TAG, "getSelfMarker() threw: " + e.getMessage());
        }
        if (selfLat == 0.0 && selfLon == 0.0)
            Toast.makeText(pluginContext, R.string.no_gps_using_map_centre, Toast.LENGTH_SHORT).show();
        weatherViewModel.loadWeatherWithFallback(selfLat, selfLon, cenLat, cenLon);
    }

    // triggerComparison() removed — comparison section retired from UI layout

    // ── PARM tab source spinner ───────────────────────────────────────────────

    private void wireParmSourceSpinner() {
        Spinner spinner = templateView.findViewById(R.id.spinner_parm_source);
        if (spinner == null) return;

        WeatherSourceManager mgr     = WeatherSourceManager.getInstance(appContext);
        List<WeatherSourceManager.SourceEntry> entries = mgr.getAvailableEntries();

        spinner.setAdapter(WeatherUiUtils.makeDarkSpinnerAdapter(appContext, entries));
        WeatherUiUtils.styleSpinnerDark(spinner);
        spinner.setSelection(mgr.getActiveSourceIndex(), false);

        fltCatBadge = templateView.findViewById(R.id.badge_parm_flt_cat);

        TextView statusLabel = templateView.findViewById(R.id.textview_parm_source_status);
        if (statusLabel != null && mgr.getActiveSource() != null)
            updateSourceStatusLabel(statusLabel, mgr.getActiveSource());

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                WeatherSourceManager.SourceEntry entry = entries.get(pos);
                mgr.setActiveSourceId(entry.sourceId);
                if (networkRepo != null) networkRepo.setActiveSource(entry.sourceId);
                if (cachingRepo  != null) cachingRepo.clearWindCache();
                if (statusLabel  != null) updateSourceStatusLabel(statusLabel, mgr.getActiveSource());
                rebuildParmsForSource(entry.sourceId);
                if (fltCatBadge != null) fltCatBadge.setVisibility(View.GONE);
                Toast.makeText(pluginContext,
                        "Source: " + entry.displayName + " — parameters updated",
                        Toast.LENGTH_SHORT).show();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void rebuildParmsForSource(String sourceId) {
        if (parametersView == null) return;
        TextView descLabel = templateView.findViewById(R.id.textview_parm_source_desc);
        WeatherSourceDefinition def = SourceDefinitionLoader.loadAll(pluginContext).get(sourceId);
        if (def != null && !def.hourlyParams.isEmpty()) {
            parametersView.setDefinitionParams(sourceId, def.hourlyParams, def.dailyParams, def.currentParams);
            if (descLabel != null) {
                if (def.description != null && !def.description.isEmpty()) {
                    descLabel.setText(def.description);
                    descLabel.setVisibility(View.VISIBLE);
                } else {
                    descLabel.setVisibility(View.GONE);
                }
            }
        } else {
            IWeatherRemoteSource src = WeatherSourceManager.getInstance(appContext).getSourceById(sourceId);
            if (src != null) parametersView.setAvailableParameters(src.getSupportedParameters());
            if (descLabel != null) descLabel.setVisibility(View.GONE);
        }
    }

    private void updateSourceStatusLabel(TextView tv, IWeatherRemoteSource src) {
        if (tv == null || src == null) return;
        String label = src.getDisplayName()
                + "  |  " + src.getSupportedParameters().size() + " parameters";

        // A source that delegates part of its interface to another provider has
        // to say so here — this label is the only place the user sees what the
        // selected source actually covers. Finding F21.
        String notice = src.getProviderNotice();
        if (notice != null && !notice.isEmpty()) {
            label += "\n\u26A0 " + notice;
        }
        tv.setText(label);
    }

    // ── Chart helpers ─────────────────────────────────────────────────────────

    // wireChartToggleButtons — delegated to ChartCoordinator (Sprint 21)
    private void wireChartToggleButtons() {
        // Now handled by chartCoordinator.init()
    }


    // ── Sprint 13: Dashboard staleness badge ─────────────────────────────────

    // ── Delegated to DashboardCoordinator (Sprint 21) ────────────────────────

    private void updateStalenesssBadge() {
        if (dashboardCoordinator != null) {
            dashboardCoordinator.setLastUpdateMs(lastUpdateMs);
            dashboardCoordinator.updateStalenesssBadge();
        }
    }

    private void updateOfflineBadge() {
        if (dashboardCoordinator != null) dashboardCoordinator.updateOfflineBadge();
    }

    private void showLoadingState() {
        if (dashboardCoordinator != null) dashboardCoordinator.showLoadingState();
    }

    private void hideLoadingState() {
        if (dashboardCoordinator != null) dashboardCoordinator.hideLoadingState();
    }

    private void showErrorState(String msg) {
        if (dashboardCoordinator != null) dashboardCoordinator.showErrorState(msg);
    }

    private void hideErrorState() {
        if (dashboardCoordinator != null) dashboardCoordinator.hideErrorState();
    }

    // ── Delegation wrappers for methods deleted by sed but still called ────────

    private void updateFltCatBadge(WeatherModel w) {
        if (dashboardCoordinator != null) dashboardCoordinator.updateFltCatBadge(w);
    }

    private void updateChartLocationHeader(LocationSnapshot snapshot) {
        if (chartCoordinator != null) chartCoordinator.updateChartLocationHeader(snapshot);
    }

    private void updateChartReadouts(int index) {
        if (chartCoordinator != null) chartCoordinator.updateChartReadouts(index);
    }

    // ── Methods accidentally deleted by sed — restored ────────────────────────

    private void setupOverflowMenu() {
        if (btnOverflow == null) return;
        btnOverflow.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(getMapView().getContext(), btnOverflow);
            popup.getMenu().add(0, 1, 0, "Generate Briefing");
            popup.getMenu().add(0, 2, 1, "Export CSV");
            popup.getMenu().add(0, 3, 2, "About");
            popup.setOnMenuItemClickListener(item -> {
                switch (item.getItemId()) {
                    case 1: generateBriefing(); return true;
                    case 2: Toast.makeText(pluginContext, "CSV export — coming soon", Toast.LENGTH_SHORT).show(); return true;
                    case 3: Toast.makeText(pluginContext, "WeatherTool ATAK Plugin", Toast.LENGTH_SHORT).show(); return true;
                }
                return false;
            });
            popup.show();
        });
    }

    private void generateBriefing() {
        if (lastWeather == null) {
            Toast.makeText(pluginContext, "No weather data — fetch first", Toast.LENGTH_SHORT).show();
            return;
        }
        String locName = lastLocation != null ? lastLocation.getDisplayName() : "Unknown";
        String srcName = WeatherSourceManager.getInstance(appContext).getActiveSourceId();
        BriefingDocument doc = BriefingGenerator.generate(
                lastWeather, dailyCache, hourlyCache, windCache, locName, srcName);
        new android.app.AlertDialog.Builder(getMapView().getContext())
                .setTitle("Weather Briefing")
                .setMessage(doc.getPlainText())
                .setPositiveButton("OK", null)
                .setNeutralButton("Copy", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            appContext.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(
                            android.content.ClipData.newPlainText("Weather Briefing", doc.getPlainText()));
                    Toast.makeText(pluginContext, "Copied to clipboard", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void handleMarkerDetails(String targetUid, String requestTab) {
        if (targetUid == null) return;
        MapGroup weatherGroup = getMapView().getRootGroup().findMapGroup("Weather Markers");
        if (weatherGroup == null) return;
        MapItem item = weatherGroup.deepFindUID(targetUid);
        if (item == null || !(item instanceof com.atakmap.android.maps.PointMapItem)) return;
        com.atakmap.android.maps.PointMapItem pmi = (com.atakmap.android.maps.PointMapItem) item;
        double lat = pmi.getPoint().getLatitude();
        double lon = pmi.getPoint().getLongitude();
        weatherViewModel.loadWeather(lat, lon, LocationSource.MAP_CENTRE);
        showDropDown(templateView, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT);
        if ("wind".equals(requestTab) && windViewModel != null) {
            String srcId = WeatherSourceManager.getInstance(appContext).getActiveSourceId();
            windViewModel.addSlot(lat, lon, srcId);
            switchToView(subWind, "Wind");
        }
    }

    private void handleShareMarker(String targetUid) {
        if (targetUid == null) return;
        // Use ATAK's built-in share mechanism
        Intent shareIntent = new Intent("com.atakmap.android.maps.SHARE");
        shareIntent.putExtra("uid", targetUid);
        com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(shareIntent);
    }

    /** Called from WeatherMapComponent when radar overlay active state changes. */
    public void onRadarActiveChanged(boolean isActive) {
        // Update any UI badge if needed
    }

    /** Called from WeatherMapComponent during cleanup. */
    public void clearWindShapes() {
        if (windTabCoordinator != null) windTabCoordinator.clearWindShapes();
    }

    @Override public void disposeImpl() {
        // Cancel any active picker
        WeatherPlaceTool.cancel(getMapView());

        // Unregister unit preference listener
        unregisterUnitPrefListener();

        // Stop auto-refresh (Sprint 13)
        if (autoRefreshManager != null) {
            autoRefreshManager.stop();
            autoRefreshManager = null;
        }

        // Remove all LiveData observers in one call (replaces 14-line removeObservers())
        observers.removeAll();

        // Evict in-memory caches, then drop our aliases. The objects themselves
        // are owned by WeatherDependencies and outlive this receiver (F20), so
        // this clears the shared wind cache but does not tear the graph down.
        if (cachingRepo != null) cachingRepo.clearWindCache();
        cachingRepo = null;
        networkRepo = null;
        paramPrefs  = null;
        fltCatBadge = null;

        // RadarTabCoordinator dispose removed — Sprint 28
        if (windTabCoordinator   != null) { windTabCoordinator.dispose();   windTabCoordinator   = null; }
        if (overlayTabCoordinator != null) { overlayTabCoordinator.dispose(); overlayTabCoordinator = null; }
        if (markerTabCoordinator  != null) { markerTabCoordinator.dispose();  markerTabCoordinator  = null; }

        // Clear Sprint 15 topbar references
        navWeather = null; navWind = null; navOverlays = null;
        navMarkers = null; navSettings = null;
        btnBack = null; btnOverflow = null; topbarTitle = null;
        subOverlays = null; subMarkers = null;

        // Clear Sprint 13 references
        missionPrepManager = null;
        // Dashboard field nulling removed — fields no longer in DDR (Sprint 27)
        // errorMessage nulling removed (Sprint 27)

        initialized = false;
    }

    @Override public void onDropDownSelectionRemoved() {}
    @Override public void onDropDownVisible(boolean v) {}
    @Override public void onDropDownSizeChanged(double w, double h) {}
    @Override public void onDropDownClose() {
        // Keep the picker running when the drop-down closes naturally so the
        // user can tap the map. Do not reset pick mode here.
    }

    @Override
    public boolean onBackButtonPressed() {
        if (currentSubView != null) {
            switchToDashboard();
            return true;  // consumed — stay in dropdown, don't close
        }
        // On dashboard — let DropDownReceiver handle close
        return super.onBackButtonPressed();
    }
}
