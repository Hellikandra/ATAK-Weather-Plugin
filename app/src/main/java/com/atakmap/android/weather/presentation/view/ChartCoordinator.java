package com.atakmap.android.weather.presentation.view;

import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.LocationSnapshot;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.plugin.R;
import com.atakmap.android.weather.util.WeatherUiUtils;

import java.util.List;
import java.util.Locale;

/**
 * Coordinator for the Weather tab's chart section.
 *
 * <p>Extracted from {@code WeatherDropDownReceiver} (Sprint 21 — S21.3).
 * Manages: toggle buttons, zoom/range controls, chart readouts,
 * location header, and seekbar overlay sync.</p>
 */
public class ChartCoordinator {

    private final View rootView;
    private WeatherChartView chartView;
    private SeekBar chartOverlaySeekBar;
    private List<HourlyEntryModel> hourlyCache;
    private WeatherModel lastWeather;

    public ChartCoordinator(View rootView) {
        this.rootView = rootView;
    }

    /**
     * Initialize the chart and wire all controls.
     * Must be called after the WeatherChartView is created and added to chart_frame.
     */
    public void init(WeatherChartView chartView, SeekBar chartOverlaySeekBar) {
        this.chartView = chartView;
        this.chartOverlaySeekBar = chartOverlaySeekBar;
        wireChartToggleButtons();
        wireChartZoomAndRange();
    }

    public void setHourlyCache(List<HourlyEntryModel> cache) {
        this.hourlyCache = cache;
    }

    public void setLastWeather(WeatherModel w) {
        this.lastWeather = w;
    }

    // ── Toggle buttons ────────────────────────────────────────────────────

    private void wireChartToggleButtons() {
        if (chartView == null) return;
        int[] btnIds = {R.id.chart_toggle_temp, R.id.chart_toggle_humidity,
                R.id.chart_toggle_wind, R.id.chart_toggle_pressure};
        WeatherChartView.Series[] series = WeatherChartView.Series.values();
        for (int i = 0; i < btnIds.length && i < series.length; i++) {
            Button btn = rootView.findViewById(btnIds[i]);
            WeatherChartView.Series s = series[i];
            if (btn == null) continue;
            btn.setOnClickListener(v -> {
                boolean visible = chartView.toggleSeries(s);
                btn.setAlpha(visible ? 1.0f : 0.35f);
            });
        }
    }

    // ── Zoom & range controls ─────────────────────────────────────────────

    private void wireChartZoomAndRange() {
        if (chartView == null) return;

        final TextView zoomLabel = rootView.findViewById(R.id.chart_zoom_label);

        // Zoom buttons
        Button zoomIn  = rootView.findViewById(R.id.btn_chart_zoom_in);
        Button zoomOut = rootView.findViewById(R.id.btn_chart_zoom_out);
        if (zoomIn != null) zoomIn.setOnClickListener(v -> chartView.zoomIn());
        if (zoomOut != null) zoomOut.setOnClickListener(v -> chartView.zoomOut());

        // Time range buttons
        Button r24  = rootView.findViewById(R.id.btn_chart_range_24);
        Button r48  = rootView.findViewById(R.id.btn_chart_range_48);
        Button r72  = rootView.findViewById(R.id.btn_chart_range_72);
        Button rAll = rootView.findViewById(R.id.btn_chart_range_all);

        View.OnClickListener rangeClick = v -> {
            int hours = 0;
            if (v == r24)       hours = 24;
            else if (v == r48)  hours = 48;
            else if (v == r72)  hours = 72;

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
        if (rAll != null) { rAll.setOnClickListener(rangeClick); rAll.setAlpha(1.0f); }
        if (r24 != null) r24.setAlpha(0.4f);
        if (r48 != null) r48.setAlpha(0.4f);
        if (r72 != null) r72.setAlpha(0.4f);

        // Zoom label callback
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

    // ── Readouts ──────────────────────────────────────────────────────────

    public void updateChartReadouts(int index) {
        if (chartView == null) return;
        TextView valTemp     = rootView.findViewById(R.id.chart_val_temp);
        TextView valHumidity = rootView.findViewById(R.id.chart_val_humidity);
        TextView valWind     = rootView.findViewById(R.id.chart_val_wind);
        TextView valPressure = rootView.findViewById(R.id.chart_val_pressure);
        TextView hourLabel   = rootView.findViewById(R.id.chart_hour_label);
        if (hourLabel != null && hourlyCache != null
                && index >= 0 && index < hourlyCache.size()) {
            String iso     = hourlyCache.get(index).getIsoTime();
            String dayName = WeatherUiUtils.isoDayOfWeek(iso);
            String timeStr = iso.length() >= 16 ? iso.substring(11, 16) : iso;
            hourLabel.setText(dayName.isEmpty() ? timeStr : dayName + " " + timeStr);
        } else if (hourLabel != null) {
            hourLabel.setText("--:--");
        }
        setReadout(valTemp,     chartView.valueAt(WeatherChartView.Series.TEMPERATURE, index), "%.1f\u00B0");
        setReadout(valHumidity, chartView.valueAt(WeatherChartView.Series.HUMIDITY,    index), "%.0f%%");
        setReadout(valWind,     chartView.valueAt(WeatherChartView.Series.WIND,        index), "%.1f m/s");
        setReadout(valPressure, chartView.valueAt(WeatherChartView.Series.PRESSURE,    index), "%.0f hPa");
    }

    private static void setReadout(TextView tv, double val, String fmt) {
        if (tv != null && !Double.isNaN(val)) tv.setText(String.format(Locale.US, fmt, val));
    }

    // ── Location header ───────────────────────────────────────────────────

    public void updateChartLocationHeader(LocationSnapshot snapshot) {
        TextView locLabel = rootView.findViewById(R.id.chart_location_label);
        TextView tsLabel  = rootView.findViewById(R.id.chart_timestamp_label);
        if (locLabel != null && snapshot != null) locLabel.setText(snapshot.getDisplayName());
        if (tsLabel != null && lastWeather != null) {
            String ts      = lastWeather.getRequestTimestamp();
            String dayName = WeatherUiUtils.isoDayOfWeek(ts);
            tsLabel.setText(dayName.isEmpty() ? ts : dayName + "  " + ts);
        }
    }
}
