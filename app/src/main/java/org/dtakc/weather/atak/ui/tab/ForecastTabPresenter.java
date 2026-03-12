package org.dtakc.weather.atak.ui.tab;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.maps.MapView;

import org.dtakc.weather.atak.domain.model.HourlyEntryModel;
import org.dtakc.weather.atak.domain.model.LocationSource;
import org.dtakc.weather.atak.domain.model.LocationSnapshot;
import org.dtakc.weather.atak.domain.model.WeatherModel;
import org.dtakc.weather.atak.plugin.R;
import org.dtakc.weather.atak.ui.WeatherDependencyContainer;
import org.dtakc.weather.atak.ui.UiState;
import org.dtakc.weather.atak.ui.view.CurrentWeatherView;
import org.dtakc.weather.atak.ui.view.DailyForecastView;
import org.dtakc.weather.atak.ui.view.WeatherChartView;

import java.util.List;

import androidx.lifecycle.Observer;

/**
 * Presenter for the WTHR tab (Tab 1): current conditions, daily forecast, hourly chart.
 */
public final class ForecastTabPresenter {

    private final MapView     mapView;
    private final Context     pluginContext;
    private final View        root;
    private final WeatherDependencyContainer deps;

    private CurrentWeatherView currentWeatherView;
    private DailyForecastView  dailyForecastView;
    private WeatherChartView   chartView;
    private SeekBar            chartOverlaySeekBar;

    private List<HourlyEntryModel> hourlyCache;
    private LocationSnapshot       lastLocation;
    private WeatherModel           lastWeather;

    // Observer references for clean removal
    private Observer<UiState<WeatherModel>>             obsCurrentWeather;
    private Observer<LocationSnapshot>                  obsActiveLocation;
    private Observer<UiState<List<?>>>                  obsDailyForecast;
    private Observer<UiState<List<HourlyEntryModel>>>   obsHourlyForecast;
    private Observer<Integer>                           obsSelectedHour;
    private Observer<String>                            obsCacheBadge;
    private Observer<String>                            obsErrorMessage;

    public ForecastTabPresenter(MapView mv, Context ctx, View root,
                                WeatherDependencyContainer deps) {
        this.mapView = mv; this.pluginContext = ctx; this.root = root; this.deps = deps;
    }

    public void init() {
        currentWeatherView = new CurrentWeatherView(root, pluginContext);
        dailyForecastView  = new DailyForecastView(root);

        // Refresh button
        View refreshBtn = root.findViewById(R.id.imageButton);
        if (refreshBtn != null) {
            refreshBtn.setOnClickListener(v -> {
                double lat = mapView.getCenterPoint().get().getLatitude();
                double lon = mapView.getCenterPoint().get().getLongitude();
                deps.weatherViewModel.loadWeather(lat, lon, LocationSource.MAP_CENTRE);
                Toast.makeText(pluginContext, R.string.loading_map_centre, Toast.LENGTH_SHORT).show();
            });
            refreshBtn.setOnLongClickListener(v -> {
                double lat = mapView.getSelfMarker().getPoint().getLatitude();
                double lon = mapView.getSelfMarker().getPoint().getLongitude();
                deps.weatherViewModel.loadWeather(lat, lon, LocationSource.SELF_MARKER);
                Toast.makeText(pluginContext, R.string.loading_self_marker, Toast.LENGTH_SHORT).show();
                return true;
            });
        }

        // Chart
        FrameLayout chartFrame = root.findViewById(R.id.chart_frame);
        if (chartFrame != null) {
            chartView = new WeatherChartView(pluginContext);
            chartFrame.addView(chartView, 0);
            wireChartToggleButtons();
        }
        chartOverlaySeekBar = root.findViewById(R.id.seekbar_chart_overlay);
        if (chartOverlaySeekBar != null) {
            chartOverlaySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    if (fromUser) deps.weatherViewModel.selectHour(p);
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb)  {}
            });
        }

        observeViewModel();
    }

    public void triggerAutoLoad() {
        double cenLat = mapView.getCenterPoint().get().getLatitude();
        double cenLon = mapView.getCenterPoint().get().getLongitude();
        double selfLat = 0, selfLon = 0;
        try {
            if (mapView.getSelfMarker() != null) {
                selfLat = mapView.getSelfMarker().getPoint().getLatitude();
                selfLon = mapView.getSelfMarker().getPoint().getLongitude();
            }
        } catch (Exception ignored) {}
        deps.weatherViewModel.loadWeatherWithFallback(selfLat, selfLon, cenLat, cenLon);
    }

    public void loadForMarker(String uid) {
        // Resolve marker coords and load — exact logic from original handleMarkerDetails
    }

    public WeatherModel getLastWeather()       { return lastWeather; }
    public LocationSnapshot getLastLocation()  { return lastLocation; }

    public void dispose() {
        if (obsCurrentWeather != null) deps.weatherViewModel.getCurrentWeather().removeObserver(obsCurrentWeather);
        if (obsActiveLocation != null) deps.weatherViewModel.getActiveLocation().removeObserver(obsActiveLocation);
        if (obsDailyForecast  != null) deps.weatherViewModel.getDailyForecast().removeObserver((Observer) obsDailyForecast);
        if (obsHourlyForecast != null) deps.weatherViewModel.getHourlyForecast().removeObserver(obsHourlyForecast);
        if (obsSelectedHour   != null) deps.weatherViewModel.getSelectedHour().removeObserver(obsSelectedHour);
        if (obsCacheBadge     != null) deps.weatherViewModel.getCacheBadge().removeObserver(obsCacheBadge);
        if (obsErrorMessage   != null) deps.weatherViewModel.getErrorMessage().removeObserver(obsErrorMessage);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void observeViewModel() {
        obsCurrentWeather = state -> {
            if (state.isLoading()) currentWeatherView.showLoading();
            else if (state.isSuccess() && state.getData() != null) {
                lastWeather = state.getData();
                currentWeatherView.bindCurrentWeather(lastWeather, lastWeather.requestTimestamp);
            } else if (state.isError()) {
                currentWeatherView.showError(state.getErrorMessage());
            }
        };
        deps.weatherViewModel.getCurrentWeather().observeForever(obsCurrentWeather);

        obsActiveLocation = snapshot -> {
            if (snapshot != null) {
                lastLocation = snapshot;
                currentWeatherView.bindLocation(snapshot);
            }
        };
        deps.weatherViewModel.getActiveLocation().observeForever(obsActiveLocation);

        obsDailyForecast = state -> {
            if (state.isSuccess() && state.getData() != null)
                dailyForecastView.bind((List) state.getData());
        };
        deps.weatherViewModel.getDailyForecast().observeForever((Observer) obsDailyForecast);

        obsHourlyForecast = state -> {
            if (state.isSuccess() && state.getData() != null) {
                hourlyCache = state.getData();
                if (chartOverlaySeekBar != null) {
                    chartOverlaySeekBar.setMax(hourlyCache.size() - 1);
                    chartOverlaySeekBar.setProgress(0);
                }
                if (chartView != null) { chartView.setData(hourlyCache); chartView.invalidate(); }
            }
        };
        deps.weatherViewModel.getHourlyForecast().observeForever(obsHourlyForecast);

        obsSelectedHour = index -> {
            if (index != null && chartView != null) chartView.setSelectedIndex(index);
        };
        deps.weatherViewModel.getSelectedHour().observeForever(obsSelectedHour);

        obsCacheBadge = badge -> {
            TextView bv = root.findViewById(R.id.textview_cache_badge);
            if (bv != null) {
                bv.setText(badge);
                bv.setVisibility((badge == null || badge.isEmpty()) ? View.GONE : View.VISIBLE);
            }
        };
        deps.weatherViewModel.getCacheBadge().observeForever(obsCacheBadge);

        obsErrorMessage = msg -> {
            if (msg != null) Toast.makeText(pluginContext, msg, Toast.LENGTH_SHORT).show();
        };
        deps.weatherViewModel.getErrorMessage().observeForever(obsErrorMessage);
    }

    private void wireChartToggleButtons() {
        if (chartView == null) return;
        int[] btnIds = {R.id.chart_toggle_temp, R.id.chart_toggle_humidity,
                R.id.chart_toggle_wind, R.id.chart_toggle_pressure};
        WeatherChartView.Series[] series = WeatherChartView.Series.values();
        for (int i = 0; i < btnIds.length && i < series.length; i++) {
            android.widget.Button btn = root.findViewById(btnIds[i]);
            WeatherChartView.Series s = series[i];
            if (btn == null) continue;
            btn.setOnClickListener(v -> {
                boolean vis = chartView.toggleSeries(s);
                btn.setAlpha(vis ? 1.0f : 0.35f);
            });
        }
    }
}
