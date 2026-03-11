package com.atakmap.android.weather.presentation.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.Button;
import android.widget.TextView;

import com.atakmap.android.weather.domain.model.WindProfileModel;
import com.atakmap.android.weather.plugin.R;

import java.util.List;
import java.util.Locale;

/**
 * View helper for Tab 2 — Wind Profile.
 *
 * ── Sprint 5 additions ────────────────────────────────────────────────────────
 *
 * 1. WindChartView injected into R.id.wind_chart_frame
 *    The Canvas barb column is injected at runtime (same pattern as
 *    WeatherChartView in Tab 5) so no custom View subclass appears in XML.
 *
 * 2. Hour SeekBar (R.id.wind_seekbar)
 *    The user can scrub through the 168-hour forecast to see how the
 *    wind profile changes over time. setSelectedHour() drives WindChartView.
 *
 * 3. The existing monospaced text table (R.id.textview_tab3_waiting_json_data)
 *    is kept below the chart as a data-dense fallback for power users.
 */
public class WindProfileView {

    private final TextView   textWindData;
    private final Button     buttonRequest;
    private       WindChartView windChartView;  // injected at runtime

    // ── Sprint 5: hour scrubber ───────────────────────────────────────────
    private SeekBar          windSeekBar;
    private List<WindProfileModel> profilesCache;

    public WindProfileView(View root) {
        textWindData  = root.findViewById(R.id.textview_tab3_waiting_json_data);
        buttonRequest = root.findViewById(R.id.wind_update_information_button);

        // Inject WindChartView into the placeholder FrameLayout
        FrameLayout chartFrame = root.findViewById(R.id.wind_chart_frame);
        if (chartFrame != null) {
            windChartView = new WindChartView(root.getContext());
            chartFrame.addView(windChartView,
                    new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT));
        }

        // Wire hour SeekBar
        windSeekBar = root.findViewById(R.id.wind_seekbar);
        if (windSeekBar != null) {
            windSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int i, boolean fromUser) {
                    if (windChartView != null) windChartView.setSelectedHour(i);
                    updateTextTable(i);
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb)  {}
            });
        }
    }

    /**
     * Called by WeatherDropDownReceiver when the wind hour seekbar changes.
     * (Receiver replaces the view's listener to add live wind-effect redraw.)
     */
    public void onHourChanged(int hour) {
        if (windChartView != null) windChartView.setSelectedHour(hour);
        updateTextTable(hour);
    }

    public void setRequestClickListener(View.OnClickListener listener) {
        if (buttonRequest != null) buttonRequest.setOnClickListener(listener);
    }

    public void showLoading() {
        if (textWindData != null) textWindData.setText(R.string.wait);
    }

    public void showError(String message) {
        if (textWindData != null) textWindData.setText("Error: " + message);
    }

    @SuppressLint("SetTextI18n")
    public void bind(List<WindProfileModel> profiles) {
        profilesCache = profiles;

        if (windChartView != null) {
            windChartView.setProfiles(profiles);
            windChartView.setSelectedHour(0);
        }
        if (windSeekBar != null) {
            windSeekBar.setMax(profiles.size() - 1);
            windSeekBar.setProgress(0);
        }
        updateTextTable(0);
    }

    // ── Private ────────────────────────────────────────────────────────────────

    @SuppressLint("SetTextI18n")
    private void updateTextTable(int hour) {
        if (textWindData == null || profilesCache == null || profilesCache.isEmpty()) return;
        int idx = Math.min(hour, profilesCache.size() - 1);
        WindProfileModel frame = profilesCache.get(Math.max(0, idx));

        StringBuilder sb = new StringBuilder();
        sb.append("Wind Profile — ").append(frame.getIsoTime()).append("\n\n");
        sb.append(String.format(Locale.getDefault(),
                "%-8s %-10s %-10s %-10s %-10s\n",
                "Alt(m)", "Speed", "Dir", "Temp", "Gusts"));
        sb.append("─────────────────────────────────────────\n");

        for (WindProfileModel.AltitudeEntry e : frame.getAltitudes()) {
            sb.append(String.format(Locale.getDefault(),
                    "%-8d %-10s %-10s %-10s %-10s\n",
                    e.altitudeMeters,
                    String.format("%.1f m/s", e.windSpeed),
                    String.format("%.0f°",    e.windDirection),
                    String.format("%.1f°C",   e.temperature),
                    e.altitudeMeters == 10
                            ? String.format("%.1f m/s", e.windGusts)
                            : "—"));
        }
        textWindData.setText(sb.toString());
    }
}
