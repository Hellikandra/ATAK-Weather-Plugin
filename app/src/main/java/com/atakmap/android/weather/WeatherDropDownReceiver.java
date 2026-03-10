package com.atakmap.android.weather;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.SeekBar;
import android.widget.Toast;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.weather.data.WeatherRepositoryImpl;
import com.atakmap.android.weather.data.geocoding.NominatimGeocodingSource;
import com.atakmap.android.weather.data.remote.IWeatherRemoteSource;
import com.atakmap.android.weather.data.remote.OpenMeteoSource;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.repository.IGeocodingRepository;
import com.atakmap.android.weather.domain.repository.IWeatherRepository;
import com.atakmap.android.weather.plugin.R;
import com.atakmap.android.weather.presentation.view.CurrentWeatherView;
import com.atakmap.android.weather.presentation.view.DailyForecastView;
import com.atakmap.android.weather.presentation.view.WindProfileView;
import com.atakmap.android.weather.presentation.viewmodel.WeatherViewModel;
import com.atakmap.android.weather.presentation.viewmodel.WindProfileViewModel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WeatherDropDownReceiver — ATAK drop-down entry point.
 *
 * KEY ATAK RULES this file must obey (and why the dropdown was broken):
 *
 *  1. PACKAGE  — must stay in com.atakmap.android.weather (root), NOT in
 *                presentation.view. WeatherMapComponent references this class
 *                directly and registers the SHOW_PLUGIN intent filter.
 *
 *  2. INFLATER — must use PluginLayoutInflater.inflate(), NOT
 *                LayoutInflater.from(context).inflate(). Plugin APK layouts
 *                live in the plugin classloader; the standard LayoutInflater
 *                uses the host ATAK classloader and silently returns null,
 *                causing showDropDown() to receive a null view and do nothing.
 *
 *  3. SHOW_PLUGIN — the intent action string must match exactly what
 *                WeatherMapComponent puts in the IntentFilter. Keep it as
 *                "com.atakmap.android.weather.SHOW_PLUGIN".
 *
 *  4. INFLATE IN CONSTRUCTOR — PluginLayoutInflater.inflate() must be called
 *                in the constructor (before onReceive). Calling it inside
 *                onReceive on every open leaks views and breaks tab state.
 */
public class WeatherDropDownReceiver extends DropDownReceiver
        implements OnStateListener {

    public static final String TAG         = WeatherDropDownReceiver.class.getSimpleName();
    public static final String SHOW_PLUGIN = "com.atakmap.android.weather.SHOW_PLUGIN";

    // ── Layout ──────────────────────────────────────────────────────────────
    // Inflated ONCE in the constructor using PluginLayoutInflater.
    // showDropDown() will receive this same view on every open.
    private final View    templateView;
    private final Context pluginContext;

    // ── ViewModels ───────────────────────────────────────────────────────────
    private WeatherViewModel     weatherViewModel;
    private WindProfileViewModel windViewModel;

    // ── View helpers ─────────────────────────────────────────────────────────
    private CurrentWeatherView currentWeatherView;
    private DailyForecastView  dailyForecastView;
    private WindProfileView    windProfileView;

    // ── Init guard — only wire up observers once ──────────────────────────────
    private boolean initialized = false;

    // ── Cached hourly list for SeekBar scrubbing ──────────────────────────────
    private List<HourlyEntryModel> hourlyCache;

    // ── Constructor ───────────────────────────────────────────────────────────

    public WeatherDropDownReceiver(final MapView mapView, final Context context) {
        super(mapView);
        this.pluginContext = context;
        // PluginLayoutInflater is mandatory for plugin APK layouts.
        // Standard LayoutInflater uses the host classloader and will silently
        // fail to find R.layout.main_layout, resulting in a null view.
        templateView = PluginLayoutInflater.inflate(context, R.layout.main_layout, null);
    }

    // ── onReceive ─────────────────────────────────────────────────────────────

    @Override
    public void onReceive(final Context context, Intent intent) {
        final String action = intent.getAction();
        if (action == null || !action.equals(SHOW_PLUGIN)) return;

        // Show the dropdown. ATAK's DropDownManager will display templateView.
        showDropDown(templateView,
                HALF_WIDTH, FULL_HEIGHT,
                FULL_WIDTH, HALF_HEIGHT,
                false, this);

        // Wire everything up on first open only.
        if (!initialized) {
            initDependencies();
            initTabs();
            initViewHelpers();
            observeViewModels();
            initialized = true;
        }

        // Trigger initial data load for device position.
        loadWeatherForSelfMarker();
    }

    // ── Dependency wiring ────────────────────────────────────────────────────

    private void initDependencies() {
        OpenMeteoSource openMeteoSource = new OpenMeteoSource();

        Map<String, IWeatherRemoteSource> sources = new HashMap<>();
        sources.put(OpenMeteoSource.SOURCE_ID, openMeteoSource);

        IWeatherRepository   weatherRepo   = new WeatherRepositoryImpl(sources, OpenMeteoSource.SOURCE_ID);
        IGeocodingRepository geocodingRepo = new NominatimGeocodingSource();

        // Direct construction — ATAK plugins don't use ViewModelProvider.
        weatherViewModel = new WeatherViewModel(weatherRepo, geocodingRepo);
        windViewModel    = new WindProfileViewModel(weatherRepo);
    }

    @SuppressWarnings("deprecation")
    private void initTabs() {
        android.widget.TabHost tabHost = templateView.findViewById(R.id.mainTabHost);
        tabHost.setup();

        android.widget.TabHost.TabSpec spec;

        spec = tabHost.newTabSpec("Forecasting");
        spec.setContent(R.id.subTabWidget1);
        spec.setIndicator("Forecasting");
        tabHost.addTab(spec);

        spec = tabHost.newTabSpec("Wind");
        spec.setContent(R.id.subTabWidget2);
        spec.setIndicator("Wind");
        tabHost.addTab(spec);

        spec = tabHost.newTabSpec("Overlay");
        spec.setContent(R.id.subTabWidget3);
        spec.setIndicator("Overlay");
        tabHost.addTab(spec);
    }

    private void initViewHelpers() {
        currentWeatherView = new CurrentWeatherView(templateView, pluginContext);
        dailyForecastView  = new DailyForecastView(templateView);
        windProfileView    = new WindProfileView(templateView);

        templateView.findViewById(R.id.imageButton)
                .setOnClickListener(v -> loadWeatherForMapCenter());

        windProfileView.setRequestClickListener(v -> loadWindForMapCenter());
    }

    // ── LiveData observers ────────────────────────────────────────────────────

    @SuppressLint("SetTextI18n")
    private void observeViewModels() {

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

        weatherViewModel.getLocationName().observeForever(name -> {
            if (name != null) currentWeatherView.bindLocationName(name);
        });

        weatherViewModel.getDailyForecast().observeForever(state -> {
            if (state.isSuccess() && state.getData() != null) {
                dailyForecastView.bind(state.getData());
            }
        });

        weatherViewModel.getHourlyForecast().observeForever(state -> {
            if (state.isSuccess() && state.getData() != null) {
                hourlyCache = state.getData();
                currentWeatherView.configureSeekBar(
                        hourlyCache.size() - 1,
                        new SeekBar.OnSeekBarChangeListener() {
                            @Override public void onProgressChanged(SeekBar bar, int i, boolean fromUser) {
                                weatherViewModel.selectHour(i);
                            }
                            @Override public void onStartTrackingTouch(SeekBar bar) {}
                            @Override public void onStopTrackingTouch(SeekBar bar) {}
                        });
            }
        });

        weatherViewModel.getSelectedHour().observeForever(index -> {
            if (hourlyCache != null && index != null
                    && index >= 0 && index < hourlyCache.size()) {
                HourlyEntryModel entry = hourlyCache.get(index);
                String label = "+" + index + "h  " + entry.getIsoTime().replace("T", " ");
                currentWeatherView.bindHourlyEntry(entry, label);
            }
        });

        weatherViewModel.getErrorMessage().observeForever(msg -> {
            if (msg != null) Toast.makeText(pluginContext, msg, Toast.LENGTH_SHORT).show();
        });

        windViewModel.getWindProfile().observeForever(state -> {
            if (state.isLoading()) {
                windProfileView.showLoading();
            } else if (state.isSuccess() && state.getData() != null) {
                windProfileView.bind(state.getData());
            } else if (state.isError()) {
                windProfileView.showError(state.getErrorMessage());
            }
        });
    }

    // ── Data triggers ─────────────────────────────────────────────────────────

    private void loadWeatherForSelfMarker() {
        double lat = getMapView().getSelfMarker().getPoint().getLatitude();
        double lon = getMapView().getSelfMarker().getPoint().getLongitude();
        if (lat == 0 && lon == 0) {
            Toast.makeText(pluginContext, R.string.set_gps, Toast.LENGTH_SHORT).show();
            return;
        }
        weatherViewModel.loadWeather(lat, lon);
    }

    private void loadWeatherForMapCenter() {
        double lat = getMapView().getCenterPoint().get().getLatitude();
        double lon = getMapView().getCenterPoint().get().getLongitude();
        weatherViewModel.loadWeather(lat, lon);
    }

    private void loadWindForMapCenter() {
        double lat = getMapView().getCenterPoint().get().getLatitude();
        double lon = getMapView().getCenterPoint().get().getLongitude();
        windViewModel.loadWindProfile(lat, lon);
    }

    // ── DropDownReceiver / OnStateListener ────────────────────────────────────

    @Override public void disposeImpl() {
        // observeForever observers are intentionally left — this receiver lives
        // for the plugin lifetime. LiveData holds weak refs; GC handles cleanup.
    }

    @Override public void onDropDownSelectionRemoved() {}
    @Override public void onDropDownVisible(boolean v)              {}
    @Override public void onDropDownSizeChanged(double w, double h) {}
    @Override public void onDropDownClose()                         {}
}
