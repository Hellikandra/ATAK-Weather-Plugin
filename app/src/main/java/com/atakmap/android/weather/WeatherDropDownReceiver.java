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
    /** Hour index currently shown in the wind chart / effect (0 = now). */
    private int     windHourIndex = 0;

    // ── Chart overlay seekbar (transparent, sits on top of chart_frame) ─────
    private SeekBar chartOverlaySeekBar;
    /** Prevents feedback loop when syncing the two seekbars. */
    private boolean suppressSeekSync = false;

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
        OpenMeteoSource openMeteoSource = new OpenMeteoSource();
        Map<String, IWeatherRemoteSource> sources = new HashMap<>();
        sources.put(OpenMeteoSource.SOURCE_ID, openMeteoSource);

        WeatherRepositoryImpl networkRepo = new WeatherRepositoryImpl(sources, OpenMeteoSource.SOURCE_ID);
        IGeocodingRepository  geocodingRepo = new NominatimGeocodingSource();

        paramPrefs = new WeatherParameterPreferences(pluginContext);
        networkRepo.setParameterPreferences(paramPrefs);

        CachingWeatherRepository cachingRepo = new CachingWeatherRepository(
                networkRepo,
                WeatherDatabase.getInstance(appContext).weatherDao(),
                paramPrefs);
        cachingRepo.purgeExpired();

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
            double lat = getMapView().getCenterPoint().get().getLatitude();
            double lon = getMapView().getCenterPoint().get().getLongitude();
            windViewModel.loadWindProfile(lat, lon);
        });

        // Tab 4 — Parameters
        parametersView = new ParametersView(templateView, pluginContext, paramPrefs);
        parametersView.setAvailableParameters(new OpenMeteoSource().getSupportedParameters());
        parametersView.setOnChangeListener(() -> {
            Toast.makeText(pluginContext, R.string.params_reloading, Toast.LENGTH_SHORT).show();
            triggerAutoLoad();
        });

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
                    if (!fromUser || suppressSeekSync) return;
                    suppressSeekSync = true;
                    // Sync main seekBar without re-triggering this listener
                    SeekBar mainSeek = templateView.findViewById(R.id.seekBar);
                    if (mainSeek != null) mainSeek.setProgress(p);
                    suppressSeekSync = false;
                    weatherViewModel.selectHour(p);
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
        }
        // ── CONF tab: API Source Spinner ─────────────────────────────────
        wireSourceSpinner();

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

        // Collapse drop-down so the map is fully visible.
        // ATAK will keep the receiver alive — the picker fires when the user taps.
        closeDropDown();

        activePicker = MapPointPicker.pick(getMapView(), pickedPoint -> {
            // Runs on main thread after user taps map
            resetPickMode(btn, hint);

            // Fetch weather at the tapped point and flag that we should
            // auto-place the marker when data arrives.
            pendingPickLat = pickedPoint.getLatitude();
            pendingPickLon = pickedPoint.getLongitude();
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
    }

    private void cancelPickMode(Button btn, TextView hint) {
        if (activePicker != null) { activePicker.cancel(); activePicker = null; }
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
        if (btnDropWindMarker != null) {
            btnDropWindMarker.setOnClickListener(v -> {
                if (lastWeather != null && lastLocation != null && windMarkerManager != null) {
                    windMarkerManager.placeMarker(lastLocation, lastWeather);
                    lastWindLat   = lastLocation.getLatitude();
                    lastWindLon   = lastLocation.getLongitude();
                    lastWindIsSelf = lastLocation.getSource() == LocationSource.SELF_MARKER;
                    Toast.makeText(pluginContext, "Wind marker dropped", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(pluginContext, R.string.map_marker_no_data, Toast.LENGTH_SHORT).show();
                }
            });
        }

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

    private void redrawIfActive() {
        if (windEffectActive && windEffectShape != null
                && lastWeather != null && !Double.isNaN(lastWindLat)) {
            drawWindEffect();
        }
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

        weatherViewModel.getCurrentWeather().observeForever(state -> {
            if (state.isLoading()) {
                currentWeatherView.showLoading();
            } else if (state.isSuccess() && state.getData() != null) {
                WeatherModel w = state.getData();
                lastWeather = w;
                currentWeatherView.bindCurrentWeather(w, w.getRequestTimestamp());
                if (lastLocation != null) updateChartLocationHeader(lastLocation);

                // ── Auto-place after point-pick ──────────────────────────────
                // If a point was picked by the user, auto-place the weather
                // marker at that location as soon as weather data arrives.
                if (pendingPickPlace) {
                    pendingPickPlace = false;
                    // Use lastLocation if already updated, else synthesise from
                    // the picked coords (race: getActiveLocation may fire after
                    // getCurrentWeather in some ATAK versions).
                    com.atakmap.android.weather.domain.model.LocationSnapshot placeSnap = lastLocation;
                    if (placeSnap == null && !Double.isNaN(pendingPickLat)) {
                        placeSnap = new com.atakmap.android.weather.domain.model.LocationSnapshot(
                                pendingPickLat, pendingPickLon,
                                null,
                                com.atakmap.android.weather.domain.model.LocationSource.MAP_CENTRE);
                    }
                    if (placeSnap != null) {
                        markerManager.placeMarker(placeSnap, w);
                        updateMarkerStatus(placeSnap);
                    }
                }
            } else if (state.isError()) {
                currentWeatherView.showError(state.getErrorMessage());
                pendingPickPlace = false;  // clear on error too
            }
        });

        weatherViewModel.getActiveLocation().observeForever(snapshot -> {
            if (snapshot != null) {
                lastLocation = snapshot;
                currentWeatherView.bindLocation(snapshot);
                updateChartLocationHeader(snapshot);
            }
        });

        weatherViewModel.getDailyForecast().observeForever(state -> {
            if (state.isSuccess() && state.getData() != null)
                dailyForecastView.bind(state.getData());
        });

        weatherViewModel.getHourlyForecast().observeForever(state -> {
            if (state.isSuccess() && state.getData() != null) {
                hourlyCache = state.getData();
                final int maxHour = hourlyCache.size() - 1;
                if (chartOverlaySeekBar != null) chartOverlaySeekBar.setMax(maxHour);
                currentWeatherView.configureSeekBar(
                        maxHour,
                        new SeekBar.OnSeekBarChangeListener() {
                            @Override public void onProgressChanged(SeekBar bar, int i, boolean fromUser) {
                                if (fromUser && !suppressSeekSync) {
                                    suppressSeekSync = true;
                                    if (chartOverlaySeekBar != null) chartOverlaySeekBar.setProgress(i);
                                    suppressSeekSync = false;
                                }
                                weatherViewModel.selectHour(i);
                            }
                            @Override public void onStartTrackingTouch(SeekBar bar) {}
                            @Override public void onStopTrackingTouch(SeekBar bar)  {}
                        });
                if (chartView != null) { chartView.setData(hourlyCache); chartView.invalidate(); }
                triggerComparison();
            }
        });

        weatherViewModel.getSelectedHour().observeForever(index -> {
            if (index == null) return;
            if (chartView != null) { chartView.setSelectedIndex(index); updateChartReadouts(index); }
            if (hourlyCache != null && index >= 0 && index < hourlyCache.size()) {
                HourlyEntryModel entry = hourlyCache.get(index);
                String label = "+" + index + "h  " + entry.getIsoTime().replace("T", " ");
                currentWeatherView.bindHourlyEntry(entry, label);
            }
        });

        weatherViewModel.getErrorMessage().observeForever(msg -> {
            if (msg != null) Toast.makeText(pluginContext, msg, Toast.LENGTH_SHORT).show();
        });

        weatherViewModel.getCacheBadge().observeForever(badge -> {
            TextView badgeView = templateView.findViewById(R.id.textview_cache_badge);
            if (badgeView == null) return;
            if (badge == null || badge.isEmpty()) {
                badgeView.setVisibility(View.GONE);
            } else {
                badgeView.setText(badge);
                badgeView.setVisibility(View.VISIBLE);
            }
        });

        windViewModel.getWindProfile().observeForever(state -> {
            if (state.isLoading()) {
                windProfileView.showLoading();
            } else if (state.isSuccess() && state.getData() != null) {
                lastWindProfiles = state.getData();
                windProfileView.bind(state.getData());
            } else if (state.isError()) {
                windProfileView.showError(state.getErrorMessage());
            }
        });

        weatherViewModel.getComparison().observeForever(state -> {
            if (comparisonView == null) return;
            if (state.isLoading())                                    comparisonView.showLoading();
            else if (state.isSuccess() && state.getData() != null)    comparisonView.bind(state.getData());
            else if (state.isError())                                  comparisonView.showError(state.getErrorMessage());
        });

        weatherViewModel.getSelfLocation().observeForever(snapshot -> {
            if (snapshot != null && comparisonView != null) comparisonView.bindSelfSnapshot(snapshot);
        });
        weatherViewModel.getCenterLocation().observeForever(snapshot -> {
            if (snapshot != null && comparisonView != null) comparisonView.bindCenterSnapshot(snapshot);
        });
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
        double selfLat = getMapView().getSelfMarker().getPoint().getLatitude();
        double selfLon = getMapView().getSelfMarker().getPoint().getLongitude();
        double cenLat  = getMapView().getCenterPoint().get().getLatitude();
        double cenLon  = getMapView().getCenterPoint().get().getLongitude();
        if (selfLat == 0.0 && selfLon == 0.0) { selfLat = cenLat; selfLon = cenLon; }
        weatherViewModel.loadComparison(selfLat, selfLon, cenLat, cenLon);
    }

    // ── Source Spinner ────────────────────────────────────────────────────────

    private void wireSourceSpinner() {
        Spinner spinner = templateView.findViewById(R.id.spinner_weather_source);
        if (spinner == null) return;
        // pluginContext is a PluginContext wrapper; SharedPreferences requires the
        // real application context (appContext) — using pluginContext crashes at
        // ctx.getSharedPreferences() with NullPointerException.
        WeatherSourceManager mgr = WeatherSourceManager.getInstance(appContext);
        java.util.List<WeatherSourceManager.SourceEntry> entries = mgr.getAvailableEntries();
        ArrayAdapter<WeatherSourceManager.SourceEntry> adapter =
                new ArrayAdapter<>(pluginContext,
                        android.R.layout.simple_spinner_item, entries);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(mgr.getActiveSourceIndex(), false);
        TextView statusView = templateView.findViewById(R.id.textview_source_status);
        updateSourceStatus(statusView, mgr.getActiveSource());
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View v,
                                       int pos, long id) {
                WeatherSourceManager.SourceEntry entry = entries.get(pos);
                mgr.setActiveSourceId(entry.sourceId);
                updateSourceStatus(statusView, mgr.getActiveSource());
                Toast.makeText(pluginContext,
                        "Source: " + entry.displayName + " — reload to apply",
                        Toast.LENGTH_SHORT).show();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void updateSourceStatus(TextView tv, com.atakmap.android.weather.data.remote.IWeatherRemoteSource source) {
        if (tv == null || source == null) return;
        tv.setText(source.getDisplayName() + "  |  " + source.getSupportedParameters().size() + " parameters");
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
        if (hourLabel != null) hourLabel.setText(String.format("+%02dh", index));
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
        if (tsLabel  != null && lastWeather != null) tsLabel.setText(lastWeather.getRequestTimestamp());
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

    @Override public void disposeImpl() {
        // Cancel any active picker to avoid a leaked listener
        if (activePicker != null) { activePicker.cancel(); activePicker = null; }
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
