package com.atakmap.android.weather;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
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
import com.atakmap.android.weather.data.cache.WeatherDatabase;
import com.atakmap.android.weather.data.geocoding.NominatimGeocodingSource;
import com.atakmap.android.weather.data.remote.IWeatherRemoteSource;
import com.atakmap.android.weather.data.remote.SourceDefinitionLoader;
import com.atakmap.android.weather.data.remote.WeatherSourceDefinition;
import com.atakmap.android.weather.data.remote.WeatherSourceManager;
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
import com.atakmap.android.weather.presentation.view.ComparisonView;
import com.atakmap.android.weather.presentation.view.CurrentWeatherView;
import com.atakmap.android.weather.presentation.view.DailyForecastView;
import com.atakmap.android.weather.presentation.view.ParametersView;
import com.atakmap.android.weather.presentation.view.RadarTabCoordinator;
import com.atakmap.android.weather.presentation.view.WeatherChartView;
import com.atakmap.android.weather.presentation.view.WindChartView;
import com.atakmap.android.weather.presentation.view.WindProfileView;
import com.atakmap.android.weather.presentation.view.WindTabCoordinator;
import com.atakmap.android.weather.presentation.viewmodel.UiState;
import com.atakmap.android.weather.presentation.viewmodel.WeatherObserverRegistry;
import com.atakmap.android.weather.presentation.viewmodel.WeatherViewModel;
import com.atakmap.android.weather.presentation.viewmodel.WindProfileViewModel;
import com.atakmap.android.weather.util.MapPointPicker;
import com.atakmap.android.weather.util.WeatherPlaceTool;
import com.atakmap.android.weather.util.WeatherUiUtils;

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
    public static final String SHOW_PLUGIN   = "com.atakmap.android.weather.SHOW_PLUGIN";
    public static final String SHARE_MARKER  = "com.atakmap.android.weather.SHARE_MARKER";
    public static final String REMOVE_MARKER = "com.atakmap.android.weather.REMOVE_MARKER";

    public static final String EXTRA_TARGET_UID    = "targetUID";
    public static final String EXTRA_REQUESTED_TAB = "requestedTab";

    // ── Layout ────────────────────────────────────────────────────────────────
    private final View    templateView;
    private final Context pluginContext;
    private final Context appContext;

    // ── ViewModels ────────────────────────────────────────────────────────────
    private WeatherViewModel     weatherViewModel;
    private WindProfileViewModel windViewModel;

    /** Kept so disposeImpl can clear the in-memory wind profile cache. */
    private CachingWeatherRepository cachingRepo;
    /** Kept to apply active-source changes from the PARM spinner. */
    private WeatherRepositoryImpl    networkRepo;

    // ── Observer registry (replaces 14 typed observer fields) ─────────────────
    private final WeatherObserverRegistry observers = new WeatherObserverRegistry();

    // ── Tab coordinators ──────────────────────────────────────────────────────
    private RadarTabCoordinator radarTabCoordinator;
    private WindTabCoordinator  windTabCoordinator;

    // ── View helpers ──────────────────────────────────────────────────────────
    private CurrentWeatherView currentWeatherView;
    private DailyForecastView  dailyForecastView;
    private WindProfileView    windProfileView;
    private ParametersView     parametersView;
    private WeatherChartView   chartView;
    private ComparisonView     comparisonView;
    private SeekBar            chartOverlaySeekBar;
    private TextView           fltCatBadge;

    // ── Preferences ───────────────────────────────────────────────────────────
    private WeatherParameterPreferences paramPrefs;

    // ── Init guard ────────────────────────────────────────────────────────────
    private boolean initialized = false;

    // ── TabHost ───────────────────────────────────────────────────────────────
    private android.widget.TabHost tabHost;

    // ── Marker managers ───────────────────────────────────────────────────────
    private final WeatherMarkerManager markerManager;
    private final WindMarkerManager    windMarkerManager;

    // ── Last known good state ─────────────────────────────────────────────────
    private WeatherModel     lastWeather;
    private LocationSnapshot lastLocation;
    private List<HourlyEntryModel> hourlyCache;

    // ── Point-pick state (consolidated from three fields) ─────────────────────
    private GeoPoint pendingPickPoint = null;  // non-null = place marker on next weather success
    private boolean  pickModeActive   = false;
    private Button   btnDropMarker    = null;

    // ── Last active slot tracking ─────────────────────────────────────────────
    private int    lastActiveSlotIdx  = -1;
    private String lastBoundSourceId  = null;

    // ── Constructor ───────────────────────────────────────────────────────────

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
        windViewModel    = new WindProfileViewModel(cachingRepo);
    }

    @SuppressWarnings("deprecation")
    private void initTabs() {
        tabHost = templateView.findViewById(R.id.mainTabHost);
        tabHost.setup();
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

        // ── WindEffectShape ───────────────────────────────────────────────────
        WindEffectShape windEffectShape = new WindEffectShape(
                getMapView(), windMarkerManager.getOverlay());

        // ── Tab coordinators ──────────────────────────────────────────────────
        radarTabCoordinator = new RadarTabCoordinator(getMapView(), templateView, pluginContext);
        windTabCoordinator  = new WindTabCoordinator(
                getMapView(), templateView, pluginContext,
                windViewModel, windMarkerManager, windEffectShape,
                windProfileView);

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

        // ── Tab 6 — Comparison ────────────────────────────────────────────────
        comparisonView = new ComparisonView(templateView);
        templateView.findViewById(R.id.comp_refresh_button)
                .setOnClickListener(v -> triggerComparison());
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
            } else if (state.isSuccess() && state.getData() != null) {
                WeatherModel w = state.getData();
                lastWeather = w;
                windTabCoordinator.setLastWeather(w);
                currentWeatherView.bindCurrentWeather(w, w.getRequestTimestamp());
                updateFltCatBadge(w);
                if (lastLocation != null) updateChartLocationHeader(lastLocation);

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
                pendingPickPoint = null;
            }
        });

        observers.add(weatherViewModel.getActiveLocation(), snapshot -> {
            if (snapshot != null) {
                lastLocation = snapshot;
                currentWeatherView.bindLocation(snapshot);
                updateChartLocationHeader(snapshot);
            }
        });

        observers.add(weatherViewModel.getDailyForecast(), state -> {
            if (state.isSuccess() && state.getData() != null)
                dailyForecastView.bind(state.getData());
        });

        observers.add(weatherViewModel.getHourlyForecast(), state -> {
            if (state.isSuccess() && state.getData() != null) {
                hourlyCache = state.getData();
                if (chartOverlaySeekBar != null) {
                    chartOverlaySeekBar.setMax(hourlyCache.size() - 1);
                    chartOverlaySeekBar.setProgress(0);
                }
                if (chartView != null) { chartView.setData(hourlyCache); chartView.invalidate(); }
                triggerComparison();
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
                windTabCoordinator.onWindProfilesUpdated(state.getData());
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

        observers.add(weatherViewModel.getComparison(), state -> {
            if (comparisonView == null) return;
            if (state.isLoading())                                    comparisonView.showLoading();
            else if (state.isSuccess() && state.getData() != null)    comparisonView.bind(state.getData());
            else if (state.isError())                                  comparisonView.showError(state.getErrorMessage());
        });

        observers.add(weatherViewModel.getSelfLocation(), snapshot -> {
            if (snapshot != null && comparisonView != null) comparisonView.bindSelfSnapshot(snapshot);
        });

        observers.add(weatherViewModel.getCenterLocation(), snapshot -> {
            if (snapshot != null && comparisonView != null) comparisonView.bindCenterSnapshot(snapshot);
        });
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
        double cenLat = getMapView().getCenterPoint().get().getLatitude();
        double cenLon = getMapView().getCenterPoint().get().getLongitude();
        if (selfLat == 0.0 && selfLon == 0.0) { selfLat = cenLat; selfLon = cenLon; }
        weatherViewModel.loadComparison(selfLat, selfLon, cenLat, cenLon);
    }

    // ── PARM tab source spinner ───────────────────────────────────────────────

    private void wireParmSourceSpinner() {
        Spinner spinner = templateView.findViewById(R.id.spinner_parm_source);
        if (spinner == null) return;

        WeatherSourceManager mgr     = WeatherSourceManager.getInstance(appContext);
        List<WeatherSourceManager.SourceEntry> entries = mgr.getAvailableEntries();

        spinner.setAdapter(WeatherUiUtils.makeDarkSpinnerAdapter(appContext, entries));
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

    /** Call from WeatherMapComponent.onDestroyImpl() to remove orphaned 3D shapes. */
    public void clearWindShapes() {
        if (windTabCoordinator != null) windTabCoordinator.clearWindShapes();
    }

    @Override public void disposeImpl() {
        // Cancel any active picker
        WeatherPlaceTool.cancel(getMapView());

        // Remove all LiveData observers in one call (replaces 14-line removeObservers())
        observers.removeAll();

        // Evict in-memory caches
        if (cachingRepo != null) { cachingRepo.clearWindCache(); cachingRepo = null; }
        networkRepo = null;
        fltCatBadge = null;

        if (radarTabCoordinator != null) { radarTabCoordinator.dispose(); radarTabCoordinator = null; }
        windTabCoordinator = null;

        initialized = false;
    }

    @Override public void onDropDownSelectionRemoved() {}
    @Override public void onDropDownVisible(boolean v) {}
    @Override public void onDropDownSizeChanged(double w, double h) {}
    @Override public void onDropDownClose() {
        // Keep the picker running when the drop-down closes naturally so the
        // user can tap the map. Do not reset pick mode here.
    }
}
