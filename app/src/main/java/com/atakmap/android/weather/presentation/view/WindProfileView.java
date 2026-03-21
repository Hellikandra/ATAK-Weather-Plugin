package com.atakmap.android.weather.presentation.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.atakmap.android.weather.domain.model.WindProfileModel;
import com.atakmap.android.weather.plugin.R;
import com.atakmap.android.weather.presentation.viewmodel.WindProfileViewModel;

import java.util.List;
import java.util.Locale;

/**
 * View helper for Tab 2 — Wind Profile.
 *
 * Sprint 26 additions:
 *   Multi-slot tab strip — a horizontal row of compact toggle buttons, one per
 *   WindSlot, rendered programmatically above the WindChartView.  Tapping a tab
 *   sets that slot as active; long-pressing removes it.
 *
 *   A "×" close button on each tab allows easy removal without long-press.
 *
 *   The Range and Height seekbars now control the ACTIVE slot only.
 *   The Hour seekbar is shared and drives all slots' chart display + 3D shapes.
 */
public class WindProfileView {

    /** Callback interface so the DDR can react to tab changes without coupling. */
    public interface SlotTabListener {
        void onSlotSelected(int slotIndex);
        void onSlotRemoved(int slotIndex);
    }

    private final TextView       textWindData;
    private final Button         buttonRequest;
    private       WindChartView  windChartView;
    private       SeekBar        windSeekBar;
    private       LinearLayout   slotTabStrip;   // dynamically populated
    private       SlotTabListener tabListener;
    private       List<WindProfileModel> profilesCache;

    private static final int TAB_ACTIVE_BG   = 0xFF2979FF;
    private static final int TAB_INACTIVE_BG = 0xFF333344;
    private static final int TAB_LOADING_BG  = 0xFF555533;
    private static final int TAB_ERROR_BG    = 0xFF553333;

    public WindProfileView(View root) {
        textWindData  = root.findViewById(R.id.textview_tab3_waiting_json_data);
        buttonRequest = root.findViewById(R.id.wind_update_information_button);
        slotTabStrip  = root.findViewById(R.id.wind_slot_tab_strip);

        FrameLayout chartFrame = root.findViewById(R.id.wind_chart_frame);
        if (chartFrame != null) {
            windChartView = new WindChartView(root.getContext());
            chartFrame.addView(windChartView,
                    new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT));
        }

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

    // ── Public API ─────────────────────────────────────────────────────────

    public void setSlotTabListener(SlotTabListener l) { this.tabListener = l; }

    /** Expose the WindChartView for direct configuration (e.g. setAltitudes, setSourceLabel). */
    public com.atakmap.android.weather.presentation.view.WindChartView getWindChart() {
        return windChartView;
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

    public void onHourChanged(int hour) {
        if (windChartView != null) windChartView.setSelectedHour(hour);
        updateTextTable(hour);
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

    /**
     * Rebuild the slot tab strip from the current slot list.
     * @param slots      current slot list from WindProfileViewModel
     * @param activeIdx  which slot is active (-1 = none)
     */
    public void rebuildSlotTabs(List<WindProfileViewModel.WindSlot> slots, int activeIdx) {
        if (slotTabStrip == null) return;
        slotTabStrip.removeAllViews();

        if (slots == null || slots.isEmpty()) {
            slotTabStrip.setVisibility(View.GONE);
            return;
        }
        slotTabStrip.setVisibility(View.VISIBLE);

        Context ctx = slotTabStrip.getContext();
        for (int i = 0; i < slots.size(); i++) {
            WindProfileViewModel.WindSlot slot = slots.get(i);
            final int slotIdx = i;

            // Container row: [label button] [× button]
            LinearLayout cell = new LinearLayout(ctx);
            cell.setOrientation(LinearLayout.HORIZONTAL);
            cell.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            cellLp.setMargins(2, 0, 2, 0);
            cell.setLayoutParams(cellLp);

            // Label button — selects this slot
            Button labelBtn = new Button(ctx);
            labelBtn.setText(shortLabel(slot));
            labelBtn.setTextSize(9f);
            labelBtn.setTypeface(Typeface.MONOSPACE);
            labelBtn.setMaxLines(1);
            labelBtn.setSingleLine(true);
            labelBtn.setPadding(4, 2, 4, 2);
            int bgColor = slot.loading ? TAB_LOADING_BG
                    : (slot.error != null ? TAB_ERROR_BG
                    : (slotIdx == activeIdx ? TAB_ACTIVE_BG : TAB_INACTIVE_BG));
            labelBtn.setBackgroundColor(bgColor);
            labelBtn.setTextColor(Color.WHITE);
            LinearLayout.LayoutParams lblLp = new LinearLayout.LayoutParams(
                    0, 52, 1f);
            labelBtn.setLayoutParams(lblLp);
            labelBtn.setOnClickListener(v -> {
                if (tabListener != null) tabListener.onSlotSelected(slotIdx);
            });

            // × button — removes this slot
            Button closeBtn = new Button(ctx);
            closeBtn.setText("×");
            closeBtn.setTextSize(11f);
            closeBtn.setTextColor(Color.parseColor("#FFAAAAAA"));
            closeBtn.setBackgroundColor(Color.TRANSPARENT);
            closeBtn.setPadding(2, 0, 2, 0);
            LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(36, 52);
            closeBtn.setLayoutParams(closeLp);
            closeBtn.setOnClickListener(v -> {
                if (tabListener != null) tabListener.onSlotRemoved(slotIdx);
            });

            cell.addView(labelBtn);
            cell.addView(closeBtn);
            slotTabStrip.addView(cell);
        }
    }

    // ── Private ────────────────────────────────────────────────────────────

    private String shortLabel(WindProfileViewModel.WindSlot slot) {
        // Build a short source abbreviation: first letters of each word in sourceId
        String src = "";
        if (slot.getSourceId() != null && !slot.getSourceId().isEmpty()) {
            // e.g. "open-meteo" → "OM", "aviation-weather" → "AW", "dwd-icon" → "DI"
            StringBuilder abbr = new StringBuilder();
            boolean nextUpper = true;
            for (char ch : slot.getSourceId().toCharArray()) {
                if (ch == '-' || ch == '_' || ch == ' ') { nextUpper = true; }
                else if (nextUpper) { abbr.append(Character.toUpperCase(ch)); nextUpper = false; }
            }
            if (abbr.length() > 0) src = " [" + abbr + "]";
        }
        if (slot.loading) return "⟳ " + slot.label + src;
        if (slot.error   != null) return "✗ " + slot.label;
        return slot.label + src;
    }

    @SuppressLint("SetTextI18n")
    private void updateTextTable(int hour) {
        if (textWindData == null || profilesCache == null || profilesCache.isEmpty()) return;
        int idx = Math.min(Math.max(0, hour), profilesCache.size() - 1);
        WindProfileModel frame = profilesCache.get(idx);

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
