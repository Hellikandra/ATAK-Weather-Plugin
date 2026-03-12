package com.atakmap.android.weather;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.AdapterView;
import android.widget.Toast;
import com.atakmap.android.weather.data.remote.WeatherSourceManager;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.weather.data.WeatherRepositoryImpl;
import com.atakmap.android.weather.data.cache.CachingWeatherRepository;
import com.atakmap.android.weather.data.cache.WeatherDatabase;
import com.atakmap.android.weather.data.geocoding.NominatimGeocodingSource;
import com.atakmap.android.weather.data.remote.IWeatherRemoteSource;
import com.atakmap.android.weather.data.remote.OpenMeteoSource;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.LocationSource;
import com.atakmap.android.weather.domain.model.LocationSnapshot;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.repository.IGeocodingRepository;
import com.atakmap.android.weather.infrastructure.preferences.WeatherParameterPreferences;
import com.atakmap.android.weather.overlay.WeatherMapOverlay;
import com.atakmap.android.weather.overlay.marker.WeatherMarkerManager;
import com.atakmap.android.weather.overlay.wind.WindEffectShape;
import com.atakmap.android.weather.overlay.wind.WindMarkerManager;
import com.atakmap.android.weather.plugin.R;
import com.atakmap.android.weather.presentation.view.ComparisonView;
import com.atakmap.android.weather.presentation.view.CurrentWeatherView;
import com.atakmap.android.weather.presentation.view.DailyForecastView;
import com.atakmap.android.weather.presentation.view.ParametersView;
import com.atakmap.android.weather.presentation.view.WeatherChartView;
import com.atakmap.android.weather.presentation.view.WindProfileView;
import com.atakmap.android.weather.presentation.viewmodel.WeatherViewModel;
import com.atakmap.android.weather.presentation.viewmodel.WindProfileViewModel;
import com.atakmap.android.weather.presentation.viewmodel.UiState;
import com.atakmap.android.weather.util.MapPointPicker;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.importexport.CotEventFactory;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * WeatherDropDownReceiver — ATAK drop-down entry point.
 *
 * ── Feature: "Tap Map to Place Weather Marker" (Feature 1) ───────────────────
 *
 * The old "Drop Weather Marker Here" placed a marker at the last known weather
 * position without any user selection.  The new flow is:
 *
 *   1. User taps "Tap Map to Place Weather Marker"
 *      → The drop-down collapses (so the map is fully visible)
 *      → A MapPointPicker is registered to listen for MAP_CONFIRMED_CLICK
 *      → The button text changes to "✕ Cancel — picking location…"
 *      → An orange hint banner appears
 *
 *   2. User taps anywhere on the live map
 *      → The screen point is converted to GeoPoint via inverseWithElevation()
 *      → weatherViewModel.loadWeather() is called at that coordinate
 *      → On success the weather observer auto-places the marker via
 *        markerManager.placeMarker()  (same path as before)
 *      → The drop-down reopens on the Map tab
 *
 *   3. If the user taps the button again while picking is active
 *      → The picker is cancelled, the button resets, panel restores
 *
 * ── Feature: Wind Effect Drawing (Feature 2) ─────────────────────────────────
 *
 * The Wind tab (Tab 2) now has:
 *   • Range SeekBar  (0.5 – 10.0 km, 0.5 km steps)
 *   • Height SeekBar (50 – 2000 m, 50 m steps)
 *   • "Draw Wind Effect" button → calls WindEffectShape.place()
 *   • "Clear" button            → calls WindEffectShape.removeAll()
 *
 * WindEffectShape draws two items in the WindMapOverlay group:
 *   • A filled directional cone  (Polyline, type "u-d-f")
 *   • A filled range-height box  (SimpleRectangle, type "u-d-r")
 * Both are colour-coded by wind speed tier and rotate to match wind direction.
 */
public class WeatherDropDownReceiver extends DropDownReceiver
        implements OnStateListener {

    public static final String TAG             = WeatherDropDownReceiver.class.getSimpleName();
    public static final String SHOW_PLUGIN     = "com.atakmap.android.weather.SHOW_PLUGIN";
    public static final String SHARE_MARKER    = "com.atakmap.android.weather.SHARE_MARKER";
    public static final String REMOVE_MARKER   = "com.atakmap.android.weather.REMOVE_MARKER";

    public static final String EXTRA_TARGET_UID    = "targetUID";
    public static final String EXTRA_REQUESTED_TAB = "requestedTab";

    // ── Layout ───────────────────────────────────────────────────────────────
    private final View    templateView;
    private final Context pluginContext;
    private final Context appContext;

    // ── ViewModels ───────────────────────────────────────────────────────────
    private WeatherViewModel     weatherViewModel;
    private WindProfileViewModel windViewModel;
    /** Kept so disposeImpl can clear the in-memory wind profile cache. */
    private CachingWeatherRepository cachingRepo;
    private WeatherRepositoryImpl    networkRepo;  // kept to apply active-source changes
    private TextView                 fltCatBadge;  // flight-category chip in source strip
    private com.atakmap.android.weather.overlay.radar.RadarOverlayManager radarManager;

    // ── View helpers ─────────────────────────────────────────────────────────
    private CurrentWeatherView currentWeatherView;
    private DailyForecastView  dailyForecastView;
    private WindProfileView    windProfileView;
    private ParametersView     parametersView;
    private WeatherChartView   chartView;
    private ComparisonView     comparisonView;

    // ── Preferences ──────────────────────────────────────────────────────────
    private WeatherParameterPreferences paramPrefs;

    // ── Init guard ───────────────────────────────────────────────────────────
    private boolean initialized = false;

    // ── TabHost reference ────────────────────────────────────────────────────
    private android.widget.TabHost tabHost;

    // ── Marker managers ──────────────────────────────────────────────────────
    private final WeatherMarkerManager markerManager;
    private final WindMarkerManager    windMarkerManager;
    private       WindEffectShape      windEffectShape;

    // ── Last known good weather + location ───────────────────────────────────
    private WeatherModel      lastWeather;
    private LocationSnapshot  lastLocation;

    // ── Last placed wind marker info (for wind effect drawing) ───────────────
    private double lastWindLat = Double.NaN;
    private double lastWindLon = Double.NaN;
    private boolean lastWindIsSelf = false;

    /**
     * Tracks ALL slots that have had a wind marker placed on the map.
     * Key = WindEffectShape.uidSuffix(lat, lon, isSelf)
     * Value = the WindSlot at the time of placement.
     * Populated when "Drop Wind Marker" is pressed; used by redrawAllPlacedSlots()
     * so that moving the hour seekbar updates ALL visible wind cones simultaneously.
     */
    private final java.util.Map<String, WindProfileViewModel.WindSlot> placedWindSlots =
            new java.util.LinkedHashMap<>();

    /** Hour index currently shown in the wind chart / effect (0 = now). */
    private int     windHourIndex = 0;

    // ── Chart overlay seekbar (transparent, sits on top of chart_frame) ─────
    private SeekBar chartOverlaySeekBar;
    /** Prevents feedback loop when syncing the two seekbars. */
    private boolean suppressSeekSync = false; // unused — kept for subclass compat

    // ── Hourly cache ─────────────────────────────────────────────────────────
    private List<HourlyEntryModel> hourlyCache;

    // ── Point-pick state ─────────────────────────────────────────────────────
    private MapPointPicker activePicker    = null;
    private boolean        pickModeActive  = false;
    private Button         btnDropMarker   = null;  // kept for text toggling

    // ── Wind effect parameters (driven by SeekBars) ──────────────────────────
    private double windEffectRangeM  = 2000.0;  // 2 km default
    private double windEffectHeightM =  500.0;  // 500 m default
    /** True while a wind-effect prism is placed — enables live seek redraw. */
    private boolean windEffectActive = false;
    /** Wind profile list cached from WindViewModel; null until first wind load. */
    private java.util.List<com.atakmap.android.weather.domain.model.WindProfileModel>
            lastWindProfiles = null;
    /** Last active slot index — used to detect slot switches in obsWindSlots. */
    private int lastActiveSlotIdx = -1;
    /** Last source ID bound to the chart — used to force altitude re-detection on source change. */
    private String lastBoundSourceId = null;

    // ── observeForever observer references (stored so we can removeObserver in disposeImpl) ──
    // Without these, every plugin reinstall in the same ATAK session registers
    // another set of observers on the same LiveData instances, and they all fire.
    private androidx.lifecycle.Observer<UiState<WeatherModel>>             obsCurrentWeather;
    private androidx.lifecycle.Observer<LocationSnapshot>                  obsActiveLocation;
    private androidx.lifecycle.Observer<UiState<java.util.List<com.atakmap.android.weather.domain.model.DailyForecastModel>>> obsDailyForecast;
    private androidx.lifecycle.Observer<UiState<java.util.List<HourlyEntryModel>>> obsHourlyForecast;
    private androidx.lifecycle.Observer<Integer>                           obsSelectedHour;
    private androidx.lifecycle.Observer<String>                            obsErrorMessage;
    private androidx.lifecycle.Observer<String>                            obsCacheBadge;
    private androidx.lifecycle.Observer<UiState<java.util.List<com.atakmap.android.weather.domain.model.WindProfileModel>>> obsWindProfile;
    private androidx.lifecycle.Observer<UiState<com.atakmap.android.weather.domain.model.ComparisonModel>> obsComparison;
    private androidx.lifecycle.Observer<LocationSnapshot>                  obsSelfLocation;
    private androidx.lifecycle.Observer<LocationSnapshot>                  obsCenterLocation;
    // Multi-slot wind observers
    private androidx.lifecycle.Observer<java.util.List<WindProfileViewModel.WindSlot>> obsWindSlots;
    private androidx.lifecycle.Observer<Integer>                           obsActiveWindSlot;

    // ── Constructor ──────────────────────────────────────────────────────────

    public WeatherDropDownReceiver(final MapView mapView,
                                   final Context context,
                                   final WeatherMarkerManager markerManager,
                                   final WindMarkerManager windMarkerManager) {
        super(mapView);
        this.pluginContext     = context;
        this.appContext        = mapView.getContext();
        this.markerManager     = markerManager;
        this.windMarkerManager = windMarkerManager;
        templateView = PluginLayoutInflater.inflate(context, R.layout.main_layout, null);
    }

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
                if (uid.startsWith("wx_wind") && windMarkerManager != null)
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
        // ── Source registry ───────────────────────────────────────────────────
        // WeatherSourceManager is the single source of truth for registered providers.
        // The CONF tab Spinner reads from it; we must also mirror its map into
        // WeatherRepositoryImpl so data loads respect the active selection.
        WeatherSourceManager sourceMgr = WeatherSourceManager.getInstance(appContext);

        // Build the sources map from everything registered in the manager so
        // that adding a new source in WeatherSourceManager is the only change needed.
        Map<String, IWeatherRemoteSource> sources = new HashMap<>();
        for (WeatherSourceManager.SourceEntry entry : sourceMgr.getAvailableEntries()) {
            IWeatherRemoteSource src = sourceMgr.getSourceById(entry.sourceId);
            if (src != null) sources.put(entry.sourceId, src);
        }

        String activeSourceId = sourceMgr.getActiveSourceId();
        WeatherRepositoryImpl networkRepo = new WeatherRepositoryImpl(sources, activeSourceId);
        IGeocodingRepository  geocodingRepo = new NominatimGeocodingSource();

        paramPrefs = new WeatherParameterPreferences(pluginContext);
        networkRepo.setParameterPreferences(paramPrefs);

        CachingWeatherRepository cachingRepo = new CachingWeatherRepository(
                networkRepo,
                WeatherDatabase.getInstance(appContext).weatherDao(),
                paramPrefs);
        cachingRepo.purgeExpired();
        this.cachingRepo   = cachingRepo;   // keep reference for dispose
        this.networkRepo   = networkRepo;   // keep to update active source on Spinner change

        weatherViewModel = new WeatherViewModel(cachingRepo, geocodingRepo);
        windViewModel    = new WindProfileViewModel(cachingRepo);

        // WindEffectShape needs the WindMapOverlay — we get it from the wind
        // marker manager's overlay reference.  Both share the same overlay instance.
        windEffectShape = new WindEffectShape(
                getMapView(),
                windMarkerManager.getOverlay());
    }

    @SuppressWarnings("deprecation")
    private void initTabs() {
        tabHost = templateView.findViewById(R.id.mainTabHost);
        tabHost.setup();
        // 3-tab layout: WTHR (forecast+chart), WIND (wind+comparison), CONF (map+params)
        android.widget.TabHost.TabSpec spec;
        spec = tabHost.newTabSpec("wthr"); spec.setIndicator("WTHR"); spec.setContent(R.id.subTabWidget1); tabHost.addTab(spec);
        spec = tabHost.newTabSpec("wind"); spec.setIndicator("WIND"); spec.setContent(R.id.subTabWidget2); tabHost.addTab(spec);
        spec = tabHost.newTabSpec("conf"); spec.setIndicator("CONF"); spec.setContent(R.id.subTabWidget3); tabHost.addTab(spec);
        spec = tabHost.newTabSpec("parm"); spec.setIndicator("PARM"); spec.setContent(R.id.subTabWidget4); tabHost.addTab(spec);
    }

    private void initViewHelpers() {
        currentWeatherView = new CurrentWeatherView(templateView, pluginContext);
        dailyForecastView  = new DailyForecastView(templateView);
        windProfileView    = new WindProfileView(templateView);

        // ── Refresh button ────────────────────────────────────────────────────
        View refreshBtn = templateView.findViewById(R.id.imageButton);
        refreshBtn.setOnClickListener(v -> {
            double lat = getMapView().getCenterPoint().get().getLatitude();
            double lon = getMapView().getCenterPoint().get().getLongitude();
            weatherViewModel.loadWeather(lat, lon, LocationSource.MAP_CENTRE);
            Toast.makeText(pluginContext, R.string.loading_map_centre, Toast.LENGTH_SHORT).show();
        });
        refreshBtn.setOnLongClickListener(v -> {
            double lat = getMapView().getSelfMarker().getPoint().getLatitude();
            double lon = getMapView().getSelfMarker().getPoint().getLongitude();
            if (lat == 0 && lon == 0) {
                Toast.makeText(pluginContext, R.string.no_gps_fix, Toast.LENGTH_SHORT).show();
                return true;
            }
            weatherViewModel.loadWeather(lat, lon, LocationSource.SELF_MARKER);
            Toast.makeText(pluginContext, R.string.loading_self_marker, Toast.LENGTH_SHORT).show();
            return true;
        });

        windProfileView.setRequestClickListener(v -> {
            // Use WeatherPlaceTool for wind placement — user taps map to pick the point.
            // Close the dropdown first so the map is fully visible.
            closeDropDown();
            com.atakmap.android.weather.util.WeatherPlaceTool.start(
                    getMapView(),
                    com.atakmap.android.weather.util.WeatherPlaceTool.Mode.WIND,
                    (pickedPoint, mode) -> {
                        double lat = pickedPoint.getLatitude();
                        double lon = pickedPoint.getLongitude();
                        Toast.makeText(pluginContext,
                                String.format(java.util.Locale.US,
                                        "Fetching wind: %.4f°, %.4f°", lat, lon),
                                Toast.LENGTH_SHORT).show();
                        // Get the active weather source ID so we can tag the slot
                        String srcId = WeatherSourceManager.getInstance(appContext)
                                .getActiveSourceId();
                        windViewModel.addSlot(lat, lon, srcId);
                        // Re-open the panel on the WIND tab
                        Intent reopen = new Intent(SHOW_PLUGIN);
                        reopen.putExtra(EXTRA_REQUESTED_TAB, "wind");
                        com.atakmap.android.ipc.AtakBroadcast.getInstance()
                                .sendBroadcast(reopen);
                    });
        });

        windProfileView.setSlotTabListener(new WindProfileView.SlotTabListener() {
            @Override public void onSlotSelected(int slotIndex) {
                windViewModel.setActiveSlot(slotIndex);
            }
            @Override public void onSlotRemoved(int slotIndex) {
                WindProfileViewModel.WindSlot slot = windViewModel.getSlotList().size() > slotIndex
                        ? windViewModel.getSlotList().get(slotIndex) : null;
                windViewModel.removeSlot(slotIndex);
                // Remove 3D shapes for this slot
                if (slot != null && windEffectShape != null) {
                    final String suffix = WindEffectShape.uidSuffix(slot.lat, slot.lon, false);
                    windEffectShape.removeAll(); // simplest: remove all, DDR will re-draw active
                    windEffectActive = false;
                }
            }
        });

        // Tab 4 — Parameters
        parametersView = new ParametersView(templateView, pluginContext, paramPrefs);
        // Build param list for the currently active source (deferred — rebuildParmsForSource
        // is also called from wireParmSourceSpinner which runs right after wireViews).
        WeatherSourceManager parmSrcMgr = WeatherSourceManager.getInstance(appContext);
        if (parmSrcMgr.getActiveSource() != null) {
            rebuildParmsForSource(parmSrcMgr.getActiveSourceId());
        }
        parametersView.setOnChangeListener(() -> {
            Toast.makeText(pluginContext, R.string.params_reloading, Toast.LENGTH_SHORT).show();
            triggerAutoLoad();
        });

        // ── Refresh sources button ────────────────────────────────────────────
        Button btnRefreshSources = templateView.findViewById(R.id.btn_refresh_sources);
        if (btnRefreshSources != null) {
            btnRefreshSources.setOnClickListener(v -> {
                com.atakmap.android.weather.data.remote.SourceDefinitionLoader.clearCache();
                WeatherSourceManager mgr2 = WeatherSourceManager.getInstance(appContext);
                java.util.Map<String, com.atakmap.android.weather.data.remote.WeatherSourceDefinition>
                        allDefs = com.atakmap.android.weather.data.remote.SourceDefinitionLoader
                        .loadAll(pluginContext);
                rebuildParmsForSource(mgr2.getActiveSourceId());
                Toast.makeText(pluginContext,
                        pluginContext.getString(R.string.sources_refreshed, allDefs.size()),
                        Toast.LENGTH_SHORT).show();
            });
        }


        // Also wire the radar source refresh button in the CONF tab
        Button btnRefreshRadarSrc = templateView.findViewById(R.id.btn_refresh_radar_sources);
        if (btnRefreshRadarSrc != null) {
            btnRefreshRadarSrc.setOnClickListener(v -> {
                com.atakmap.android.weather.data.remote.SourceDefinitionLoader.clearCache();
                java.util.List<com.atakmap.android.weather.data.remote.WeatherSourceDefinition>
                        radarDefs = com.atakmap.android.weather.data.remote.SourceDefinitionLoader
                        .loadRadarSources(pluginContext);
                Toast.makeText(pluginContext,
                        pluginContext.getString(R.string.radar_sources_refreshed, radarDefs.size()),
                        Toast.LENGTH_SHORT).show();
                // Reload radar spinner
                android.widget.Spinner rs = templateView.findViewById(R.id.spinner_radar_source);
                if (rs != null && rs.getAdapter() instanceof android.widget.ArrayAdapter) {
                    @SuppressWarnings("unchecked")
                    android.widget.ArrayAdapter<String> ada =
                            (android.widget.ArrayAdapter<String>) rs.getAdapter();
                    ada.clear();
                    for (com.atakmap.android.weather.data.remote.WeatherSourceDefinition d : radarDefs)
                        ada.add(d.displayName);
                    ada.notifyDataSetChanged();
                }
            });
        }

        // Tab 1 — Chart embedded in WTHR tab
        FrameLayout chartFrame = templateView.findViewById(R.id.chart_frame);
        if (chartFrame != null) {
            chartView = new WeatherChartView(pluginContext);
            // Insert at index 0 so the overlay SeekBar stays on top
            chartFrame.addView(chartView, 0);
            wireChartToggleButtons();
        }
        // ── Chart overlay seekbar (transparent, drawn on top of chart) ─────
        chartOverlaySeekBar = templateView.findViewById(R.id.seekbar_chart_overlay);
        if (chartOverlaySeekBar != null) {
            chartOverlaySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    if (!fromUser) return;
                    weatherViewModel.selectHour(p);
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
        }
        // ── CONF tab: API Source Spinner ─────────────────────────────────
        wireSourceSpinner();
        wireParmSourceSpinner();

        // ── CONF tab: Radar overlay controls ─────────────────────────────
        initRadarControls();

        // ── Tab 3 — Map / Marker controls ────────────────────────────────────
        initMapTab();

        // ── Tab 2 — Wind tab ─────────────────────────────────────────────────
        initWindTab();

        // Tab 6 — Comparison
        comparisonView = new ComparisonView(templateView);
        templateView.findViewById(R.id.comp_refresh_button)
                .setOnClickListener(v -> triggerComparison());
    }

    // ── Tab 3 — Map tab initialisation ────────────────────────────────────────

    /**
     * "Drop Weather Marker" is now a two-step pick-then-fetch flow:
     *
     *   Idle state:  button says "📍 Tap Map to Place Weather Marker"
     *   Pick state:  button says "✕ Cancel — picking location…"  (orange tint)
     *                hint banner is visible
     *                MapPointPicker is listening for MAP_CONFIRMED_CLICK
     *
     * Tapping the button toggles between idle and pick states.
     * Once the user taps the map the picker fires onPointPicked():
     *   1. Calls weatherViewModel.loadWeather() for that coordinate
     *   2. Resets the button back to idle
     *   3. The weather observer auto-places the marker on success (see observeViewModels)
     *   4. Reopens the drop-down on the Map tab
     */

    // ── Radar overlay controls ────────────────────────────────────────────────

    @SuppressLint("SetTextI18n")
    private void initRadarControls() {
        radarManager = new com.atakmap.android.weather.overlay.radar.RadarOverlayManager(
                getMapView());

        // ── Radar source spinner ──────────────────────────────────────────────
        android.widget.Spinner radarSourceSpinner =
                templateView.findViewById(R.id.spinner_radar_source);
        if (radarSourceSpinner != null) {
            java.util.List<com.atakmap.android.weather.data.remote.WeatherSourceDefinition>
                    radarSources = com.atakmap.android.weather.data.remote.SourceDefinitionLoader
                    .loadRadarSources(pluginContext);
            // If no JSON sources found, fall back to the built-in RainViewer definition
            if (radarSources.isEmpty()) {
                radarSources = new java.util.ArrayList<>();
                radarSources.add(new com.atakmap.android.weather.data.remote
                        .WeatherSourceDefinition.Builder()
                        .radarSourceId("rainviewer")
                        .displayName("RainViewer (built-in)")
                        .manifestUrl(com.atakmap.android.weather.overlay.radar
                                .RadarTileProvider.MANIFEST_URL)
                        .build());
            }
            final java.util.List<com.atakmap.android.weather.data.remote.WeatherSourceDefinition>
                    finalRadarSources = radarSources;
            java.util.List<String> radarNames = new java.util.ArrayList<>();
            for (com.atakmap.android.weather.data.remote.WeatherSourceDefinition d : radarSources)
                radarNames.add(d.displayName);
            android.widget.ArrayAdapter<String> radarAdapter =
                    makeDarkSpinnerAdapter(radarNames);
            radarSourceSpinner.setAdapter(radarAdapter);
            radarSourceSpinner.setOnItemSelectedListener(
                    new android.widget.AdapterView.OnItemSelectedListener() {
                        @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
                        @Override public void onItemSelected(android.widget.AdapterView<?> p,
                                                             android.view.View v, int pos, long id) {
                            com.atakmap.android.weather.data.remote.WeatherSourceDefinition def =
                                    finalRadarSources.get(pos);
                            if (radarManager != null && def.manifestUrl != null) {
                                radarManager.setRadarSource(def.manifestUrl, def.tileUrlTemplate);
                            }
                        }
                    });
        }

        SeekBar  frameSeek  = templateView.findViewById(R.id.seekbar_radar_frame);
        SeekBar  opacitySeek= templateView.findViewById(R.id.seekbar_radar_opacity);
        TextView timeLabel  = templateView.findViewById(R.id.textview_radar_time);
        TextView opacityLbl = templateView.findViewById(R.id.textview_radar_opacity);
        TextView statusView = templateView.findViewById(R.id.textview_radar_status);
        TextView diagView   = templateView.findViewById(R.id.textview_radar_diagnostics);
        Button   btnShow    = templateView.findViewById(R.id.btn_radar_show);
        Button   btnHide    = templateView.findViewById(R.id.btn_radar_hide);
        Button   btnRecenter= templateView.findViewById(R.id.btn_radar_recenter);

        radarManager.setListener(new com.atakmap.android.weather.overlay.radar
                .RadarOverlayManager.Listener() {
            @Override public void onManifestLoaded(int total, int defIdx) {
                if (frameSeek != null) { frameSeek.setMax(total - 1); frameSeek.setProgress(defIdx); }
                if (statusView != null)
                    statusView.setText(pluginContext.getString(R.string.radar_status_ready, total));
            }
            @Override public void onFrameDisplayed(int idx, String label) {
                if (timeLabel != null) timeLabel.setText(label);
            }
            @Override public void onDiagnosticsUpdated(String info) {
                if (diagView != null) diagView.setText(info);
            }
            @Override public void onError(String msg) {
                // Show the actual error message so the user knows what's wrong
                // (e.g. "Manifest fetch failed: timeout" vs just "Error")
                if (statusView != null) statusView.setText("⚠ " + msg);
                Toast.makeText(pluginContext, "Radar: " + msg, Toast.LENGTH_SHORT).show();
            }
        });

        if (btnShow != null) btnShow.setOnClickListener(v -> {
            // Warn if map is in 3D tilt mode — the View-overlay stays screen-flat
            if (getMapView().getMapTilt() > 5.0) {
                Toast.makeText(pluginContext,
                        "ⓘ Radar overlay is 2D only — disable 3D tilt for geo-locked display",
                        Toast.LENGTH_LONG).show();
            }
            if (statusView != null) statusView.setText(R.string.radar_status_loading);
            radarManager.start();
        });
        if (btnHide != null) btnHide.setOnClickListener(v -> {
            radarManager.stop();
            if (statusView != null) statusView.setText(R.string.radar_status_idle);
            if (timeLabel  != null) timeLabel.setText("—");
            if (diagView   != null) diagView.setText("");
        });
        if (btnRecenter != null) btnRecenter.setOnClickListener(v -> radarManager.refresh());

        if (frameSeek != null) frameSeek.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                        if (u) radarManager.setFrameIndex(p);
                    }
                    @Override public void onStartTrackingTouch(SeekBar sb) {}
                    @Override public void onStopTrackingTouch(SeekBar sb) {}
                });

        if (opacitySeek != null) opacitySeek.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                        radarManager.setOpacity(p);
                        if (opacityLbl != null) opacityLbl.setText(p + "%");
                    }
                    @Override public void onStartTrackingTouch(SeekBar sb) {}
                    @Override public void onStopTrackingTouch(SeekBar sb) {}
                });
    }

    private void initMapTab() {
        btnDropMarker = templateView.findViewById(R.id.btn_drop_weather_marker);
        final TextView pickHint = templateView.findViewById(R.id.textview_pick_hint);

        if (btnDropMarker != null) {
            btnDropMarker.setOnClickListener(v -> {
                if (pickModeActive) {
                    // Second tap = cancel
                    cancelPickMode(btnDropMarker, pickHint);
                } else {
                    // First tap = enter pick mode
                    enterPickMode(btnDropMarker, pickHint);
                }
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
                shareIntent.putExtra(EXTRA_TARGET_UID, buildMarkerUid(lastLocation));
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

        // CRITICAL ORDER: register the tool FIRST, THEN close the dropdown.
        //
        // If closeDropDown() fires before the Tool pushes its listener stack,
        // the touch-release from the dropdown's closing animation can arrive as
        // a MAP_CLICK event at the map centre — that phantom tap would be
        // consumed by the tool as the intended pick point.
        //
        // By calling WeatherPlaceTool.start() first the listener stack is in
        // place before any map touch events can fire from the slide-away.
        // A 200 ms postDelayed for the actual close gives the ToolManager
        // broadcast time to complete before the dropdown geometry changes.
        com.atakmap.android.weather.util.WeatherPlaceTool.start(
                getMapView(),
                com.atakmap.android.weather.util.WeatherPlaceTool.Mode.WEATHER,
                (pickedPoint, mode) -> {
                    // Runs on main thread after user taps map
                    resetPickMode(btn, hint);

                    pendingPickLat   = pickedPoint.getLatitude();
                    pendingPickLon   = pickedPoint.getLongitude();
                    pendingPickPlace = true;

                    weatherViewModel.loadWeather(
                            pickedPoint.getLatitude(),
                            pickedPoint.getLongitude(),
                            LocationSource.MAP_CENTRE);

                    // Reopen the panel on the Map tab
                    Intent reopen = new Intent(SHOW_PLUGIN);
                    reopen.putExtra(EXTRA_REQUESTED_TAB, "conf");
                    com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(reopen);
                });

        // Close the dropdown after a short delay so the tool's listener stack
        // is fully active before the map becomes interactive.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                this::closeDropDown, 200);
    }

    private void cancelPickMode(Button btn, TextView hint) {
        // Cancel the active Tool (restores listener stack, closes TextContainer prompt)
        com.atakmap.android.weather.util.WeatherPlaceTool.cancel(getMapView());
        if (activePicker != null) { activePicker.cancel(); activePicker = null; } // legacy fallback
        resetPickMode(btn, hint);
    }

    private void resetPickMode(Button btn, TextView hint) {
        pickModeActive = false;
        activePicker   = null;
        if (btn  != null) { btn.setText(R.string.map_btn_pick_and_drop); btn.setAlpha(1.0f); }
        if (hint != null) hint.setVisibility(View.GONE);
    }

    // ── Pending auto-place after a point pick ─────────────────────────────────
    // Set in the MapPointPicker callback, consumed by the weather observer.
    private boolean pendingPickPlace = false;
    private double  pendingPickLat   = Double.NaN;
    private double  pendingPickLon   = Double.NaN;

    // ── Tab 2 — Wind tab initialisation ───────────────────────────────────────

    /**
     * Sync the Range + Height seekbars to the values stored in the given slot.
     * Called when the active wind slot changes so each slot has independent
     * range/height settings.
     */
    private void syncWindEffectSeekbarsToSlot(WindProfileViewModel.WindSlot slot) {
        if (slot == null) return;
        windEffectRangeM  = slot.rangeM;
        windEffectHeightM = slot.heightM;

        SeekBar rangeSeek  = templateView.findViewById(R.id.seekbar_wind_range);
        SeekBar heightSeek = templateView.findViewById(R.id.seekbar_wind_height);
        TextView rangeLabel  = templateView.findViewById(R.id.textview_wind_range_value);
        TextView heightLabel = templateView.findViewById(R.id.textview_wind_height_value);

        if (rangeSeek != null) {
            int prog = (int) Math.round(slot.rangeM / 500.0) - 1;
            rangeSeek.setProgress(Math.max(0, Math.min(rangeSeek.getMax(), prog)));
        }
        if (rangeLabel != null)
            rangeLabel.setText(String.format(java.util.Locale.US,
                    windEffectRangeM >= 1000 ? "%.1f km" : "%.0f m",
                    windEffectRangeM >= 1000 ? windEffectRangeM / 1000.0 : windEffectRangeM));

        if (heightSeek != null) {
            int prog = (int) Math.round(slot.heightM / 50.0) - 1;
            heightSeek.setProgress(Math.max(0, Math.min(heightSeek.getMax(), prog)));
        }
        if (heightLabel != null)
            heightLabel.setText(String.format(java.util.Locale.US, "%.0f m", windEffectHeightM));
    }

    private void initWindTab() {
        // ── Range SeekBar (0.5–10 km, step 0.5 km) ───────────────────────────
        final TextView rangeLabel  = templateView.findViewById(R.id.textview_wind_range_value);
        final SeekBar  rangeSeek   = templateView.findViewById(R.id.seekbar_wind_range);
        if (rangeSeek != null) {
            rangeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                    windEffectRangeM = (p + 1) * 500.0; // step 0.5 km, min 0.5 km
                    if (rangeLabel != null)
                        rangeLabel.setText(String.format(Locale.US,
                                windEffectRangeM >= 1000 ? "%.1f km" : "%.0f m",
                                windEffectRangeM >= 1000 ? windEffectRangeM / 1000.0 : windEffectRangeM));
                    // Persist to active slot
                    WindProfileViewModel.WindSlot activeSlot = windViewModel.getActiveWindSlot();
                    if (activeSlot != null) activeSlot.rangeM = windEffectRangeM;
                    // Live update: stretch/shrink existing cones without full redraw
                    if (windEffectActive && windEffectShape != null && !Double.isNaN(lastWindLat)) {
                        final String suffix = WindEffectShape.uidSuffix(lastWindLat, lastWindLon, lastWindIsSelf);
                        windEffectShape.updateRange(suffix, lastWindLat, lastWindLon,
                                windEffectRangeM, currentFrameList());
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                // Full redraw on release to reconcile any edge-cases
                @Override public void onStopTrackingTouch(SeekBar sb) { redrawIfActive(); }
            });
            rangeSeek.setProgress(rangeSeek.getProgress());
        }

        // ── Height SeekBar (50–2000 m, step 50 m) ────────────────────────────
        final TextView heightLabel = templateView.findViewById(R.id.textview_wind_height_value);
        final SeekBar  heightSeek  = templateView.findViewById(R.id.seekbar_wind_height);
        if (heightSeek != null) {
            heightSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                    windEffectHeightM = (p + 1) * 50.0; // step 50 m, min 50 m
                    if (heightLabel != null)
                        heightLabel.setText(String.format(Locale.US, "%.0f m", windEffectHeightM));
                    // Persist to active slot
                    WindProfileViewModel.WindSlot activeSlot = windViewModel.getActiveWindSlot();
                    if (activeSlot != null) activeSlot.heightM = windEffectHeightM;
                    // Live update: show/hide tiers above/below the new ceiling
                    if (windEffectActive && windEffectShape != null && !Double.isNaN(lastWindLat)) {
                        final String suffix = WindEffectShape.uidSuffix(lastWindLat, lastWindLon, lastWindIsSelf);
                        windEffectShape.updateHeightCeiling(suffix, windEffectHeightM, currentFrameList());
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                // Full redraw on release to reconcile visibility + label
                @Override public void onStopTrackingTouch(SeekBar sb) { redrawIfActive(); }
            });
            heightSeek.setProgress(heightSeek.getProgress());
        }

        // ── Draw Wind Effect button ───────────────────────────────────────────
        Button btnDraw = templateView.findViewById(R.id.btn_draw_wind_effect);
        if (btnDraw != null) {
            btnDraw.setOnClickListener(v -> drawWindEffect());
        }

        // ── Clear Wind Effect button ──────────────────────────────────────────
        Button btnClear = templateView.findViewById(R.id.btn_clear_wind_effect);
        if (btnClear != null) {
            btnClear.setOnClickListener(v -> {
                if (windEffectShape != null) windEffectShape.removeAll();
                windEffectActive = false;
                Toast.makeText(pluginContext, R.string.wind_effect_cleared, Toast.LENGTH_SHORT).show();
            });
        }

        // ── Drop Wind Marker button ───────────────────────────────────────────
        Button btnDropWindMarker = templateView.findViewById(R.id.btn_drop_wind_marker);
        TextView windMarkerCoordLabel = templateView.findViewById(R.id.textview_wind_marker_coord);
        if (btnDropWindMarker != null) {
            btnDropWindMarker.setOnClickListener(v -> {
                WindProfileViewModel.WindSlot activeSlot = windViewModel.getActiveWindSlot();
                if (activeSlot != null && windMarkerManager != null) {
                    // Use the active slot's lat/lon — that's the point wind was fetched for
                    LocationSnapshot slotLocation = new LocationSnapshot(
                            activeSlot.lat, activeSlot.lon,
                            activeSlot.label, LocationSource.MAP_CENTRE);
                    WeatherModel wx = (lastWeather != null) ? lastWeather : null;
                    // We need a WeatherModel for the marker - use lastWeather if it's close,
                    // otherwise use a minimal placeholder from the slot's profile surface data
                    if (wx == null && activeSlot.profiles != null && !activeSlot.profiles.isEmpty()) {
                        wx = buildWeatherModelFromProfile(activeSlot);
                    }
                    if (wx != null) {
                        windMarkerManager.placeMarker(slotLocation, wx);
                        lastWindLat    = activeSlot.lat;
                        lastWindLon    = activeSlot.lon;
                        lastWindIsSelf = false;
                        // Register slot for multi-slot seekbar redraw
                        String suffix = WindEffectShape.uidSuffix(activeSlot.lat, activeSlot.lon, false);
                        placedWindSlots.put(suffix, activeSlot);
                        Toast.makeText(pluginContext,
                                String.format(java.util.Locale.US,
                                        "Wind marker dropped at %.4f°, %.4f°",
                                        activeSlot.lat, activeSlot.lon),
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(pluginContext, R.string.map_marker_no_data, Toast.LENGTH_SHORT).show();
                    }
                } else if (lastWeather != null && lastLocation != null && windMarkerManager != null) {
                    // Fallback to last weather location if no slot selected
                    windMarkerManager.placeMarker(lastLocation, lastWeather);
                    lastWindLat   = lastLocation.getLatitude();
                    lastWindLon   = lastLocation.getLongitude();
                    lastWindIsSelf = lastLocation.getSource() == LocationSource.SELF_MARKER;
                    // No slot to register in placedWindSlots — single-point fallback
                    Toast.makeText(pluginContext, "Wind marker dropped", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(pluginContext, R.string.map_marker_no_data, Toast.LENGTH_SHORT).show();
                }
            });
        }
        // Update coord label when active slot changes (wired from obsActiveWindSlot below,
        // but also update it here on initWindTab to show the initial state)
        updateWindMarkerCoordLabel(windMarkerCoordLabel);

        // ── Wind hour seekbar: update windHourIndex for live prism redraw ──
        final SeekBar windHourSeek = templateView.findViewById(R.id.wind_seekbar);
        if (windHourSeek != null) {
            windHourSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    windHourIndex = p;
                    // Update chart UI immediately on every tick
                    windProfileView.onHourChanged(p);
                    // Redraw shapes live while dragging — same call as on release
                    if (fromUser) redrawIfActive();
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb)  { redrawIfActive(); }
            });
        }

        // ── WIND source spinner (per-slot source selector) ─────────────────
        wireWindSourceSpinner();

        // ── Initial empty-state: no slots loaded yet ───────────────────────
        updateWindEmptyState(windViewModel.getSlotList());
    }

    /** Show/hide the "no slots" empty hint and source selector row based on slot list. */
    private void updateWindEmptyState(java.util.List<WindProfileViewModel.WindSlot> slots) {
        boolean hasSlots = slots != null && !slots.isEmpty();
        android.view.View emptyHint   = templateView.findViewById(R.id.textview_wind_empty);
        android.view.View sourceRow   = templateView.findViewById(R.id.wind_source_row);
        android.view.View chartFrame  = templateView.findViewById(R.id.wind_chart_frame);
        android.view.View hourRow     = templateView.findViewById(R.id.wind_seekbar) != null
                ? ((android.view.View) templateView.findViewById(R.id.wind_seekbar).getParent()) : null;
        if (emptyHint  != null) emptyHint.setVisibility(hasSlots ? View.GONE    : View.VISIBLE);
        if (sourceRow  != null) sourceRow.setVisibility(hasSlots ? View.VISIBLE : View.GONE);
        if (chartFrame != null) chartFrame.setVisibility(hasSlots ? View.VISIBLE : View.GONE);
        if (hourRow    != null) hourRow.setVisibility(hasSlots ? View.VISIBLE : View.GONE);
    }

    /**
     * Wire the WIND tab's per-slot source spinner.
     * Changing it overwrites the active slot's sourceId and re-fetches wind data.
     */
    private void wireWindSourceSpinner() {
        android.widget.Spinner spinner = templateView.findViewById(R.id.spinner_wind_source);
        if (spinner == null) return;

        WeatherSourceManager mgr = WeatherSourceManager.getInstance(appContext);
        java.util.List<WeatherSourceManager.SourceEntry> entries = mgr.getAvailableEntries();

        ArrayAdapter<WeatherSourceManager.SourceEntry> adapter =
                makeDarkSpinnerAdapter(entries);
        spinner.setAdapter(adapter);

        // Set selection to active source
        spinner.setSelection(mgr.getActiveSourceIndex(), false);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                WeatherSourceManager.SourceEntry entry = entries.get(pos);
                WindProfileViewModel.WindSlot activeSlot = windViewModel.getActiveWindSlot();
                if (activeSlot == null) {
                    // No slot yet — just change the global default source
                    mgr.setActiveSourceId(entry.sourceId);
                    return;
                }
                // Change the slot's source and re-fetch
                activeSlot.sourceId = entry.sourceId;
                lastBoundSourceId   = null; // force re-bind
                Toast.makeText(pluginContext,
                        "Re-fetching wind for slot using " + entry.displayName,
                        Toast.LENGTH_SHORT).show();
                windViewModel.refetchSlot(windViewModel.getActiveSlotIndex(), entry.sourceId);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    /**
     * Draw the wind cone + range-height box for the last placed wind marker.
     * Requires a wind marker to have been placed first.
     */
    private void drawWindEffect() {
        if (windEffectShape == null) return;

        if (Double.isNaN(lastWindLat) || lastWeather == null) {
            Toast.makeText(pluginContext, R.string.wind_effect_no_marker, Toast.LENGTH_SHORT).show();
            return;
        }

        final String suffix = WindEffectShape.uidSuffix(lastWindLat, lastWindLon, lastWindIsSelf);

        // Surface fallback (used when no profile is loaded yet)
        double surfaceSpeed = lastWeather.getWindSpeed();
        double surfaceDir   = lastWeather.getWindDirection();

        // Build a single-frame list from the currently selected hour so
        // WindEffectShape receives exactly one WindProfileModel — the hour
        // the user has scrubbed to.  Each AltitudeEntry carries its own
        // speed and direction, giving per-cone colours and pointing.
        java.util.List<com.atakmap.android.weather.domain.model.WindProfileModel>
                frameList = null;
        if (lastWindProfiles != null && !lastWindProfiles.isEmpty()) {
            int idx = Math.min(windHourIndex, lastWindProfiles.size() - 1);
            com.atakmap.android.weather.domain.model.WindProfileModel frame =
                    lastWindProfiles.get(idx);
            frameList = java.util.Collections.singletonList(frame);

            // Also update surface fallback from the 10m tier of this frame
            if (frame.getAltitudes() != null && !frame.getAltitudes().isEmpty()) {
                surfaceSpeed = frame.getAltitudes().get(0).windSpeed;
                surfaceDir   = frame.getAltitudes().get(0).windDirection;
            }
        }

        windEffectShape.place(
                lastWindLat, lastWindLon,
                surfaceSpeed,
                surfaceDir,
                windEffectRangeM,
                windEffectHeightM,
                suffix,
                frameList);
        windEffectActive = true;

        String toastMsg = (frameList != null)
                ? pluginContext.getString(R.string.wind_effect_drawn)
                : pluginContext.getString(R.string.wind_effect_drawn_no_profile);
        Toast.makeText(pluginContext, toastMsg, Toast.LENGTH_SHORT).show();
    }

    /**
     * Redraw wind cones for ALL slots that currently have a placed wind marker.
     * Called whenever the hour seekbar moves so every slot's cone animates together.
     *
     * For each registered slot we build a single-frame list from the slot's profile
     * at {@code windHourIndex} and call {@code WindEffectShape.place()}.  Slots
     * that have no profiles yet (still loading) are silently skipped.
     *
     * Note: {@code lastWindLat/Lon} is still kept in sync with the ACTIVE slot for
     * the single-shot {@link #drawWindEffect()} call path.
     */
    private void redrawIfActive() {
        if (!windEffectActive || windEffectShape == null) return;

        // Redraw every registered slot
        if (!placedWindSlots.isEmpty()) {
            for (java.util.Map.Entry<String, WindProfileViewModel.WindSlot> entry
                    : placedWindSlots.entrySet()) {
                String suffix = entry.getKey();
                WindProfileViewModel.WindSlot slot = entry.getValue();
                // Refresh the slot reference in case it was updated (profiles loaded after placement)
                WindProfileViewModel.WindSlot live = findSlotByLatLon(slot.lat, slot.lon);
                if (live != null && live.profiles != null && !live.profiles.isEmpty()) {
                    int idx = Math.min(windHourIndex, live.profiles.size() - 1);
                    java.util.List<com.atakmap.android.weather.domain.model.WindProfileModel>
                            frameList = java.util.Collections.singletonList(live.profiles.get(idx));
                    double surfaceSpeed = 0, surfaceDir = 0;
                    com.atakmap.android.weather.domain.model.WindProfileModel.AltitudeEntry surf =
                            live.profiles.get(idx).getAltitudes() != null
                                    && !live.profiles.get(idx).getAltitudes().isEmpty()
                                    ? live.profiles.get(idx).getAltitudes().get(0) : null;
                    if (surf != null) { surfaceSpeed = surf.windSpeed; surfaceDir = surf.windDirection; }
                    windEffectShape.place(live.lat, live.lon,
                            surfaceSpeed, surfaceDir,
                            windEffectRangeM, windEffectHeightM,
                            suffix, frameList);
                }
            }
        } else if (lastWeather != null && !Double.isNaN(lastWindLat)) {
            // Legacy: single-point mode (no slot placed via button)
            drawWindEffect();
        }
    }

    /** Find the live slot matching a lat/lon from the ViewModel's current slot list. */
    /**
     * Updates the "1c. Active slot data-source badge" TextView below the slot strip.
     * Shows e.g. "AWC METAR  10/760/1500/3000m" so the user always knows which
     * data source is driving the current wind chart.
     */
    private void updateWindSourceBadge(WindProfileViewModel.WindSlot slot) {
        if (templateView == null) return;
        android.widget.TextView badge = templateView.findViewById(R.id.textview_wind_source_badge);
        if (badge == null) return;
        if (slot == null || slot.profiles == null || slot.profiles.isEmpty()) {
            badge.setVisibility(View.GONE);
            return;
        }
        String srcDisplay = slot.sourceId;
        com.atakmap.android.weather.data.remote.IWeatherRemoteSource srcObj =
                WeatherSourceManager.getInstance(appContext).getSourceById(slot.sourceId);
        if (srcObj != null) srcDisplay = srcObj.getDisplayName();
        String tierStr = buildAltitudeTierLabel(slot.profiles);
        String text = "Data source: " + srcDisplay
                + (tierStr.isEmpty() ? "" : "  ·  Altitudes: " + tierStr);
        badge.setText(text);
        badge.setVisibility(View.VISIBLE);
    }

    private WindProfileViewModel.WindSlot findSlotByLatLon(double lat, double lon) {
        if (windViewModel == null) return null;
        for (WindProfileViewModel.WindSlot s : windViewModel.getSlotList()) {
            if (Math.abs(s.lat - lat) < 1e-6 && Math.abs(s.lon - lon) < 1e-6) return s;
        }
        return null;
    }

    /**
     * Builds a compact string listing the altitude tiers present in a profile list.
     * Example: "10/80/120/180m" for Open-Meteo, "10/760/1500m" for METAR.
     */
    private static String buildAltitudeTierLabel(
            java.util.List<com.atakmap.android.weather.domain.model.WindProfileModel> profiles) {
        if (profiles == null || profiles.isEmpty()) return "";
        com.atakmap.android.weather.domain.model.WindProfileModel frame = profiles.get(0);
        if (frame.getAltitudes() == null || frame.getAltitudes().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (com.atakmap.android.weather.domain.model.WindProfileModel.AltitudeEntry e
                : frame.getAltitudes()) {
            if (sb.length() > 0) sb.append("/");
            int alt = e.altitudeMeters;
            sb.append(alt);
        }
        sb.append("m");
        return sb.toString();
    }

    /**
     * Returns a singletonList of the currently selected hour's WindProfileModel
     * frame, or null if no profiles are loaded.
     * Used by the live-mutation seekbar callbacks so they share the same frame
     * selection logic as drawWindEffect().
     */
    private java.util.List<com.atakmap.android.weather.domain.model.WindProfileModel>
    currentFrameList() {
        if (lastWindProfiles == null || lastWindProfiles.isEmpty()) return null;
        int idx = Math.min(windHourIndex, lastWindProfiles.size() - 1);
        return java.util.Collections.singletonList(lastWindProfiles.get(idx));
    }

    // ── LiveData observers ─────────────────────────────────────────────────────

    @SuppressLint("SetTextI18n")
    private void observeViewModels() {

        obsCurrentWeather = state -> {
            if (state.isLoading()) {
                currentWeatherView.showLoading();
            } else if (state.isSuccess() && state.getData() != null) {
                WeatherModel w = state.getData();
                lastWeather = w;
                currentWeatherView.bindCurrentWeather(w, w.getRequestTimestamp());
                updateFltCatBadge(w);
                if (lastLocation != null) updateChartLocationHeader(lastLocation);

                // ── Auto-place after point-pick ──────────────────────────────
                if (pendingPickPlace) {
                    pendingPickPlace = false;
                    // ALWAYS use the picked coordinates — never lastLocation here.
                    // lastLocation is the location of the PREVIOUS weather load
                    // (e.g. self-marker). pendingPickLat/Lon are the coordinates the
                    // user actually tapped, which is what we want for the marker.
                    com.atakmap.android.weather.domain.model.LocationSnapshot placeSnap = null;
                    if (!Double.isNaN(pendingPickLat)) {
                        placeSnap = new com.atakmap.android.weather.domain.model.LocationSnapshot(
                                pendingPickLat, pendingPickLon,
                                null,
                                com.atakmap.android.weather.domain.model.LocationSource.MAP_CENTRE);
                    } else if (lastLocation != null) {
                        placeSnap = lastLocation; // genuine fallback
                    }
                    if (placeSnap != null) {
                        markerManager.placeMarker(placeSnap, w);
                        updateMarkerStatus(placeSnap);
                    }
                    pendingPickLat = Double.NaN;
                    pendingPickLon = Double.NaN;
                }

            } else if (state.isError()) {
                currentWeatherView.showError(state.getErrorMessage());
                pendingPickPlace = false;
            }
        };
        weatherViewModel.getCurrentWeather().observeForever(obsCurrentWeather);

        obsActiveLocation = snapshot -> {
            if (snapshot != null) {
                lastLocation = snapshot;
                currentWeatherView.bindLocation(snapshot);
                updateChartLocationHeader(snapshot);
            }
        };
        weatherViewModel.getActiveLocation().observeForever(obsActiveLocation);

        obsDailyForecast = state -> {
            if (state.isSuccess() && state.getData() != null)
                dailyForecastView.bind(state.getData());
        };
        weatherViewModel.getDailyForecast().observeForever(obsDailyForecast);

        obsHourlyForecast = state -> {
            if (state.isSuccess() && state.getData() != null) {
                hourlyCache = state.getData();
                final int maxHour = hourlyCache.size() - 1;
                // chartOverlaySeekBar (inside chart_frame) is the sole scrubber now.
                if (chartOverlaySeekBar != null) {
                    chartOverlaySeekBar.setMax(maxHour);
                    chartOverlaySeekBar.setProgress(0);
                }
                if (chartView != null) { chartView.setData(hourlyCache); chartView.invalidate(); }
                triggerComparison();
            }
        };
        weatherViewModel.getHourlyForecast().observeForever(obsHourlyForecast);

        obsSelectedHour = index -> {
            if (index == null) return;
            if (chartView != null) { chartView.setSelectedIndex(index); updateChartReadouts(index); }
            if (hourlyCache != null && index >= 0 && index < hourlyCache.size()) {
                HourlyEntryModel entry = hourlyCache.get(index);
                String iso = entry.getIsoTime(); // "2024-07-27T14:00"
                String dayOfWeek = isoDayOfWeek(iso);  // "Saturday"
                String label = "+" + index + "h  " + dayOfWeek + "  " + iso.replace("T", " ");
                currentWeatherView.bindHourlyEntry(entry, label);
                // Also update chart header timestamp with day name
                TextView tsLabel = templateView.findViewById(R.id.chart_timestamp_label);
                if (tsLabel != null) tsLabel.setText(dayOfWeek + "  " + iso.replace("T", " "));
            }
        };
        weatherViewModel.getSelectedHour().observeForever(obsSelectedHour);

        obsErrorMessage = msg -> {
            if (msg != null) Toast.makeText(pluginContext, msg, Toast.LENGTH_SHORT).show();
        };
        weatherViewModel.getErrorMessage().observeForever(obsErrorMessage);

        obsCacheBadge = badge -> {
            TextView badgeView = templateView.findViewById(R.id.textview_cache_badge);
            if (badgeView == null) return;
            if (badge == null || badge.isEmpty()) {
                badgeView.setVisibility(View.GONE);
            } else {
                badgeView.setText(badge);
                badgeView.setVisibility(View.VISIBLE);
            }
        };
        weatherViewModel.getCacheBadge().observeForever(obsCacheBadge);

        obsWindProfile = state -> {
            if (state.isLoading()) {
                windProfileView.showLoading();
            } else if (state.isSuccess() && state.getData() != null) {
                lastWindProfiles = state.getData();
                windProfileView.bind(state.getData());
            } else if (state.isError()) {
                windProfileView.showError(state.getErrorMessage());
            }
        };
        windViewModel.getWindProfile().observeForever(obsWindProfile);

        // Multi-slot: rebuild tab strip whenever slot list changes
        obsWindSlots = slots -> {
            int activeIdx = windViewModel.getActiveSlotIndex();
            windProfileView.rebuildSlotTabs(slots, activeIdx);

            // Show/hide empty state and source selector row based on whether slots exist
            updateWindEmptyState(slots);

            // Sync WIND tab source spinner to active slot's source (if any)
            if (slots != null && activeIdx >= 0 && activeIdx < slots.size()) {
                String slotSrc = slots.get(activeIdx).sourceId;
                android.widget.Spinner windSpinner = templateView.findViewById(R.id.spinner_wind_source);
                if (windSpinner != null && windSpinner.getAdapter() != null) {
                    WeatherSourceManager mgr = WeatherSourceManager.getInstance(appContext);
                    int idx2 = mgr.getIndexForSourceId(slotSrc);
                    if (idx2 >= 0 && windSpinner.getSelectedItemPosition() != idx2) {
                        windSpinner.setSelection(idx2, false);
                    }
                }
            }

            // When profiles arrive asynchronously for the ACTIVE slot, the activeSlotLive
            // index doesn't change (only slotsLive fires). We must therefore update the
            // chart altitudes + source label here whenever the active slot's profiles change.
            if (slots != null && activeIdx >= 0 && activeIdx < slots.size()) {
                WindProfileViewModel.WindSlot activeSlot = slots.get(activeIdx);
                if (activeSlot.profiles != null && !activeSlot.profiles.isEmpty()) {
                    // Force re-bind if: new profiles, slot switched, or source/altitudes changed
                    boolean slotSwitched    = (activeIdx != lastActiveSlotIdx);
                    boolean profilesChanged = (lastWindProfiles == null)
                            || (lastWindProfiles != activeSlot.profiles);
                    boolean sourceChanged   = !activeSlot.sourceId.equals(
                            lastBoundSourceId != null ? lastBoundSourceId : "");
                    if (profilesChanged || slotSwitched || sourceChanged) {
                        lastWindProfiles  = activeSlot.profiles;
                        lastActiveSlotIdx = activeIdx;
                        lastBoundSourceId = activeSlot.sourceId;
                        com.atakmap.android.weather.presentation.view.WindChartView wc =
                                windProfileView != null ? windProfileView.getWindChart() : null;
                        if (wc != null) {
                            // Always re-detect altitude tiers — critical when switching
                            // between Open-Meteo (10/80/120/180 m) and METAR (10/760/1500/3000/4200 m)
                            wc.setAltitudesFromProfiles(activeSlot.profiles);
                            String srcDisplay = activeSlot.sourceId;
                            com.atakmap.android.weather.data.remote.IWeatherRemoteSource srcObj =
                                    WeatherSourceManager.getInstance(appContext)
                                            .getSourceById(activeSlot.sourceId);
                            if (srcObj != null) srcDisplay = srcObj.getDisplayName();
                            String tierStr = buildAltitudeTierLabel(activeSlot.profiles);
                            wc.setSourceLabel(tierStr.isEmpty() ? srcDisplay
                                    : srcDisplay + "  " + tierStr);
                        }
                        // bind() also calls setAltitudesFromProfiles — redundant but harmless
                        windProfileView.bind(activeSlot.profiles);
                        updateWindSourceBadge(activeSlot);
                    }
                }
            }
        };

        windViewModel.getSlots().observeForever(obsWindSlots);

        // Multi-slot: switch Range/Height seekbars to active slot's values
        obsActiveWindSlot = activeIdx -> {
            windProfileView.rebuildSlotTabs(windViewModel.getSlotList(), activeIdx);
            // Refresh the coord label below Drop Wind Marker
            TextView coordLabel = templateView.findViewById(R.id.textview_wind_marker_coord);
            updateWindMarkerCoordLabel(coordLabel);

            if (activeIdx == null || activeIdx < 0) return;
            java.util.List<WindProfileViewModel.WindSlot> slots = windViewModel.getSlotList();
            if (activeIdx >= slots.size()) return;
            WindProfileViewModel.WindSlot slot = slots.get(activeIdx);
            // Sync per-slot Range + Height seekbars to active slot's saved values
            syncWindEffectSeekbarsToSlot(slot);
            // Sync lastWindLat/Lon for 3D drawing
            lastWindLat  = slot.lat;
            lastWindLon  = slot.lon;
            lastWindIsSelf = false;

            // Zoom map to the active slot's location when switching tabs/slots
            try {
                getMapView().getMapController().panTo(
                        new com.atakmap.coremap.maps.coords.GeoPoint(slot.lat, slot.lon), true);
            } catch (Exception ignored) {}

            // Sync WIND tab source spinner to the new active slot's source
            android.widget.Spinner windSpinner = templateView.findViewById(R.id.spinner_wind_source);
            if (windSpinner != null && windSpinner.getAdapter() != null) {
                int srcIdx = WeatherSourceManager.getInstance(appContext)
                        .getIndexForSourceId(slot.sourceId);
                if (srcIdx >= 0 && windSpinner.getSelectedItemPosition() != srcIdx) {
                    windSpinner.setSelection(srcIdx, false);
                }
            }

            if (slot.profiles != null) {
                lastWindProfiles  = slot.profiles;
                lastActiveSlotIdx = activeIdx;
                lastBoundSourceId = slot.sourceId;
                // Auto-detect altitude tiers from the actual data (METAR vs Open-Meteo differ)
                com.atakmap.android.weather.presentation.view.WindChartView wc =
                        windProfileView != null ? windProfileView.getWindChart() : null;
                if (wc != null) {
                    wc.setAltitudesFromProfiles(slot.profiles);
                    // Show the source's human-readable display name in the chart corner
                    String srcDisplay = slot.sourceId;
                    com.atakmap.android.weather.data.remote.IWeatherRemoteSource srcObj =
                            WeatherSourceManager.getInstance(appContext).getSourceById(slot.sourceId);
                    if (srcObj != null) srcDisplay = srcObj.getDisplayName();
                    // Build "Open-Meteo GFS · 10m/80m/120m" style label
                    String tierStr = buildAltitudeTierLabel(slot.profiles);
                    wc.setSourceLabel(tierStr.isEmpty() ? srcDisplay : srcDisplay + "  " + tierStr);
                }
                windProfileView.bind(slot.profiles);
                updateWindSourceBadge(slot);
            }
        };

        windViewModel.getActiveSlot().observeForever(obsActiveWindSlot);

        obsComparison = state -> {
            if (comparisonView == null) return;
            if (state.isLoading())                                    comparisonView.showLoading();
            else if (state.isSuccess() && state.getData() != null)    comparisonView.bind(state.getData());
            else if (state.isError())                                  comparisonView.showError(state.getErrorMessage());
        };
        weatherViewModel.getComparison().observeForever(obsComparison);

        obsSelfLocation = snapshot -> {
            if (snapshot != null && comparisonView != null) comparisonView.bindSelfSnapshot(snapshot);
        };
        weatherViewModel.getSelfLocation().observeForever(obsSelfLocation);

        obsCenterLocation = snapshot -> {
            if (snapshot != null && comparisonView != null) comparisonView.bindCenterSnapshot(snapshot);
        };
        weatherViewModel.getCenterLocation().observeForever(obsCenterLocation);
    }

    // ── Data triggers ──────────────────────────────────────────────────────────

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
        boolean hasGps = !(selfLat == 0.0 && selfLon == 0.0);
        if (!hasGps) Toast.makeText(pluginContext, R.string.no_gps_using_map_centre, Toast.LENGTH_SHORT).show();
        weatherViewModel.loadWeatherWithFallback(selfLat, selfLon, cenLat, cenLon);
    }

    private void triggerComparison() {
        double selfLat = 0.0, selfLon = 0.0;
        try {
            if (getMapView().getSelfMarker() != null) {
                selfLat = getMapView().getSelfMarker().getPoint().getLatitude();
                selfLon = getMapView().getSelfMarker().getPoint().getLongitude();
            }
        } catch (Exception e) {
            com.atakmap.coremap.log.Log.w(TAG, "triggerComparison getSelfMarker() threw: " + e.getMessage());
        }
        double cenLat  = getMapView().getCenterPoint().get().getLatitude();
        double cenLon  = getMapView().getCenterPoint().get().getLongitude();
        if (selfLat == 0.0 && selfLon == 0.0) { selfLat = cenLat; selfLon = cenLon; }
        weatherViewModel.loadComparison(selfLat, selfLon, cenLat, cenLon);
    }

    // ── Source Spinner ────────────────────────────────────────────────────────

    /**
     * The source selector lives in the PARM tab (spinner_parm_source).
     * This stub kept for call-site compatibility — delegates to wireParmSourceSpinner().
     */
    private void wireSourceSpinner() {
        // The spinner was always in tab_parameters.xml.
        // All logic now lives in wireParmSourceSpinner() which uses the
        // correctly-named IDs (spinner_parm_source, badge_parm_flt_cat, etc.).
    }


    /**
     * Wire the PARM tab's own source spinner (spinner_parm_source).
     *
     * This is a SEPARATE spinner from the CONF tab's spinner_weather_source.
     * Having duplicate IDs caused the wrong spinner to be found by wireSourceSpinner(),
     * leaving the PARM tab's spinner unresponsive to source changes.
     *
     * The PARM spinner only updates ParametersView — it does NOT change the active
     * data source for weather/wind fetches (that's the CONF spinner's job).
     * Both spinners stay in sync via the shared WeatherSourceManager active source ID.
     */
    private void wireParmSourceSpinner() {
        android.widget.Spinner spinner = templateView.findViewById(R.id.spinner_parm_source);
        if (spinner == null) return;

        WeatherSourceManager mgr = WeatherSourceManager.getInstance(appContext);
        java.util.List<WeatherSourceManager.SourceEntry> entries = mgr.getAvailableEntries();

        // Use appContext to avoid BadTokenException in plugin context
        ArrayAdapter<WeatherSourceManager.SourceEntry> adapter =
                makeDarkSpinnerAdapter(entries);
        spinner.setAdapter(adapter);
        spinner.setSelection(mgr.getActiveSourceIndex(), false);

        // Init fltCatBadge from PARM tab (renamed from badge_flt_cat)
        fltCatBadge = templateView.findViewById(R.id.badge_parm_flt_cat);

        // Status label in PARM tab
        android.widget.TextView statusLabel =
                templateView.findViewById(R.id.textview_parm_source_status);
        if (statusLabel != null && mgr.getActiveSource() != null) {
            updateSourceStatusLabel(statusLabel, mgr.getActiveSource());
        }

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                WeatherSourceManager.SourceEntry entry = entries.get(pos);
                mgr.setActiveSourceId(entry.sourceId);
                if (networkRepo != null) networkRepo.setActiveSource(entry.sourceId);
                if (cachingRepo  != null) cachingRepo.clearWindCache();

                // Update PARM tab status label
                if (statusLabel != null) {
                    updateSourceStatusLabel(statusLabel, mgr.getActiveSource());
                }

                // Rebuild parameter list for the newly selected source
                rebuildParmsForSource(entry.sourceId);

                if (fltCatBadge != null) fltCatBadge.setVisibility(View.GONE);
                Toast.makeText(pluginContext,
                        "Source: " + entry.displayName + " — parameters updated",
                        Toast.LENGTH_SHORT).show();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    /**
     * Rebuild the PARM tab parameter lists for the given source ID.
     * Prefers JSON/YAML definition; falls back to the enum getSupportedParameters().
     * Also updates the description label.
     */
    private void rebuildParmsForSource(String sourceId) {
        if (parametersView == null) return;
        android.widget.TextView descLabel =
                templateView.findViewById(R.id.textview_parm_source_desc);
        com.atakmap.android.weather.data.remote.WeatherSourceDefinition def =
                com.atakmap.android.weather.data.remote.SourceDefinitionLoader
                        .loadAll(pluginContext).get(sourceId);
        if (def != null && !def.hourlyParams.isEmpty()) {
            parametersView.setDefinitionParams(sourceId,
                    def.hourlyParams, def.dailyParams, def.currentParams);
            if (descLabel != null) {
                if (def.description != null && !def.description.isEmpty()) {
                    descLabel.setText(def.description);
                    descLabel.setVisibility(View.VISIBLE);
                } else {
                    descLabel.setVisibility(View.GONE);
                }
            }
        } else {
            // No JSON definition — use the source's own enum parameter list
            WeatherSourceManager mgr = WeatherSourceManager.getInstance(appContext);
            com.atakmap.android.weather.data.remote.IWeatherRemoteSource src =
                    mgr.getSourceById(sourceId);
            if (src != null) {
                parametersView.setAvailableParameters(src.getSupportedParameters());
            }
            if (descLabel != null) descLabel.setVisibility(View.GONE);
        }
    }

    /**
     * Create an ArrayAdapter for ATAK plugin spinners.
     *
     * ATAK's dark theme means:
     *   collapsed spinner  → dark/black background  → needs WHITE text
     *   expanded dropdown  → white popup background  → needs DARK (#111) text
     *
     * Using android.R.layout.simple_spinner_item with the system default resolves
     * to white text in both views (invisible in the white popup).  We override
     * getDropDownView() to force dark text in the popup only.
     */
    private <T> ArrayAdapter<T> makeDarkSpinnerAdapter(java.util.List<T> items) {
        return new ArrayAdapter<T>(appContext,
                android.R.layout.simple_spinner_item, items) {
            @Override
            public android.view.View getView(int pos, android.view.View conv,
                                             android.view.ViewGroup parent) {
                android.view.View v = super.getView(pos, conv, parent);
                // Collapsed spinner sits on ATAK's dark background → white text
                if (v instanceof android.widget.TextView) {
                    ((android.widget.TextView) v).setTextColor(
                            android.graphics.Color.WHITE);
                }
                return v;
            }
            @Override
            public android.view.View getDropDownView(int pos, android.view.View conv,
                                                     android.view.ViewGroup parent) {
                android.view.View v = super.getDropDownView(pos, conv, parent);
                // Dropdown popup has a light/white background → dark text
                if (v instanceof android.widget.TextView) {
                    ((android.widget.TextView) v).setTextColor(
                            android.graphics.Color.parseColor("#111111"));
                }
                return v;
            }
        };
    }

    /** Colour-coded flight-category chip updated on every successful METAR fetch. */
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

    private void updateSourceStatusLabel(TextView tv,
                                         com.atakmap.android.weather.data.remote.IWeatherRemoteSource src) {
        if (tv == null || src == null) return;
        tv.setText(src.getDisplayName() + "  |  " + src.getSupportedParameters().size() + " parameters");
    }

    // ── Chart helpers ──────────────────────────────────────────────────────────

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

    private void updateChartReadouts(int index) {
        if (chartView == null) return;
        TextView valTemp     = templateView.findViewById(R.id.chart_val_temp);
        TextView valHumidity = templateView.findViewById(R.id.chart_val_humidity);
        TextView valWind     = templateView.findViewById(R.id.chart_val_wind);
        TextView valPressure = templateView.findViewById(R.id.chart_val_pressure);
        TextView hourLabel   = templateView.findViewById(R.id.chart_hour_label);
        // Show "Monday 14:00" instead of "+14h" in the readout row
        if (hourLabel != null && hourlyCache != null
                && index >= 0 && index < hourlyCache.size()) {
            String iso = hourlyCache.get(index).getIsoTime(); // "2024-07-27T14:00"
            String dayName  = isoDayOfWeek(iso);
            String timeStr  = iso.length() >= 16 ? iso.substring(11, 16) : iso;
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

    private String buildMarkerUid(LocationSnapshot snapshot) {
        if (snapshot.getSource() == LocationSource.SELF_MARKER) return "wx_self";
        return String.format(Locale.US, "wx_centre_%.4f_%.4f",
                snapshot.getLatitude(), snapshot.getLongitude());
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
        if (tsLabel != null && lastWeather != null) {
            // Show day-of-week alongside the request timestamp
            String ts      = lastWeather.getRequestTimestamp();
            String dayName = isoDayOfWeek(ts);
            tsLabel.setText(dayName.isEmpty() ? ts : dayName + "  " + ts);
        }
    }

    /** Update the small coord label below the Drop Wind Marker button. */
    private void updateWindMarkerCoordLabel(TextView label) {
        if (label == null) return;
        WindProfileViewModel.WindSlot slot = windViewModel.getActiveWindSlot();
        if (slot == null) {
            label.setText(R.string.wind_marker_no_slot);
        } else {
            label.setText(String.format(java.util.Locale.US,
                    "Active slot: %.4f°N  %.4f°E%s",
                    slot.lat, slot.lon,
                    slot.loading ? "  (loading…)" : slot.error != null ? "  (error)" : ""));
        }
    }

    /**
     * Build a minimal WeatherModel from the surface tier of a wind profile slot
     * so we can place a wind marker even when no WTHR fetch has been done for
     * this exact coordinate.
     */
    private WeatherModel buildWeatherModelFromProfile(WindProfileViewModel.WindSlot slot) {
        if (slot.profiles == null || slot.profiles.isEmpty()) return null;
        com.atakmap.android.weather.domain.model.WindProfileModel frame = slot.profiles.get(0);
        if (frame.getAltitudes() == null || frame.getAltitudes().isEmpty()) return null;
        com.atakmap.android.weather.domain.model.WindProfileModel.AltitudeEntry surface =
                frame.getAltitudes().get(0); // lowest altitude tier (10 m surface)
        return new WeatherModel.Builder(slot.lat, slot.lon)
                .windSpeed(surface.windSpeed)
                .windDirection(surface.windDirection)
                .temperatureMin(surface.temperature)
                .temperatureMax(surface.temperature)
                .requestTimestamp(frame.getIsoTime())
                .build();
    }

    /**
     * Parse day-of-week from an ISO date-time string "YYYY-MM-DDTHH:MM".
     * Returns full English day name ("Monday" … "Sunday") or "" on parse failure.
     */
    private static String isoDayOfWeek(String iso) {
        if (iso == null || iso.length() < 10) return "";
        try {
            int year  = Integer.parseInt(iso.substring(0, 4));
            int month = Integer.parseInt(iso.substring(5, 7));
            int day   = Integer.parseInt(iso.substring(8, 10));
            java.util.Calendar cal = new java.util.GregorianCalendar(year, month - 1, day);
            String[] names = {"Sunday","Monday","Tuesday","Wednesday","Thursday","Friday","Saturday"};
            return names[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1];
        } catch (Exception e) {
            return "";
        }
    }

    // ── Marker intent handlers ─────────────────────────────────────────────────

    private void handleMarkerDetails(final String uid, final String requestedTab) {
        if (uid == null) { triggerAutoLoad(); return; }

        MapItem item = null;
        MapGroup root = getMapView().getRootGroup();
        MapGroup wxGrp = root.findMapGroup(WeatherMapOverlay.GROUP_NAME);
        if (wxGrp != null) item = wxGrp.deepFindUID(uid);
        if (item == null) {
            MapGroup windGrp = root.findMapGroup(
                    com.atakmap.android.weather.overlay.WindMapOverlay.GROUP_NAME);
            if (windGrp != null) item = windGrp.deepFindUID(uid);
        }

        if (item == null) {
            Toast.makeText(pluginContext, R.string.map_marker_no_data, Toast.LENGTH_SHORT).show();
            triggerAutoLoad();
            return;
        }

        final double lat = item.getMetaDouble("latitude",  Double.NaN);
        final double lon = item.getMetaDouble("longitude", Double.NaN);
        final String src = item.getMetaString("wx_source",  "MAP_CENTRE");

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

        String defaultTab = (uid.startsWith("wx_wind")) ? "wind" : "wthr";
        jumpToTab(requestedTab != null ? requestedTab : defaultTab);
    }

    private void handleShareMarker(final String uid) {
        if (uid == null) {
            Toast.makeText(pluginContext, R.string.map_marker_no_data, Toast.LENGTH_SHORT).show();
            return;
        }
        MapItem item = null;
        MapGroup root = getMapView().getRootGroup();
        MapGroup wxGrp = root.findMapGroup(WeatherMapOverlay.GROUP_NAME);
        if (wxGrp != null) item = wxGrp.deepFindUID(uid);
        if (item == null) {
            MapGroup windGrp = root.findMapGroup(
                    com.atakmap.android.weather.overlay.WindMapOverlay.GROUP_NAME);
            if (windGrp != null) item = windGrp.deepFindUID(uid);
        }
        if (item == null) {
            Toast.makeText(pluginContext, R.string.map_marker_no_data, Toast.LENGTH_SHORT).show();
            return;
        }
        final CotEvent event = CotEventFactory.createCotEvent(item);
        if (event == null || !event.isValid()) {
            Toast.makeText(pluginContext, "Could not create CoT event for marker", Toast.LENGTH_SHORT).show();
            return;
        }
        CotMapComponent.getExternalDispatcher().dispatchToBroadcast(event);
        Toast.makeText(pluginContext, "Shared: " + item.getMetaString("callsign", uid),
                Toast.LENGTH_SHORT).show();
    }

    @SuppressWarnings("deprecation")
    private void jumpToTab(final String tabName) {
        if (tabHost == null) return;
        try { tabHost.setCurrentTabByTag(tabName); } catch (Exception ignored) {}
    }

    // ── DropDownReceiver / OnStateListener ────────────────────────────────────

    /**
     * Called by WeatherMapComponent.onDestroyImpl() to remove any 3D wind-cone
     * shapes that are still on the map.  Without this call the Polyline and
     * SensorFOV items persist in the WindMapOverlay group after the plugin is
     * uninstalled, appearing as orphaned shapes on the next ATAK session.
     */
    public void clearWindShapes() {
        if (windEffectShape != null) {
            windEffectShape.removeAll();
        }
    }

    @Override public void disposeImpl() {
        // Cancel any active picker to avoid a leaked listener
        if (activePicker != null) { activePicker.cancel(); activePicker = null; }

        // Remove all LiveData observers so that a plugin reinstall in the same
        // ATAK session does not accumulate duplicate observer registrations.
        // observeForever() has no lifecycle owner — we must call removeObserver()
        // explicitly, and we can only do that if we kept the lambda reference.
        removeObservers();

        // Evict the in-memory wind profile cache so the next plugin load always
        // fetches fresh aloft data.
        if (cachingRepo != null) {
            cachingRepo.clearWindCache();
            cachingRepo = null;
        }
        networkRepo = null;
        fltCatBadge = null;
        if (radarManager != null) { radarManager.dispose(); radarManager = null; }

        // Reset init guard so that if ATAK reuses this receiver instance after
        // a hot-swap, the next SHOW_PLUGIN will re-run initDependencies() and
        // observeViewModels() with a clean slate.
        initialized = false;
    }

    private void removeObservers() {
        if (weatherViewModel == null) return;
        if (obsCurrentWeather  != null) weatherViewModel.getCurrentWeather() .removeObserver(obsCurrentWeather);
        if (obsActiveLocation  != null) weatherViewModel.getActiveLocation() .removeObserver(obsActiveLocation);
        if (obsDailyForecast   != null) weatherViewModel.getDailyForecast()  .removeObserver(obsDailyForecast);
        if (obsHourlyForecast  != null) weatherViewModel.getHourlyForecast() .removeObserver(obsHourlyForecast);
        if (obsSelectedHour    != null) weatherViewModel.getSelectedHour()   .removeObserver(obsSelectedHour);
        if (obsErrorMessage    != null) weatherViewModel.getErrorMessage()   .removeObserver(obsErrorMessage);
        if (obsCacheBadge      != null) weatherViewModel.getCacheBadge()     .removeObserver(obsCacheBadge);
        if (obsComparison      != null) weatherViewModel.getComparison()     .removeObserver(obsComparison);
        if (obsSelfLocation    != null) weatherViewModel.getSelfLocation()   .removeObserver(obsSelfLocation);
        if (obsCenterLocation  != null) weatherViewModel.getCenterLocation() .removeObserver(obsCenterLocation);
        if (windViewModel != null && obsWindProfile != null)
            windViewModel.getWindProfile().removeObserver(obsWindProfile);
        if (windViewModel != null && obsWindSlots != null)
            windViewModel.getSlots().removeObserver(obsWindSlots);
        if (windViewModel != null && obsActiveWindSlot != null)
            windViewModel.getActiveSlot().removeObserver(obsActiveWindSlot);
    }
    @Override public void onDropDownSelectionRemoved() {}
    @Override public void onDropDownVisible(boolean v) {}
    @Override public void onDropDownSizeChanged(double w, double h) {}
    @Override public void onDropDownClose() {
        // If pick mode is active and the drop-down closes naturally (not via
        // enterPickMode's closeDropDown), keep the picker running so the user
        // can tap the map.  Do not reset pick mode here.
    }
}
