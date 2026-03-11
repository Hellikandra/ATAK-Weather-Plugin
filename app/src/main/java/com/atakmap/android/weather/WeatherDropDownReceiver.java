package com.atakmap.android.weather;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.Toast;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.weather.data.WeatherRepositoryImpl;
import com.atakmap.android.weather.data.cache.CachingWeatherRepository;
import com.atakmap.android.weather.data.cache.WeatherDatabase;
import com.atakmap.android.weather.data.geocoding.NominatimGeocodingSource;
import com.atakmap.android.weather.data.remote.IWeatherRemoteSource;
import com.atakmap.android.weather.data.remote.OpenMeteoSource;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.LocationSource;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.repository.IGeocodingRepository;
import com.atakmap.android.weather.infrastructure.preferences.WeatherParameterPreferences;
import com.atakmap.android.weather.overlay.WeatherMapOverlay;
import com.atakmap.android.weather.overlay.marker.WeatherMarkerManager;
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
import com.atakmap.android.weather.domain.model.LocationSnapshot;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.importexport.CotEventFactory;
import com.atakmap.android.maps.MapItem;
import com.atakmap.coremap.cot.event.CotEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WeatherDropDownReceiver — ATAK drop-down entry point.
 *
 * ── Sprint 1 changes ──────────────────────────────────────────────────────────
 *
 * 1. Refresh button — short tap vs long press
 *    Short tap  → MAP_CENTRE  (where you are looking)
 *    Long press → SELF_MARKER (your GPS position)
 *    A Toast confirms which source was used so the user always knows.
 *
 * 2. loadWeatherWithFallback() on every open
 *    On first open and every subsequent open the fallback chain runs:
 *      GPS available  → SELF_MARKER
 *      GPS at 0,0     → MAP_CENTRE (with informational Toast)
 *    This eliminates the "Unknown location" case entirely.
 *
 * 3. Location observers
 *    getActiveLocation() LiveData drives Tab 1 name + coords header.
 *    getSelfLocation() / getCenterLocation() drive Tab 6 card headers.
 *
 * 4. Auto-comparison on first open
 *    After the first Tab-1 load succeeds, loadComparison() is called
 *    automatically so Tab 6 is pre-populated without requiring a tab switch.
 *
 * ── ATAK rules (unchanged) ────────────────────────────────────────────────────
 *  1. Package: com.atakmap.android.weather (root)
 *  2. PluginLayoutInflater.inflate() — not LayoutInflater.from()
 *  3. SHOW_PLUGIN action string must match WeatherMapComponent's IntentFilter
 *  4. Inflate once in constructor — same view reused on every open
 */
public class WeatherDropDownReceiver extends DropDownReceiver
        implements OnStateListener {

    public static final String TAG         = WeatherDropDownReceiver.class.getSimpleName();
    public static final String SHOW_PLUGIN     = "com.atakmap.android.weather.SHOW_PLUGIN";
    public static final String SHARE_MARKER   = "com.atakmap.android.weather.SHARE_MARKER";
    public static final String REMOVE_MARKER  = "com.atakmap.android.weather.REMOVE_MARKER";

    /** Intent extra key carrying the marker UID (from radial menu {uid} token). */
    public static final String EXTRA_TARGET_UID    = "targetUID";
    /** Intent extra key requesting a specific tab name: "fcst","wind","chrt","prm","map","cmp". */
    public static final String EXTRA_REQUESTED_TAB = "requestedTab";

    // ── Layout ───────────────────────────────────────────────────────────────
    private final View    templateView;
    private final Context pluginContext;  // plugin's own context — layout, prefs, toasts
    private final Context appContext;     // ATAK app context — Room database builder

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

    // ── TabHost reference (needed for programmatic tab switching) ────────────
    private android.widget.TabHost tabHost;

    // ── Marker manager (Tab 3) ───────────────────────────────────────────────
    private WeatherMarkerManager markerManager;
    private WindMarkerManager windMarkerManager;

    // ── Last known good weather + location (for marker placement) ────────────
    private WeatherModel        lastWeather;
    private LocationSnapshot    lastLocation;

    // ── Cached hourly list for SeekBar scrubbing ─────────────────────────────
    private List<HourlyEntryModel> hourlyCache;

    // ── Constructor ───────────────────────────────────────────────────────────

    public WeatherDropDownReceiver(final MapView mapView,
                                   final Context context,
                                   final WeatherMarkerManager markerManager,
                                   final WindMarkerManager windMarkerManager) {
        super(mapView);
        this.pluginContext  = context;
        // mapView.getContext() returns the host application's context which has a
        // valid ApplicationContext — required by Room.databaseBuilder().
        // pluginContext.getApplicationContext() returns null in the ATAK plugin
        // sandbox and must never be passed to Room.
        this.appContext     = mapView.getContext();
        this.markerManager      = markerManager;
        this.windMarkerManager  = windMarkerManager;
        templateView = PluginLayoutInflater.inflate(context, R.layout.main_layout, null);
    }

    // ── onReceive ─────────────────────────────────────────────────────────────

    @Override
    public void onReceive(final Context context, Intent intent) {
        final String action = intent.getAction();
        if (action == null) return;

        // ── SHARE_MARKER — broadcast this marker's CoT over the TAK network ──
        if (SHARE_MARKER.equals(action)) {
            handleShareMarker(intent.getStringExtra(EXTRA_TARGET_UID));
            return;
        }

        // ── REMOVE_MARKER — remove this weather or wind marker from the map ───
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

        // ── SHOW_PLUGIN — open the drop-down (optionally jump to a tab) ──────
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
            // Register the radial-menu intent actions so onReceive() is called
            // when the user taps Share or Remove from the marker radial menu.
            com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter extras =
                    new com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter();
            extras.addAction(SHARE_MARKER);
            extras.addAction(REMOVE_MARKER);
            com.atakmap.android.ipc.AtakBroadcast.getInstance()
                    .registerReceiver(this, extras);
            initialized = true;
        }

        // If we arrived from a marker radial menu, pre-populate from that
        // marker's stored weather meta-data and jump to the requested tab.
        final String targetUid  = intent.getStringExtra(EXTRA_TARGET_UID);
        final String requestTab = intent.getStringExtra(EXTRA_REQUESTED_TAB);
        if (targetUid != null) {
            handleMarkerDetails(targetUid, requestTab);
        } else {
            // Normal open — run the GPS → map-centre fallback chain
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

        // Sprint 2: inject paramPrefs before first load
        paramPrefs = new WeatherParameterPreferences(pluginContext);
        networkRepo.setParameterPreferences(paramPrefs);

        // Sprint 3: wrap with caching repository
        CachingWeatherRepository cachingRepo = new CachingWeatherRepository(
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

        spec = tabHost.newTabSpec("fcst");
        spec.setContent(R.id.subTabWidget1);
        spec.setIndicator("Fcst");
        tabHost.addTab(spec);

        spec = tabHost.newTabSpec("wind");
        spec.setContent(R.id.subTabWidget2);
        spec.setIndicator("Wind");
        tabHost.addTab(spec);

        spec = tabHost.newTabSpec("map");
        spec.setContent(R.id.subTabWidget3);
        spec.setIndicator("Map");
        tabHost.addTab(spec);

        spec = tabHost.newTabSpec("prm");
        spec.setContent(R.id.subTabWidget4);
        spec.setIndicator("Prm");
        tabHost.addTab(spec);

        spec = tabHost.newTabSpec("chrt");
        spec.setContent(R.id.subTabWidget5);
        spec.setIndicator("Chrt");
        tabHost.addTab(spec);

        spec = tabHost.newTabSpec("cmp");
        spec.setContent(R.id.subTabWidget6);
        spec.setIndicator("Cmp");
        tabHost.addTab(spec);
    }

    private void initViewHelpers() {
        currentWeatherView = new CurrentWeatherView(templateView, pluginContext);
        dailyForecastView  = new DailyForecastView(templateView);
        windProfileView    = new WindProfileView(templateView);

        // ── Refresh button: short tap = map centre, long press = self marker ──
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
        // paramPrefs already constructed in initDependencies.
        // Pass the source's supported parameter list so the UI reflects
        // only what the active source can provide.
        parametersView = new ParametersView(templateView, pluginContext, paramPrefs);
        parametersView.setAvailableParameters(
                new OpenMeteoSource().getSupportedParameters());
        parametersView.setOnChangeListener(() -> {
            // User changed a parameter selection — re-trigger load so the
            // new variable set is fetched immediately (debounced 1 s in ParametersView)
            Toast.makeText(pluginContext, R.string.params_reloading, Toast.LENGTH_SHORT).show();
            triggerAutoLoad();
        });

        // Tab 5 — Charts: inject canvas view into placeholder FrameLayout
        FrameLayout chartFrame = templateView.findViewById(R.id.chart_frame);
        if (chartFrame != null) {
            chartView = new WeatherChartView(pluginContext);
            chartFrame.addView(chartView);
            wireChartToggleButtons();
        }

        // Tab 3 — Map / Marker controls
        // ── Wind tab: drop wind marker button ────────────────────────────────
        android.widget.Button btnDropWindMarker = templateView.findViewById(R.id.btn_drop_wind_marker);
        if (btnDropWindMarker != null) {
            btnDropWindMarker.setOnClickListener(v -> {
                if (lastWeather != null && lastLocation != null && windMarkerManager != null) {
                    windMarkerManager.placeMarker(lastLocation, lastWeather);
                    Toast.makeText(pluginContext, "Wind marker dropped", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(pluginContext, R.string.map_marker_no_data, Toast.LENGTH_SHORT).show();
                }
            });
        }

        android.widget.Button btnDropMarker = templateView.findViewById(R.id.btn_drop_weather_marker);
        android.widget.Button btnShareMarker = templateView.findViewById(R.id.btn_share_marker);
        android.widget.Button btnRemoveAll  = templateView.findViewById(R.id.btn_remove_all_markers);

        if (btnDropMarker != null) {
            btnDropMarker.setOnClickListener(v -> {
                if (lastWeather != null && lastLocation != null) {
                    markerManager.placeMarker(lastLocation, lastWeather);
                    updateMarkerStatus(lastLocation);
                } else {
                    Toast.makeText(pluginContext, R.string.map_marker_no_data, Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (btnShareMarker != null) {
            btnShareMarker.setOnClickListener(v -> {
                // Share the last-placed marker by constructing its UID and
                // dispatching SHARE_MARKER — same path as the radial menu button.
                if (lastLocation == null) {
                    Toast.makeText(pluginContext, R.string.map_marker_no_data, Toast.LENGTH_SHORT).show();
                    return;
                }
                android.content.Intent shareIntent = new android.content.Intent(SHARE_MARKER);
                shareIntent.putExtra(EXTRA_TARGET_UID, buildMarkerUid(lastLocation));
                com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(shareIntent);
            });
        }

        if (btnRemoveAll != null) {
            btnRemoveAll.setOnClickListener(v -> {
                markerManager.removeAllMarkers();
                android.widget.TextView statusView = templateView.findViewById(R.id.textview_marker_status);
                if (statusView != null) statusView.setText(R.string.map_marker_none_placed);
            });
        }

        // Tab 6 — Comparison
        comparisonView = new ComparisonView(templateView);
        templateView.findViewById(R.id.comp_refresh_button)
                .setOnClickListener(v -> triggerComparison());
    }

    // ── LiveData observers ────────────────────────────────────────────────────

    @SuppressLint("SetTextI18n")
    private void observeViewModels() {

        // Tab 1 — current conditions
        weatherViewModel.getCurrentWeather().observeForever(state -> {
            if (state.isLoading()) {
                currentWeatherView.showLoading();
            } else if (state.isSuccess() && state.getData() != null) {
                WeatherModel w = state.getData();
                lastWeather = w;
                currentWeatherView.bindCurrentWeather(w, w.getRequestTimestamp());
                // Refresh chart timestamp header if location is already known
                if (lastLocation != null) updateChartLocationHeader(lastLocation);
            } else if (state.isError()) {
                currentWeatherView.showError(state.getErrorMessage());
            }
        });

        // Tab 1 — location header (name + coords + source tag)
        weatherViewModel.getActiveLocation().observeForever(snapshot -> {
            if (snapshot != null) {
                lastLocation = snapshot;
                currentWeatherView.bindLocation(snapshot);
                // Update chart tab header so the user knows which point the data is for
                updateChartLocationHeader(snapshot);
            }
        });

        // Tab 1 — daily forecast strip
        weatherViewModel.getDailyForecast().observeForever(state -> {
            if (state.isSuccess() && state.getData() != null) {
                dailyForecastView.bind(state.getData());
            }
        });

        // Tab 1 + Tab 5 — hourly data
        weatherViewModel.getHourlyForecast().observeForever(state -> {
            if (state.isSuccess() && state.getData() != null) {
                hourlyCache = state.getData();
                currentWeatherView.configureSeekBar(
                        hourlyCache.size() - 1,
                        new SeekBar.OnSeekBarChangeListener() {
                            @Override public void onProgressChanged(SeekBar bar, int i, boolean b) {
                                weatherViewModel.selectHour(i);
                            }
                            @Override public void onStartTrackingTouch(SeekBar bar) {}
                            @Override public void onStopTrackingTouch(SeekBar bar)  {}
                        });
                if (chartView != null) {
                    chartView.setData(hourlyCache);
                    chartView.invalidate();
                }
                // Auto-trigger Tab 6 comparison on first successful load
                triggerComparison();
            }
        });

        // Tab 1 + Tab 5 — SeekBar position
        weatherViewModel.getSelectedHour().observeForever(index -> {
            if (index == null) return;
            if (chartView != null) {
                chartView.setSelectedIndex(index);
                updateChartReadouts(index);
            }
            if (hourlyCache != null && index >= 0 && index < hourlyCache.size()) {
                HourlyEntryModel entry = hourlyCache.get(index);
                String label = "+" + index + "h  " + entry.getIsoTime().replace("T", " ");
                currentWeatherView.bindHourlyEntry(entry, label);
            }
        });

        // Error toast
        weatherViewModel.getErrorMessage().observeForever(msg -> {
            if (msg != null) Toast.makeText(pluginContext, msg, Toast.LENGTH_SHORT).show();
        });

        // Sprint 3 — cache badge in Tab 1 header
        weatherViewModel.getCacheBadge().observeForever(badge -> {
            android.widget.TextView badgeView =
                    templateView.findViewById(R.id.textview_cache_badge);
            if (badgeView == null) return;
            if (badge == null || badge.isEmpty()) {
                badgeView.setVisibility(android.view.View.GONE);
            } else {
                badgeView.setText(badge);
                badgeView.setVisibility(android.view.View.VISIBLE);
            }
        });

        // Tab 2 — wind profile
        windViewModel.getWindProfile().observeForever(state -> {
            if (state.isLoading()) {
                windProfileView.showLoading();
            } else if (state.isSuccess() && state.getData() != null) {
                windProfileView.bind(state.getData());
            } else if (state.isError()) {
                windProfileView.showError(state.getErrorMessage());
            }
        });

        // Tab 6 — comparison weather data
        weatherViewModel.getComparison().observeForever(state -> {
            if (comparisonView == null) return;
            if (state.isLoading()) {
                comparisonView.showLoading();
            } else if (state.isSuccess() && state.getData() != null) {
                comparisonView.bind(state.getData());
            } else if (state.isError()) {
                comparisonView.showError(state.getErrorMessage());
            }
        });

        // Tab 6 — self card location header
        weatherViewModel.getSelfLocation().observeForever(snapshot -> {
            if (snapshot != null && comparisonView != null)
                comparisonView.bindSelfSnapshot(snapshot);
        });

        // Tab 6 — centre card location header
        weatherViewModel.getCenterLocation().observeForever(snapshot -> {
            if (snapshot != null && comparisonView != null)
                comparisonView.bindCenterSnapshot(snapshot);
        });
    }

    // ── Data triggers ─────────────────────────────────────────────────────────

    /**
     * Called on every plugin open.
     * Runs the GPS → map-centre fallback chain and shows an informational
     * Toast if we had to fall back so the user is not confused.
     */
    private void triggerAutoLoad() {
        double cenLat  = getMapView().getCenterPoint().get().getLatitude();
        double cenLon  = getMapView().getCenterPoint().get().getLongitude();

        // getSelfMarker() can be null on cold start before ATAK has initialised
        // the self-marker. Guard against NPE and fall back to map centre.
        double selfLat = 0.0, selfLon = 0.0;
        try {
            if (getMapView().getSelfMarker() != null) {
                selfLat = getMapView().getSelfMarker().getPoint().getLatitude();
                selfLon = getMapView().getSelfMarker().getPoint().getLongitude();
            }
        } catch (Exception e) {
            com.atakmap.coremap.log.Log.w(TAG, "getSelfMarker() threw: " + e.getMessage());
        }

        // A position of exactly 0,0 means "no fix yet" in ATAK.
        boolean hasGps = !(selfLat == 0.0 && selfLon == 0.0);
        if (!hasGps) {
            Toast.makeText(pluginContext, R.string.no_gps_using_map_centre, Toast.LENGTH_SHORT).show();
        }
        weatherViewModel.loadWeatherWithFallback(selfLat, selfLon, cenLat, cenLon);
    }

    /**
     * Fires comparison load for the current self + map-centre positions.
     * Called automatically after first hourly data arrives, and also by the
     * Tab 6 manual refresh button.
     */
    private void triggerComparison() {
        double selfLat = getMapView().getSelfMarker().getPoint().getLatitude();
        double selfLon = getMapView().getSelfMarker().getPoint().getLongitude();
        double cenLat  = getMapView().getCenterPoint().get().getLatitude();
        double cenLon  = getMapView().getCenterPoint().get().getLongitude();

        // If no GPS, use map centre for both — delta will be 0 but won't crash
        if (selfLat == 0.0 && selfLon == 0.0) {
            selfLat = cenLat;
            selfLon = cenLon;
        }
        weatherViewModel.loadComparison(selfLat, selfLon, cenLat, cenLon);
    }

    // ── Chart helpers ─────────────────────────────────────────────────────────

    private void wireChartToggleButtons() {
        int[] btnIds = {
                R.id.chart_toggle_temp,
                R.id.chart_toggle_humidity,
                R.id.chart_toggle_wind,
                R.id.chart_toggle_pressure
        };
        WeatherChartView.Series[] series = WeatherChartView.Series.values();
        for (int i = 0; i < btnIds.length && i < series.length; i++) {
            android.widget.Button btn = templateView.findViewById(btnIds[i]);
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
        android.widget.TextView valTemp     = templateView.findViewById(R.id.chart_val_temp);
        android.widget.TextView valHumidity = templateView.findViewById(R.id.chart_val_humidity);
        android.widget.TextView valWind     = templateView.findViewById(R.id.chart_val_wind);
        android.widget.TextView valPressure = templateView.findViewById(R.id.chart_val_pressure);
        android.widget.TextView hourLabel   = templateView.findViewById(R.id.chart_hour_label);
        if (hourLabel   != null) hourLabel.setText(String.format("+%02dh", index));
        setReadout(valTemp,     chartView.valueAt(WeatherChartView.Series.TEMPERATURE, index), "%.1f°");
        setReadout(valHumidity, chartView.valueAt(WeatherChartView.Series.HUMIDITY,    index), "%.0f%%");
        setReadout(valWind,     chartView.valueAt(WeatherChartView.Series.WIND,        index), "%.1f m/s");
        setReadout(valPressure, chartView.valueAt(WeatherChartView.Series.PRESSURE,    index), "%.0f hPa");
    }

    private static void setReadout(android.widget.TextView tv, double val, String fmt) {
        if (tv != null && !Double.isNaN(val)) tv.setText(String.format(fmt, val));
    }

    /**
     * Mirrors WeatherMarkerManager.buildUid() so the Tab-3 Share button can
     * reference the UID of the last-placed marker without holding a reference
     * to the marker object itself.
     */
    private String buildMarkerUid(LocationSnapshot snapshot) {
        if (snapshot.getSource() == LocationSource.SELF_MARKER) return "wx_self";
        return String.format(java.util.Locale.US, "wx_centre_%.4f_%.4f",
                snapshot.getLatitude(), snapshot.getLongitude());
    }

    /** Update the Tab 3 status text after placing a marker. */
    private void updateMarkerStatus(LocationSnapshot snapshot) {
        android.widget.TextView statusView = templateView.findViewById(R.id.textview_marker_status);
        if (statusView == null) return;
        statusView.setText(String.format("%s\n%s  [%s]",
                snapshot.getDisplayName(),
                snapshot.getCoordsLabel(),
                snapshot.getSource().label));
    }

    /**
     * Update the Tab 5 chart header with the location name and data timestamp.
     * Called whenever a new location snapshot arrives.
     */
    private void updateChartLocationHeader(LocationSnapshot snapshot) {
        android.widget.TextView locLabel = templateView.findViewById(R.id.chart_location_label);
        android.widget.TextView tsLabel  = templateView.findViewById(R.id.chart_timestamp_label);
        if (locLabel != null) locLabel.setText(snapshot.getDisplayName());
        if (tsLabel  != null && lastWeather != null) {
            tsLabel.setText(lastWeather.getRequestTimestamp());
        }
    }

    // ── Marker intent handlers ────────────────────────────────────────────────

    /**
     * Called when the user taps "WX Details" on a weather marker's radial menu.
     *
     * Looks up the marker by UID, extracts the stored weather meta-strings, and
     * reloads WeatherViewModel with those values so the drop-down shows data
     * for the tapped marker's position rather than the GPS/map-centre position.
     *
     * Then switches the TabHost to the requested tab (default: "fcst").
     *
     * @param uid          UID of the tapped weather marker (e.g. "wx_self")
     * @param requestedTab Tab name: "fcst" | "wind" | "chrt" | "prm" | "map" | "cmp"
     */
    private void handleMarkerDetails(final String uid, final String requestedTab) {
        if (uid == null) { triggerAutoLoad(); return; }

        // Look up the marker — check both WX Markers group and Wind Markers group
        MapItem item = null;
        {
            MapGroup root = getMapView().getRootGroup();
            // Try weather markers group first
            MapGroup wxGrp = root.findMapGroup(
                    WeatherMapOverlay.GROUP_NAME);
            if (wxGrp != null) item = wxGrp.deepFindUID(uid);
            // If not found, try wind markers group
            if (item == null) {
                MapGroup windGrp = root.findMapGroup(
                        com.atakmap.android.weather.overlay.WindMapOverlay.GROUP_NAME);
                if (windGrp != null) item = windGrp.deepFindUID(uid);
            }
        }

        if (item == null) {
            // Marker not found — fall back to auto load
            Toast.makeText(pluginContext, R.string.map_marker_no_data, Toast.LENGTH_SHORT).show();
            triggerAutoLoad();
            return;
        }

        // Extract the geographic position stored in the marker
        final double lat = item.getMetaDouble("latitude",  Double.NaN);
        final double lon = item.getMetaDouble("longitude", Double.NaN);
        final String src = item.getMetaString("wx_source",  "MAP_CENTRE");

        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            // Fallback: re-trigger from the marker's map point if meta is missing
            if (item instanceof com.atakmap.android.maps.PointMapItem) {
                com.atakmap.android.maps.PointMapItem pmi =
                        (com.atakmap.android.maps.PointMapItem) item;
                LocationSource source = LocationSource.SELF_MARKER.name().equals(src)
                        ? LocationSource.SELF_MARKER : LocationSource.MAP_CENTRE;
                weatherViewModel.loadWeather(
                        pmi.getPoint().getLatitude(),
                        pmi.getPoint().getLongitude(),
                        source);
            } else {
                triggerAutoLoad();
            }
        } else {
            LocationSource source = LocationSource.SELF_MARKER.name().equals(src)
                    ? LocationSource.SELF_MARKER : LocationSource.MAP_CENTRE;
            weatherViewModel.loadWeather(lat, lon, source);
        }

        // Wind markers default to the wind tab
        String defaultTab = (uid != null && uid.startsWith("wx_wind")) ? "wind" : "fcst";
        jumpToTab(requestedTab != null ? requestedTab : defaultTab);
    }

    /**
     * Called when the user taps "Share WX" on a weather marker's radial menu.
     *
     * Converts the marker to a CoT XML event via CotEventFactory, then
     * broadcasts it to all connected TAK servers and peer-to-peer connections
     * via CotMapComponent.getExternalDispatcher().dispatchToBroadcast().
     *
     * The CoT event includes all standard fields (type, how, time, point) plus
     * the wx_* meta-strings as a <detail><weather ...> element via marker metadata.
     */
    private void handleShareMarker(final String uid) {
        if (uid == null) {
            Toast.makeText(pluginContext, R.string.map_marker_no_data, Toast.LENGTH_SHORT).show();
            return;
        }

        MapItem item = null;
        {
            MapGroup root = getMapView().getRootGroup();
            MapGroup wxGrp = root.findMapGroup(
                    WeatherMapOverlay.GROUP_NAME);
            if (wxGrp != null) item = wxGrp.deepFindUID(uid);
            if (item == null) {
                MapGroup windGrp = root.findMapGroup(
                        com.atakmap.android.weather.overlay.WindMapOverlay.GROUP_NAME);
                if (windGrp != null) item = windGrp.deepFindUID(uid);
            }
        }

        if (item == null) {
            Toast.makeText(pluginContext, R.string.map_marker_no_data, Toast.LENGTH_SHORT).show();
            return;
        }

        // CotEventFactory.createCotEvent() serialises the MapItem to a CoT XML event.
        // It reads type, how, point, title and all meta-strings automatically.
        final CotEvent event = CotEventFactory.createCotEvent(item);
        if (event == null || !event.isValid()) {
            Toast.makeText(pluginContext, "Could not create CoT event for marker", Toast.LENGTH_SHORT).show();
            return;
        }

        // dispatchToBroadcast() sends to ALL connections: TAK servers + peer-to-peer
        CotMapComponent.getExternalDispatcher().dispatchToBroadcast(event);

        final String callsign = item.getMetaString("callsign", uid);
        Toast.makeText(pluginContext,
                "Shared: " + callsign, Toast.LENGTH_SHORT).show();
    }

    /**
     * Switches the TabHost to the named tab.
     * Valid names: "fcst", "wind", "map", "prm", "chrt", "cmp"
     */
    @SuppressWarnings("deprecation")
    private void jumpToTab(final String tabName) {
        if (tabHost == null) return;
        try {
            tabHost.setCurrentTabByTag(tabName);
        } catch (Exception e) {
            // setCurrentTabByTag throws if tag not found — fail silently
        }
    }

    // ── DropDownReceiver / OnStateListener ────────────────────────────────────

    @Override public void disposeImpl()                              {}
    @Override public void onDropDownSelectionRemoved()              {}
    @Override public void onDropDownVisible(boolean v)              {}
    @Override public void onDropDownSizeChanged(double w, double h) {}
    @Override public void onDropDownClose()                         {}
}
