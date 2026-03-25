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
import com.atakmap.android.weather.data.cache.WeatherDatabase;
import com.atakmap.android.weather.data.geocoding.NominatimGeocodingSource;
import com.atakmap.android.weather.data.remote.IWeatherRemoteSource;
import com.atakmap.android.weather.data.remote.SourceDefinitionLoader;
import com.atakmap.android.weather.data.remote.WeatherSourceDefinition;
import com.atakmap.android.weather.data.remote.WeatherSourceManager;
import com.atakmap.android.weather.overlay.radar.RadarOverlayManager;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.LocationSnapshot;
import com.atakmap.android.weather.domain.model.LocationSource;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.repository.IGeocodingRepository;
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
import com.atakmap.android.weather.presentation.view.MarkerTabCoordinator;
import com.atakmap.android.weather.presentation.view.OverlayTabCoordinator;
import com.atakmap.android.weather.presentation.view.RadarTabCoordinator;
import com.atakmap.android.weather.presentation.view.WeatherChartView;
import com.atakmap.android.weather.presentation.view.WindProfileView;
import com.atakmap.android.weather.presentation.view.WindChartView;
import com.atakmap.android.weather.presentation.view.WindTabCoordinator;
import com.atakmap.android.weather.presentation.viewmodel.UiState;
import com.atakmap.android.weather.presentation.viewmodel.WeatherObserverRegistry;
import com.atakmap.android.weather.presentation.viewmodel.WeatherViewModel;
import com.atakmap.android.weather.presentation.viewmodel.WindProfileViewModel;
import com.atakmap.android.weather.data.cache.MissionPrepManager;
import com.atakmap.android.weather.presentation.view.CollapsibleSection;
import com.atakmap.android.weather.util.AutoRefreshManager;
import com.atakmap.android.weather.util.MapPointPicker;
import com.atakmap.android.weather.util.ThemeManager;
import com.atakmap.android.weather.util.WeatherPlaceTool;
import com.atakmap.android.weather.util.WeatherUiUtils;
import com.atakmap.android.weather.util.WmoCodeMapper;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.importexport.CotEventFactory;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ATAK drop-down entry point for the WeatherTool plugin.
 *
 * <h3>Refactoring summary (vs original ~1 865-line version)</h3>
 * <ul>
 *   <li><b>DDR decomposition</b> — radar and wind tab initialisation extracted
 *       to {@link RadarTabCoordinator} and {@link WindTabCoordinator}.</li>
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
 *       {@code @Nullable GeoPoint pendingPickPoint}.</li>
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

    // ── ViewModels ────────────────────────────────────────────────────────────
    private WeatherViewModel     weatherViewModel;

    /** Kept so disposeImpl can clear the in-memory wind profile cache. */
    private CachingWeatherRepository cachingRepo;
    /** Kept to apply active-source changes from the PARM spinner. */
    private WeatherRepositoryImpl    networkRepo;

    // ── Observer registry (replaces 14 typed observer fields) ─────────────────
    private final WeatherObserverRegistry observers = new WeatherObserverRegistry();

    // ── Tab coordinators ──────────────────────────────────────────────────────
    private RadarTabCoordinator   radarTabCoordinator;
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
    // ComparisonView field removed — comparison section retired from UI
    private SeekBar            chartOverlaySeekBar;
    private TextView           fltCatBadge;

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
    private GeoPoint pendingPickPoint = null;  // non-null = place marker on next weather success
    private boolean  pickModeActive   = false;
    private Button   btnDropMarker    = null;

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

    // ── Sprint 13: Dashboard state views ─────────────────────────────────────
    private TextView lastUpdatedBadge;
    private TextView offlineBadge;
    private ProgressBar loadingProgress;
    private View errorState;
    private TextView errorMessage;

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
                                   final WeatherMarkerManager markerManager,
                                   final WindMarkerManager windMarkerManager,
                                   final WindProfileViewModel windViewModel,
                                   final WindEffectShape sharedWindEffectShape,
                                   final RadarOverlayManager radarManager) {
        super(mapView);
        this.pluginContext          = context;
        this.appContext             = mapView.getContext();
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
        WeatherSourceManager sourceMgr = WeatherSourceManager.getInstance(appContext);

        Map<String, IWeatherRemoteSource> sources = new HashMap<>();
        for (WeatherSourceManager.SourceEntry entry : sourceMgr.getAvailableEntries()) {
            IWeatherRemoteSource src = sourceMgr.getSourceById(entry.sourceId);
            if (src != null) sources.put(entry.sourceId, src);
        }

        networkRepo = new WeatherRepositoryImpl(sources, sourceMgr.getActiveSourceId());
        IGeocodingRepository geocodingRepo = new NominatimGeocodingSource();

        paramPrefs = new WeatherParameterPreferences(pluginContext);
        networkRepo.setParameterPreferences(paramPrefs);

        cachingRepo = new CachingWeatherRepository(
                networkRepo,
                WeatherDatabase.getInstance(appContext).weatherDao(),
                paramPrefs);
        cachingRepo.purgeExpired();

        weatherViewModel = new WeatherViewModel(cachingRepo, geocodingRepo);
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
        lastUpdatedBadge = templateView.findViewById(R.id.last_updated_badge);
        offlineBadge     = templateView.findViewById(R.id.offline_badge);
        loadingProgress  = templateView.findViewById(R.id.loading_progress);
        errorState       = templateView.findViewById(R.id.error_state);
        errorMessage     = templateView.findViewById(R.id.error_message);

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

        // ── Tab coordinators ──────────────────────────────────────────────────
        // sharedWindEffectShape is injected from WeatherMapComponent — the same
        // instance used by WindHudWidget so both draw into the same overlay group.
        radarTabCoordinator = new RadarTabCoordinator(
                getMapView(), templateView, pluginContext, radarManager);
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
        View srcMgrRoot = templateView.findViewById(R.id.source_manager_section);
        if (srcMgrRoot != null) {
            SourceManagerView sourceManagerView = new SourceManagerView(srcMgrRoot, pluginContext);
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
            wireChartToggleButtons();
            wireChartZoomAndRange();
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

        // ── Sprint 13: Theme detection ───────────────────────────────────────
        ThemeManager.detectAtakTheme(appContext);

        // ── Sprint 13: Collapsible sections ──────────────────────────────────
        initCollapsibleSections();

        // ── Sprint 13: PARM tab — Auto-Refresh spinner ──────────────────────
        wireParmAutoRefreshSpinner();

        // ── Sprint 13: PARM tab — Theme spinner ─────────────────────────────
        wireParmThemeSpinner();

        // ── Sprint 13: PARM tab — Widget Style spinner ──────────────────────
        wireParmWidgetStyleSpinner();

        // HUD Management removed — HUDs retired from plugin
        // wireHudManagement();

        // ── Sprint 13: PARM tab — Mission Prep ──────────────────────────────
        wireParmMissionPrep();

        // ── Sprint 13: PARM tab — Cache management ──────────────────────────
        wireParmCacheManagement();

        // ── Sprint 20: Import buttons + Radar source spinner ────────────────
        wireImportButtons();
        wireParmRadarSourceSpinner();
    }

    // ── Map tab ───────────────────────────────────────────────────────────────

    private void initMapTab() {
        btnDropMarker = templateView.findViewById(R.id.btn_drop_weather_marker);
        final TextView pickHint = templateView.findViewById(R.id.textview_pick_hint);

        if (btnDropMarker != null) {
            btnDropMarker.setOnClickListener(v -> {
                if (pickModeActive) cancelPickMode(btnDropMarker, pickHint);
                else                enterPickMode(btnDropMarker, pickHint);
            });
        }

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
            btnRemoveAll.setOnClickListener(v -> {
                markerManager.removeAllMarkers();
                TextView statusView = templateView.findViewById(R.id.textview_marker_status);
                if (statusView != null) statusView.setText(R.string.map_marker_none_placed);
            });
        }
    }

    private void enterPickMode(Button btn, TextView hint) {
        pickModeActive = true;
        btn.setText(R.string.map_btn_pick_cancel);
        btn.setAlpha(0.75f);
        if (hint != null) hint.setVisibility(View.VISIBLE);

        // Register the tool FIRST, then close the dropdown (see original comment).
        WeatherPlaceTool.start(getMapView(), WeatherPlaceTool.Mode.WEATHER,
                (pickedPoint, mode) -> {
                    resetPickMode(btn, hint);
                    pendingPickPoint = pickedPoint;
                    weatherViewModel.loadWeather(
                            pickedPoint.getLatitude(), pickedPoint.getLongitude(),
                            LocationSource.MAP_CENTRE);
                    Intent reopen = new Intent(SHOW_PLUGIN);
                    reopen.putExtra(EXTRA_REQUESTED_TAB, "conf");
                    com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(reopen);
                });

        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(this::closeDropDown, 200);
    }

    private void cancelPickMode(Button btn, TextView hint) {
        WeatherPlaceTool.cancel(getMapView());
        resetPickMode(btn, hint);
    }

    private void resetPickMode(Button btn, TextView hint) {
        pickModeActive   = false;
        pendingPickPoint = null;
        if (btn  != null) { btn.setText(R.string.map_btn_pick_and_drop); btn.setAlpha(1.0f); }
        if (hint != null) hint.setVisibility(View.GONE);
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

                // Auto-place after point-pick
                if (pendingPickPoint != null) {
                    LocationSnapshot placeSnap = new LocationSnapshot(
                            pendingPickPoint.getLatitude(), pendingPickPoint.getLongitude(),
                            null, LocationSource.MAP_CENTRE);
                    markerManager.placeMarker(placeSnap, w);
                    updateMarkerStatus(placeSnap);
                    pendingPickPoint = null;
                }
            } else if (state.isError()) {
                currentWeatherView.showError(state.getErrorMessage());
                if (weatherTabView != null) weatherTabView.showError(state.getErrorMessage());
                showErrorState(state.getErrorMessage());  // Sprint 13: error + retry
                pendingPickPoint = null;
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
        tv.setText(src.getDisplayName() + "  |  " + src.getSupportedParameters().size() + " parameters");
    }

    // ── Chart helpers ─────────────────────────────────────────────────────────

    private void wireChartToggleButtons() {
        int[] btnIds = {R.id.chart_toggle_temp, R.id.chart_toggle_humidity,
                R.id.chart_toggle_wind, R.id.chart_toggle_pressure};
        WeatherChartView.Series[] series = WeatherChartView.Series.values();
        for (int i = 0; i < btnIds.length && i < series.length; i++) {
            Button btn = templateView.findViewById(btnIds[i]);
            WeatherChartView.Series s = series[i];
            if (btn == null) continue;
            btn.setOnClickListener(v -> {
                boolean visible = chartView.toggleSeries(s);
                btn.setAlpha(visible ? 1.0f : 0.35f);
            });
        }
    }

    private void wireChartZoomAndRange() {
        if (chartView == null) return;

        final TextView zoomLabel = templateView.findViewById(R.id.chart_zoom_label);

        // Zoom buttons
        Button zoomIn  = templateView.findViewById(R.id.btn_chart_zoom_in);
        Button zoomOut = templateView.findViewById(R.id.btn_chart_zoom_out);
        if (zoomIn != null) zoomIn.setOnClickListener(v -> chartView.zoomIn());
        if (zoomOut != null) zoomOut.setOnClickListener(v -> chartView.zoomOut());

        // Time range buttons
        Button r24  = templateView.findViewById(R.id.btn_chart_range_24);
        Button r48  = templateView.findViewById(R.id.btn_chart_range_48);
        Button r72  = templateView.findViewById(R.id.btn_chart_range_72);
        Button rAll = templateView.findViewById(R.id.btn_chart_range_all);

        View.OnClickListener rangeClick = v -> {
            int hours = 0;
            String label = "7d";
            if (v == r24)       { hours = 24;  label = "24h"; }
            else if (v == r48)  { hours = 48;  label = "48h"; }
            else if (v == r72)  { hours = 72;  label = "3d"; }
            else                { hours = 0;   label = "7d"; }

            chartView.setTimeRange(hours);
            if (chartOverlaySeekBar != null && hourlyCache != null) {
                int max = (hours > 0 && hours < hourlyCache.size())
                        ? hours - 1 : hourlyCache.size() - 1;
                chartOverlaySeekBar.setMax(max);
                chartOverlaySeekBar.setProgress(0);
            }

            // Highlight active range button
            float active = 1.0f, inactive = 0.4f;
            if (r24 != null) r24.setAlpha(hours == 24 ? active : inactive);
            if (r48 != null) r48.setAlpha(hours == 48 ? active : inactive);
            if (r72 != null) r72.setAlpha(hours == 72 ? active : inactive);
            if (rAll != null) rAll.setAlpha(hours == 0 ? active : inactive);
        };

        if (r24 != null) r24.setOnClickListener(rangeClick);
        if (r48 != null) r48.setOnClickListener(rangeClick);
        if (r72 != null) r72.setOnClickListener(rangeClick);
        if (rAll != null) {
            rAll.setOnClickListener(rangeClick);
            rAll.setAlpha(1.0f); // default active
        }
        if (r24 != null) r24.setAlpha(0.4f);
        if (r48 != null) r48.setAlpha(0.4f);
        if (r72 != null) r72.setAlpha(0.4f);

        // Zoom label updates
        chartView.setZoomChangeListener((zoom, visHours, totalHours) -> {
            if (zoomLabel != null) {
                if (zoom <= 1.01f) {
                    zoomLabel.setText(totalHours + "h");
                } else {
                    zoomLabel.setText(visHours + "h / " + String.format(Locale.US, "%.1fx", zoom));
                }
            }
        });
    }

    private void updateChartReadouts(int index) {
        if (chartView == null) return;
        TextView valTemp     = templateView.findViewById(R.id.chart_val_temp);
        TextView valHumidity = templateView.findViewById(R.id.chart_val_humidity);
        TextView valWind     = templateView.findViewById(R.id.chart_val_wind);
        TextView valPressure = templateView.findViewById(R.id.chart_val_pressure);
        TextView hourLabel   = templateView.findViewById(R.id.chart_hour_label);
        if (hourLabel != null && hourlyCache != null
                && index >= 0 && index < hourlyCache.size()) {
            String iso     = hourlyCache.get(index).getIsoTime();
            String dayName = WeatherUiUtils.isoDayOfWeek(iso);
            String timeStr = iso.length() >= 16 ? iso.substring(11, 16) : iso;
            hourLabel.setText(dayName.isEmpty() ? timeStr : dayName + " " + timeStr);
        } else if (hourLabel != null) {
            hourLabel.setText("--:--");
        }
        setReadout(valTemp,     chartView.valueAt(WeatherChartView.Series.TEMPERATURE, index), "%.1f°");
        setReadout(valHumidity, chartView.valueAt(WeatherChartView.Series.HUMIDITY,    index), "%.0f%%");
        setReadout(valWind,     chartView.valueAt(WeatherChartView.Series.WIND,        index), "%.1f m/s");
        setReadout(valPressure, chartView.valueAt(WeatherChartView.Series.PRESSURE,    index), "%.0f hPa");
    }

    private static void setReadout(TextView tv, double val, String fmt) {
        if (tv != null && !Double.isNaN(val)) tv.setText(String.format(fmt, val));
    }

    private void updateFltCatBadge(WeatherModel w) {
        if (fltCatBadge == null) return;
        if (w == null || !w.isMetarSource() || w.getFlightCategory().isEmpty()) {
            fltCatBadge.setVisibility(View.GONE);
            return;
        }
        String cat = w.getFlightCategory();
        int bg;
        switch (cat) {
            case "VFR":  bg = 0xFF00AA00; break;
            case "MVFR": bg = 0xFF0055FF; break;
            case "IFR":  bg = 0xFFCC0000; break;
            case "LIFR": bg = 0xFFAA00AA; break;
            default:     bg = 0xFF555555; break;
        }
        fltCatBadge.setBackgroundColor(bg);
        fltCatBadge.setText(cat);
        fltCatBadge.setVisibility(View.VISIBLE);
    }

    private void updateMarkerStatus(LocationSnapshot snapshot) {
        TextView statusView = templateView.findViewById(R.id.textview_marker_status);
        if (statusView == null) return;
        statusView.setText(String.format("%s\n%s  [%s]",
                snapshot.getDisplayName(),
                snapshot.getCoordsLabel(),
                snapshot.getSource().label));
    }

    private void updateChartLocationHeader(LocationSnapshot snapshot) {
        TextView locLabel = templateView.findViewById(R.id.chart_location_label);
        TextView tsLabel  = templateView.findViewById(R.id.chart_timestamp_label);
        if (locLabel != null) locLabel.setText(snapshot.getDisplayName());
        if (tsLabel  != null && lastWeather != null) {
            String ts      = lastWeather.getRequestTimestamp();
            String dayName = WeatherUiUtils.isoDayOfWeek(ts);
            tsLabel.setText(dayName.isEmpty() ? ts : dayName + "  " + ts);
        }
    }

    // ── Marker intent handlers ─────────────────────────────────────────────────

    private void handleMarkerDetails(final String uid, final String requestedTab) {
        if (uid == null) { triggerAutoLoad(); return; }

        MapItem item = null;
        MapGroup root  = getMapView().getRootGroup();
        MapGroup wxGrp = root.findMapGroup(WeatherMapOverlay.GROUP_NAME);
        if (wxGrp != null) item = wxGrp.deepFindUID(uid);
        if (item == null) {
            MapGroup windGrp = root.findMapGroup(WindMapOverlay.GROUP_NAME);
            if (windGrp != null) item = windGrp.deepFindUID(uid);
        }
        if (item == null) {
            Toast.makeText(pluginContext, R.string.map_marker_no_data, Toast.LENGTH_SHORT).show();
            triggerAutoLoad();
            return;
        }

        final double lat = item.getMetaDouble("latitude",  Double.NaN);
        final double lon = item.getMetaDouble("longitude", Double.NaN);
        final String src = item.getMetaString("wx_source", "MAP_CENTRE");

        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            if (item instanceof com.atakmap.android.maps.PointMapItem) {
                com.atakmap.android.maps.PointMapItem pmi = (com.atakmap.android.maps.PointMapItem) item;
                LocationSource source = LocationSource.SELF_MARKER.name().equals(src)
                        ? LocationSource.SELF_MARKER : LocationSource.MAP_CENTRE;
                weatherViewModel.loadWeather(pmi.getPoint().getLatitude(),
                        pmi.getPoint().getLongitude(), source);
            } else {
                triggerAutoLoad();
            }
        } else {
            LocationSource source = LocationSource.SELF_MARKER.name().equals(src)
                    ? LocationSource.SELF_MARKER : LocationSource.MAP_CENTRE;
            weatherViewModel.loadWeather(lat, lon, source);
        }

        String defaultTab = uid.startsWith(WindMarkerManager.UID_PREFIX) ? "wind" : "wthr";
        jumpToTab(requestedTab != null ? requestedTab : defaultTab);
    }

    /**
     * Share a weather marker over the TAK network.
     *
     * <h4>Sprint 3 enhancement</h4>
     * Uses {@link com.atakmap.android.weather.cot.WeatherCotExporter} to build an
     * enriched CoT event with a {@code <__weather>} detail element, rather than
     * the basic {@code CotEventFactory.createCotEvent()} which only includes ATAK's
     * standard marker fields. This allows receiving TAK instances with the WeatherTool
     * plugin to reconstruct a full {@link WeatherModel}.
     *
     * Falls back to the basic factory if the enriched export fails.
     */
    private void handleShareMarker(final String uid) {
        if (uid == null) {
            Toast.makeText(pluginContext, R.string.map_marker_no_data, Toast.LENGTH_SHORT).show();
            return;
        }
        MapItem item = null;
        MapGroup root  = getMapView().getRootGroup();
        MapGroup wxGrp = root.findMapGroup(WeatherMapOverlay.GROUP_NAME);
        if (wxGrp != null) item = wxGrp.deepFindUID(uid);
        if (item == null) {
            MapGroup windGrp = root.findMapGroup(WindMapOverlay.GROUP_NAME);
            if (windGrp != null) item = windGrp.deepFindUID(uid);
        }
        if (item == null) {
            Toast.makeText(pluginContext, R.string.map_marker_no_data, Toast.LENGTH_SHORT).show();
            return;
        }

        // Sprint 3: Use enriched exporter for weather-specific detail elements
        final com.atakmap.android.weather.cot.WeatherCotExporter exporter =
                new com.atakmap.android.weather.cot.WeatherCotExporter();
        CotEvent event = exporter.buildWeatherEventFromItem(item);

        // Fallback to basic CotEventFactory if enriched export fails
        if (event == null || !event.isValid()) {
            event = CotEventFactory.createCotEvent(item);
        }

        if (event == null || !event.isValid()) {
            Toast.makeText(pluginContext, "Could not create CoT event for marker", Toast.LENGTH_SHORT).show();
            return;
        }

        exporter.broadcast(event);
        Toast.makeText(pluginContext, "Shared: " + item.getMetaString("callsign", uid),
                Toast.LENGTH_SHORT).show();
    }

    // ── Sprint 15: Overflow menu ────────────────────────────────────────────

    private void setupOverflowMenu() {
        if (btnOverflow == null) return;
        btnOverflow.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(appContext, v);
            popup.getMenu().add(0, 1, 0, "Generate Briefing");
            popup.getMenu().add(0, 2, 1, "Export CSV");
            popup.getMenu().add(0, 3, 2, "About WeatherTool");
            popup.setOnMenuItemClickListener(item -> {
                switch (item.getItemId()) {
                    case 1: generateBriefing(); return true;
                    case 2: /* TODO: Export CSV — Sprint 16 */ return true;
                    case 3: /* TODO: About dialog — Sprint 16 */ return true;
                }
                return false;
            });
            popup.show();
        });
    }

    // ── Sprint 12 (S12.3): Briefing generation ─────────────────────────────

    private void generateBriefing() {
        if (lastWeather == null) {
            Toast.makeText(pluginContext, "No weather data available for briefing",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        String locationName = lastLocation != null ? lastLocation.getDisplayName() : "Unknown";
        String sourceName = "Unknown";
        IWeatherRemoteSource activeSource = WeatherSourceManager.getInstance(appContext).getActiveSource();
        if (activeSource != null) sourceName = activeSource.getDisplayName();

        BriefingDocument briefing = BriefingGenerator.generate(
                lastWeather, dailyCache, hourlyCache, windCache,
                locationName, sourceName);

        // Show options dialog
        android.app.AlertDialog.Builder dialogBuilder =
                new android.app.AlertDialog.Builder(getMapView().getContext());
        dialogBuilder.setTitle("Weather Briefing");
        dialogBuilder.setMessage("Briefing generated for " + locationName
                + ".\nChoose an action:");

        dialogBuilder.setPositiveButton("Copy", (dialog, which) -> {
            briefing.copyToClipboard(pluginContext);
        });

        dialogBuilder.setNeutralButton("Share", (dialog, which) -> {
            briefing.share(pluginContext);
        });

        dialogBuilder.setNegativeButton("Save", (dialog, which) -> {
            briefing.saveToFile(pluginContext);
        });

        try {
            dialogBuilder.show();
        } catch (Exception e) {
            // Fallback: just copy to clipboard if dialog fails (e.g. wrong context)
            com.atakmap.coremap.log.Log.w(TAG, "Dialog show failed, copying to clipboard", e);
            briefing.copyToClipboard(pluginContext);
        }
    }

    private void jumpToTab(final String tabName) {
        if (tabName == null) { switchToDashboard(); return; }
        switch (tabName) {
            case "wthr":     switchToView(subWeather, "Weather");   break;
            case "wind":     switchToView(subWind, "Wind");         break;
            case "conf":     switchToView(subMarkers, "Markers");   break;  // legacy "conf" routes to Markers tab
            case "parm":     switchToView(subParm, "Settings");     break;
            case "overlays": switchToView(subOverlays, "Overlays"); break;
            case "markers":  switchToView(subMarkers, "Markers");   break;
            default:         switchToDashboard();                   break;
        }
    }

    // ── DropDownReceiver / OnStateListener ────────────────────────────────────

    /**
     * Call from WeatherMapComponent.onDestroyImpl() to remove orphaned 3D shapes.
     * Uses sharedWindEffectShape directly — windTabCoordinator may not be
     * initialised yet if dispose is called before the first SHOW_PLUGIN.
     */
    public void clearWindShapes() {
        if (sharedWindEffectShape != null) sharedWindEffectShape.removeAll();
        if (windTabCoordinator != null) windTabCoordinator.clearWindShapes();
    }

    /**
     * Called by WeatherMapComponent when the RadarOverlayManager active state
     * changes (e.g. toggled from the Overlay Manager). Updates the DDR CONF tab
     * status label so it stays in sync with the Overlay Manager checkbox.
     *
     * @param isActive {@code true} if the radar is now running
     */
    public void onRadarActiveChanged(boolean isActive) {
        if (radarTabCoordinator != null) {
            radarTabCoordinator.onRadarActiveChanged(isActive);
        }
    }

    // ── Sprint 13: Collapsible sections ─────────────────────────────────────

    private void initCollapsibleSections() {
        SharedPreferences sectionPrefs = appContext.getSharedPreferences(
                "weather_section_prefs", Context.MODE_PRIVATE);

        // METAR card
        View metarCard = templateView.findViewById(R.id.metar_card);
        if (metarCard != null) {
            View metarHeader = metarCard.findViewById(R.id.textview_metar_station);
            View metarContent = metarCard.findViewById(R.id.textview_metar_raw);
            if (metarHeader != null && metarContent != null) {
                CollapsibleSection.setup(metarHeader, metarContent, "metar_card", sectionPrefs);
            }
        }

        // Derived conditions card
        View derivedCard = templateView.findViewById(R.id.derived_card);
        if (derivedCard != null) {
            // The first child is the header, rest is content
            if (derivedCard instanceof android.view.ViewGroup) {
                android.view.ViewGroup vg = (android.view.ViewGroup) derivedCard;
                if (vg.getChildCount() >= 2) {
                    CollapsibleSection.setup(vg.getChildAt(0), vg.getChildAt(1),
                            "derived_card", sectionPrefs);
                }
            }
        }

        // Sprint 17 (S17.3): Settings tab collapsible sections
        CollapsibleSection.setup(
                templateView.findViewById(R.id.settings_header_sources),
                templateView.findViewById(R.id.settings_content_sources),
                "settings_sources", sectionPrefs);
        CollapsibleSection.setup(
                templateView.findViewById(R.id.settings_header_auto_refresh),
                templateView.findViewById(R.id.settings_content_auto_refresh),
                "settings_auto_refresh", sectionPrefs);
        CollapsibleSection.setup(
                templateView.findViewById(R.id.settings_header_theme),
                templateView.findViewById(R.id.settings_content_theme),
                "settings_theme", sectionPrefs);
        // HUD section removed — views no longer in layout
        CollapsibleSection.setup(
                templateView.findViewById(R.id.settings_header_mission_prep),
                templateView.findViewById(R.id.settings_content_mission_prep),
                "settings_mission_prep", sectionPrefs);

        // Sprint 20: Radar Sources collapsible section
        CollapsibleSection.setup(
                templateView.findViewById(R.id.settings_header_radar_sources),
                templateView.findViewById(R.id.settings_content_radar_sources),
                "settings_radar_sources", sectionPrefs);
        // Parameters section removed from layout — no collapsible setup needed
    }

    // ── Sprint 13: PARM tab — Auto-Refresh spinner ──────────────────────────

    private void wireParmAutoRefreshSpinner() {
        Spinner spinner = templateView.findViewById(R.id.spinner_auto_refresh);
        if (spinner == null) return;

        java.util.List<String> labels = java.util.Arrays.asList(
                "Disabled", "Every 15 min", "Every 30 min", "Every 60 min");
        spinner.setAdapter(WeatherUiUtils.makeDarkSpinnerAdapter(pluginContext, labels));
        WeatherUiUtils.styleSpinnerDark(spinner);

        // Set current selection
        SharedPreferences wxPrefs = appContext.getSharedPreferences("WeatherToolPrefs", Context.MODE_PRIVATE);
        int currentInterval = wxPrefs.getInt(AutoRefreshManager.PREF_KEY, 0);
        int selectedIdx = 0;
        for (int i = 0; i < AutoRefreshManager.INTERVALS.length; i++) {
            if (AutoRefreshManager.INTERVALS[i] == currentInterval) { selectedIdx = i; break; }
        }
        spinner.setSelection(selectedIdx, false);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                int interval = AutoRefreshManager.INTERVALS[pos];
                wxPrefs.edit().putInt(AutoRefreshManager.PREF_KEY, interval).apply();
                if (autoRefreshManager != null) {
                    autoRefreshManager.setInterval(interval);
                    autoRefreshManager.stop();
                    autoRefreshManager.start(() -> triggerAutoLoad());
                }
                Toast.makeText(pluginContext,
                        "Auto-refresh: " + AutoRefreshManager.intervalLabel(interval),
                        Toast.LENGTH_SHORT).show();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    // ── Sprint 13: PARM tab — Theme spinner ─────────────────────────────────

    private void wireParmThemeSpinner() {
        Spinner spinner = templateView.findViewById(R.id.spinner_theme);
        if (spinner == null) return;

        java.util.List<String> labels = java.util.Arrays.asList(
                "Dark", "Light", "NVG (Night Vision)");
        spinner.setAdapter(WeatherUiUtils.makeDarkSpinnerAdapter(pluginContext, labels));
        WeatherUiUtils.styleSpinnerDark(spinner);

        // Set current selection
        ThemeManager.Theme current = ThemeManager.getTheme();
        int selectedIdx = 0;
        ThemeManager.Theme[] themes = ThemeManager.Theme.values();
        for (int i = 0; i < themes.length; i++) {
            if (themes[i] == current) { selectedIdx = i; break; }
        }
        spinner.setSelection(selectedIdx, false);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                ThemeManager.Theme[] all = ThemeManager.Theme.values();
                if (pos >= 0 && pos < all.length) {
                    ThemeManager.saveTheme(appContext, all[pos]);
                    ThemeManager.applyToView(templateView);
                    Toast.makeText(pluginContext,
                            "Theme: " + all[pos].label, Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    // ── Sprint 13: PARM tab — Widget Style spinner ──────────────────────────

    private void wireParmWidgetStyleSpinner() {
        // spinner_widget_style replaced by HUD Management panel in layout redesign.
        // HUD controls are now wired in wireHudManagement().
        // Keep this method as a no-op for backward compatibility.
        if (templateView == null) return;
        Spinner spinner = null; // R.id.spinner_widget_style removed
        if (spinner == null) return;

        String[] labels = {"Text HUD", "Bitmap Widget"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(pluginContext,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        SharedPreferences wxPrefs = appContext.getSharedPreferences("WeatherToolPrefs", Context.MODE_PRIVATE);
        String currentStyle = wxPrefs.getString(
                com.atakmap.android.weather.overlay.weather.WeatherBitmapWidget.PREF_KEY_STYLE,
                com.atakmap.android.weather.overlay.weather.WeatherBitmapWidget.STYLE_TEXT);
        spinner.setSelection("bitmap".equals(currentStyle) ? 1 : 0, false);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                String style = pos == 1 ? "bitmap" : "text";
                wxPrefs.edit().putString(
                        com.atakmap.android.weather.overlay.weather.WeatherBitmapWidget.PREF_KEY_STYLE,
                        style).apply();
                Toast.makeText(pluginContext,
                        "Widget style: " + labels[pos] + " (restart to apply)",
                        Toast.LENGTH_SHORT).show();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    // ── HUD Management ───────────────────────────────────────────────────────

    /**
     * HUD Management — RETIRED. All HUD controls removed from Settings layout.
     * Method kept as empty stub to avoid breaking any remaining call sites.
     */
    private void wireHudManagement() {
    }

    /* HUD Management code fully removed — see git history for original.
       The following block was ~230 lines of HUD toggle, position spinner,
       opacity seekbar, and cache info wiring. All removed because:
       1. Bitmap HUD crashes with JNI NativeLayer null pointer
       2. Weather/Wind HUDs had no click interaction
       3. User decided HUDs are not useful for this plugin.
    */
    @SuppressWarnings("unused")
    private static void HUD_CODE_REMOVED() { /* placeholder */ }
    // DELETED: ~230 lines of HUD wiring code that referenced removed R.id.hud_* views.
    // See git history for the original wireHudManagement() method body.
    // updateCacheInfo() is defined in the Mission Prep section below
    //
    // DEAD_CODE_BLOCK_REPLACED — original contained:
    // - Position spinner listeners (hud_weather_position, hud_bitmap_position, hud_wind_position)
    // - Toggle listeners (hud_weather_toggle, hud_bitmap_toggle, hud_wind_toggle)
    // - Opacity seekbar listeners (hud_weather_opacity, hud_bitmap_opacity, hud_wind_opacity)
    // - Reset HUD button (btn_hud_reset)
    // - Cache info label update (updateCacheInfo)
    // ALL REMOVED because HUD Management section was deleted from tab_parameters.xml.
    //
    // If updateCacheInfo() is called elsewhere, the stub above prevents compile errors.

    // ── Sprint 13: Mission Prep + Cache Management (restored after HUD cleanup) ──

    private void wireParmMissionPrep() {
        Button btnMissionPrep = templateView.findViewById(R.id.btn_mission_prep);
        if (btnMissionPrep == null || missionPrepManager == null) return;

        final android.widget.ProgressBar progress =
                templateView.findViewById(R.id.mission_prep_progress);
        final TextView statusText = templateView.findViewById(R.id.mission_prep_status);

        btnMissionPrep.setOnClickListener(v -> {
            // Use current viewport bounds for area download
            com.atakmap.coremap.maps.coords.GeoBounds bounds = getMapView().getBounds();
            if (bounds == null) return;
            double north = bounds.getNorth();
            double south = bounds.getSouth();
            double east  = bounds.getEast();
            double west  = bounds.getWest();

            if (progress != null) { progress.setProgress(0); progress.setVisibility(View.VISIBLE); }
            if (statusText != null) statusText.setText("Downloading offline data…");

            missionPrepManager.downloadArea(north, south, east, west, 48,
                    new MissionPrepManager.ProgressCallback() {
                        @Override public void onProgress(int current, int total, String status) {
                            getMapView().post(() -> {
                                if (progress != null && total > 0)
                                    progress.setProgress(current * 100 / total);
                                if (statusText != null) statusText.setText(status);
                            });
                        }
                        @Override public void onComplete(int itemsDownloaded) {
                            getMapView().post(() -> {
                                if (progress != null) progress.setVisibility(View.GONE);
                                if (statusText != null)
                                    statusText.setText("Downloaded " + itemsDownloaded + " items");
                                updateCacheInfo();
                            });
                        }
                        @Override public void onError(String error) {
                            getMapView().post(() -> {
                                if (progress != null) progress.setVisibility(View.GONE);
                                if (statusText != null) statusText.setText("Error: " + error);
                            });
                        }
                    });
        });
    }

    private void wireParmCacheManagement() {
        // Cache info text is updated by updateCacheInfo()
        updateCacheInfo();
    }

    // ── Sprint 20: Import buttons ───────────────────────────────────────────

    private void wireImportButtons() {
        // Weather Source import handled by SourceManagerView "Browse & Import" button.
        // Only radar import wired here.

        // Import Radar Source — opens ATAK native file browser
        Button btnImportTile = templateView.findViewById(R.id.btn_import_tile_source);
        if (btnImportTile != null) {
            btnImportTile.setOnClickListener(v -> {
                java.io.File startDir = new java.io.File(
                        android.os.Environment.getExternalStorageDirectory(),
                        "atak/tools/weather_tiles");
                if (!startDir.exists()) startDir.mkdirs();

                com.atakmap.android.gui.ImportFileBrowserDialog.show(
                        "Import Radar Source",
                        startDir.getAbsolutePath(),
                        new String[] { "json", "xml" },
                        new com.atakmap.android.gui.ImportFileBrowserDialog.DialogDismissed() {
                            @Override public void onFileSelected(java.io.File f) {
                                if (f == null) return;
                                try {
                                    com.atakmap.android.weather.data.remote.SourceDefinitionLoader
                                            .importTileSourceFromFile(pluginContext, f);
                                    com.atakmap.android.weather.data.remote.SourceDefinitionLoader
                                            .clearCache();
                                    Toast.makeText(pluginContext,
                                            "Imported radar source: " + f.getName(),
                                            Toast.LENGTH_SHORT).show();
                                } catch (Exception e) {
                                    Toast.makeText(pluginContext,
                                            "Import failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                }
                            }
                            @Override public void onDialogClosed() {}
                        },
                        getMapView().getContext()
                );
            });
        }
    }

    // ── Sprint 20: Radar source list on Settings tab ────────────────────────

    private void wireParmRadarSourceSpinner() {
        LinearLayout radarList = templateView.findViewById(R.id.radar_source_list);
        if (radarList == null) return;

        com.atakmap.android.weather.overlay.radar.RadarSourceSelector selector =
                new com.atakmap.android.weather.overlay.radar.RadarSourceSelector(appContext);
        selector.loadSources();

        java.util.List<com.atakmap.android.weather.data.remote.schema.WeatherSourceDefinitionV2> sources =
                selector.getAvailableSources();

        populateRadarSourceList(radarList, sources, selector);

        // Wire Scan Folder button for radar
        Button btnScanRadar = templateView.findViewById(R.id.btn_scan_radar_folder);
        if (btnScanRadar != null) {
            btnScanRadar.setOnClickListener(v -> {
                com.atakmap.android.weather.data.remote.SourceDefinitionLoader.clearCache();
                selector.refreshSources();
                java.util.List<com.atakmap.android.weather.data.remote.schema.WeatherSourceDefinitionV2> refreshed =
                        selector.getAvailableSources();
                populateRadarSourceList(radarList, refreshed, selector);
                Toast.makeText(pluginContext,
                        "Radar sources: " + refreshed.size() + " found",
                        Toast.LENGTH_SHORT).show();
            });
        }
    }

    /**
     * Populate the radar source list with ON/OFF toggle rows.
     * The active source has its toggle ON; tapping a toggle sets it as active.
     */
    private void populateRadarSourceList(
            LinearLayout container,
            java.util.List<com.atakmap.android.weather.data.remote.schema.WeatherSourceDefinitionV2> sources,
            com.atakmap.android.weather.overlay.radar.RadarSourceSelector selector) {
        container.removeAllViews();

        if (sources == null || sources.isEmpty()) {
            TextView empty = new TextView(pluginContext);
            empty.setText("No radar sources found");
            empty.setTextSize(11);
            empty.setTextColor(android.graphics.Color.parseColor("#8b949e"));
            empty.setPadding(0, 16, 0, 16);
            container.addView(empty);
            return;
        }

        int activeIdx = selector.getActiveSourceIndex();
        float dp = pluginContext.getResources().getDisplayMetrics().density;

        for (int i = 0; i < sources.size(); i++) {
            com.atakmap.android.weather.data.remote.schema.WeatherSourceDefinitionV2 def = sources.get(i);
            final int idx = i;

            LinearLayout row = new LinearLayout(pluginContext);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding((int)(4*dp), (int)(6*dp), (int)(4*dp), (int)(6*dp));

            // ON/OFF toggle (RadioButton-like: only one active at a time)
            android.widget.Switch toggle = new android.widget.Switch(getMapView().getContext());
            toggle.setChecked(i == activeIdx);
            toggle.setTextSize(11);
            toggle.setText("");

            // Source name label
            TextView label = new TextView(pluginContext);
            String name = def.getDisplayName() != null ? def.getDisplayName()
                    : (def.getProvider() != null ? def.getProvider() : def.getSourceId());
            label.setText(name);
            label.setTextSize(12);
            label.setTextColor(android.graphics.Color.parseColor("#c9d1d9"));
            label.setPadding((int)(8*dp), 0, 0, 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            label.setLayoutParams(lp);

            // Provider info
            TextView info = new TextView(pluginContext);
            String provider = def.getProvider() != null ? def.getProvider() : "";
            info.setText(provider);
            info.setTextSize(9);
            info.setTextColor(android.graphics.Color.parseColor("#8b949e"));
            info.setPadding((int)(4*dp), 0, 0, 0);

            row.addView(toggle);
            row.addView(label);
            row.addView(info);

            toggle.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) {
                    String sourceId = def.getRadarSourceId() != null
                            ? def.getRadarSourceId() : def.getSourceId();
                    selector.setActiveSourceId(sourceId);
                    // Uncheck all other toggles
                    for (int c = 0; c < container.getChildCount(); c++) {
                        View child = container.getChildAt(c);
                        if (child instanceof LinearLayout) {
                            View first = ((LinearLayout) child).getChildAt(0);
                            if (first instanceof android.widget.Switch && first != btn) {
                                ((android.widget.Switch) first).setChecked(false);
                            }
                        }
                    }
                    Toast.makeText(pluginContext,
                            "Active radar: " + name, Toast.LENGTH_SHORT).show();
                }
            });

            container.addView(row);
        }
    }

    private void updateCacheInfo() {
        TextView cacheInfo = templateView.findViewById(R.id.cache_info_text);
        if (cacheInfo == null || missionPrepManager == null) return;
        MissionPrepManager.OfflineStatus status = missionPrepManager.getOfflineStatus();
        cacheInfo.setText(pluginContext.getString(R.string.cache_info,
                MissionPrepManager.formatBytes(status.cacheSizeBytes)));
    }

    // ── Sprint 13: Dashboard staleness badge ─────────────────────────────────

    private void updateStalenesssBadge() {
        if (lastUpdatedBadge == null) return;
        if (lastUpdateMs <= 0) {
            lastUpdatedBadge.setVisibility(View.GONE);
            return;
        }
        String level = AutoRefreshManager.getStalenessLevel(lastUpdateMs);
        String text  = AutoRefreshManager.formatTimeSince(lastUpdateMs);
        lastUpdatedBadge.setText(text);
        lastUpdatedBadge.setTextColor(ThemeManager.getStalenessColor(level));
        lastUpdatedBadge.setVisibility(View.VISIBLE);
    }

    private void updateOfflineBadge() {
        if (offlineBadge == null) return;
        boolean online = MissionPrepManager.isOnline(appContext);
        offlineBadge.setVisibility(online ? View.GONE : View.VISIBLE);
    }

    // ── Sprint 13: Loading / Error state helpers ─────────────────────────────

    private void showLoadingState() {
        if (loadingProgress != null) loadingProgress.setVisibility(View.VISIBLE);
        hideErrorState();
    }

    private void hideLoadingState() {
        if (loadingProgress != null) loadingProgress.setVisibility(View.GONE);
    }

    private void showErrorState(String msg) {
        hideLoadingState();
        if (errorState != null) errorState.setVisibility(View.VISIBLE);
        if (errorMessage != null && msg != null) errorMessage.setText(msg);
    }

    private void hideErrorState() {
        if (errorState != null) errorState.setVisibility(View.GONE);
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

        // Evict in-memory caches
        if (cachingRepo != null) { cachingRepo.clearWindCache(); cachingRepo = null; }
        networkRepo = null;
        fltCatBadge = null;

        if (radarTabCoordinator   != null) { radarTabCoordinator.dispose();   radarTabCoordinator   = null; }
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
        lastUpdatedBadge = null;
        offlineBadge = null;
        loadingProgress = null;
        errorState = null;
        errorMessage = null;

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
