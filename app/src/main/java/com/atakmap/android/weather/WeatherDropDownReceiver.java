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
import com.atakmap.android.weather.domain.repository.IWeatherRepository;
import com.atakmap.android.weather.infrastructure.preferences.WeatherParameterPreferences;
import com.atakmap.android.weather.plugin.R;
import com.atakmap.android.weather.presentation.view.ComparisonView;
import com.atakmap.android.weather.presentation.view.CurrentWeatherView;
import com.atakmap.android.weather.presentation.view.DailyForecastView;
import com.atakmap.android.weather.presentation.view.ParametersView;
import com.atakmap.android.weather.presentation.view.WeatherChartView;
import com.atakmap.android.weather.presentation.view.WindProfileView;
import com.atakmap.android.weather.presentation.viewmodel.WeatherViewModel;
import com.atakmap.android.weather.presentation.viewmodel.WindProfileViewModel;

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
    public static final String SHOW_PLUGIN = "com.atakmap.android.weather.SHOW_PLUGIN";

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

    // ── Cached hourly list for SeekBar scrubbing ─────────────────────────────
    private List<HourlyEntryModel> hourlyCache;

    // ── Constructor ───────────────────────────────────────────────────────────

    public WeatherDropDownReceiver(final MapView mapView, final Context context) {
        super(mapView);
        this.pluginContext = context;
        // mapView.getContext() returns the host application's context which has a
        // valid ApplicationContext — required by Room.databaseBuilder().
        // pluginContext.getApplicationContext() returns null in the ATAK plugin
        // sandbox and must never be passed to Room.
        this.appContext    = mapView.getContext();
        templateView = PluginLayoutInflater.inflate(context, R.layout.main_layout, null);
    }

    // ── onReceive ─────────────────────────────────────────────────────────────

    @Override
    public void onReceive(final Context context, Intent intent) {
        final String action = intent.getAction();
        if (action == null || !action.equals(SHOW_PLUGIN)) return;

        showDropDown(templateView,
                HALF_WIDTH, FULL_HEIGHT,
                FULL_WIDTH, HALF_HEIGHT,
                false, this);

        if (!initialized) {
            initDependencies();
            initTabs();
            initViewHelpers();
            observeViewModels();
            initialized = true;
        }

        // Fallback chain: GPS → map centre
        triggerAutoLoad();
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
        android.widget.TabHost tabHost = templateView.findViewById(R.id.mainTabHost);
        tabHost.setup();

        android.widget.TabHost.TabSpec spec;

        spec = tabHost.newTabSpec("Fcst");
        spec.setContent(R.id.subTabWidget1);
        spec.setIndicator("Fcst");
        tabHost.addTab(spec);

        spec = tabHost.newTabSpec("Wind");
        spec.setContent(R.id.subTabWidget2);
        spec.setIndicator("Wind");
        tabHost.addTab(spec);

        spec = tabHost.newTabSpec("Map");
        spec.setContent(R.id.subTabWidget3);
        spec.setIndicator("Map");
        tabHost.addTab(spec);

        spec = tabHost.newTabSpec("Prm");
        spec.setContent(R.id.subTabWidget4);
        spec.setIndicator("Prm");
        tabHost.addTab(spec);

        spec = tabHost.newTabSpec("Chrt");
        spec.setContent(R.id.subTabWidget5);
        spec.setIndicator("Chrt");
        tabHost.addTab(spec);

        spec = tabHost.newTabSpec("Cmp");
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
                currentWeatherView.bindCurrentWeather(w, w.getRequestTimestamp());
            } else if (state.isError()) {
                currentWeatherView.showError(state.getErrorMessage());
            }
        });

        // Tab 1 — location header (name + coords + source tag)
        weatherViewModel.getActiveLocation().observeForever(snapshot -> {
            if (snapshot != null) currentWeatherView.bindLocation(snapshot);
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
        double selfLat = getMapView().getSelfMarker().getPoint().getLatitude();
        double selfLon = getMapView().getSelfMarker().getPoint().getLongitude();
        double cenLat  = getMapView().getCenterPoint().get().getLatitude();
        double cenLon  = getMapView().getCenterPoint().get().getLongitude();

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

    // ── DropDownReceiver / OnStateListener ────────────────────────────────────

    @Override public void disposeImpl()                              {}
    @Override public void onDropDownSelectionRemoved()              {}
    @Override public void onDropDownVisible(boolean v)              {}
    @Override public void onDropDownSizeChanged(double w, double h) {}
    @Override public void onDropDownClose()                         {}
}
