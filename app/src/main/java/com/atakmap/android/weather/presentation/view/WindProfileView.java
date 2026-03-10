package com.atakmap.android.weather.presentation.view;

import android.annotation.SuppressLint;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.atakmap.android.weather.domain.model.WindProfileModel;
import com.atakmap.android.weather.plugin.R;

import java.util.List;
import java.util.Locale;

/**
 * View helper for Tab 3 (Wind Profile).
 *
 * Replaces the raw JSON dump with a formatted, human-readable table.
 */
public class WindProfileView {

    private final TextView textWindData;
    private final Button   buttonRequest;

    public WindProfileView(View root) {
        textWindData  = root.findViewById(R.id.textview_tab3_waiting_json_data);
        buttonRequest = root.findViewById(R.id.wind_update_information_button);
    }

    public void setRequestClickListener(View.OnClickListener listener) {
        buttonRequest.setOnClickListener(listener);
    }

    public void showLoading() {
        textWindData.setText(R.string.wait);
    }

    public void showError(String message) {
        textWindData.setText("Error: " + message);
    }

    @SuppressLint("SetTextI18n")
    public void bind(List<WindProfileModel> profiles) {
        if (profiles == null || profiles.isEmpty()) {
            textWindData.setText("No wind data available.");
            return;
        }

        // Display first hour entry as a formatted altitude table
        WindProfileModel first = profiles.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append("Wind Profile — ").append(first.getIsoTime()).append("\n\n");
        sb.append(String.format(Locale.getDefault(),
                "%-8s %-10s %-10s %-10s %-10s\n",
                "Alt(m)", "Speed", "Dir", "Temp", "Gusts"));
        sb.append("─────────────────────────────────────────\n");

        for (WindProfileModel.AltitudeEntry e : first.getAltitudes()) {
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
