package com.atakmap.android.weather.presentation.view;

import android.annotation.SuppressLint;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.atakmap.android.weather.domain.model.ComparisonModel;
import com.atakmap.android.weather.domain.model.LocationSnapshot;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.util.WmoCodeMapper;
import com.atakmap.android.weather.plugin.R;

/**
 * View helper for Tab 6 — Self Marker vs Map Centre comparison.
 *
 * Sprint 1 changes:
 *  - bindSelfSnapshot(LocationSnapshot) / bindCenterSnapshot(LocationSnapshot)
 *    replace the plain-String bindSelfLocation / bindCenterLocation methods.
 *    Each card now shows:  name (line 1) + coords (line 2) + source label (tag)
 *    so the user can confirm the data provenance at a glance.
 *  - Auto-populated on first open (triggered from WeatherViewModel after the
 *    first successful Tab-1 load — no need to tap the refresh button).
 */
public class ComparisonView {

    // ── Self card ──────────────────────────────────────────────────────────
    private final TextView  selfLocation;
    private final TextView  selfCoords;     // NEW
    private final TextView  selfSourceTag;  // NEW: "Self"
    private final ImageView selfIcon;
    private final TextView  selfWeatherDesc;
    private final TextView  selfTemp;
    private final TextView  selfFeels;
    private final TextView  selfHumidity;
    private final TextView  selfPressure;
    private final TextView  selfWind;
    private final TextView  selfPrecip;

    // ── Map centre card ────────────────────────────────────────────────────
    private final TextView  centerLocation;
    private final TextView  centerCoords;   // NEW
    private final TextView  centerSourceTag;// NEW: "Map centre"
    private final ImageView centerIcon;
    private final TextView  centerWeatherDesc;
    private final TextView  centerTemp;
    private final TextView  centerFeels;
    private final TextView  centerHumidity;
    private final TextView  centerPressure;
    private final TextView  centerWind;
    private final TextView  centerPrecip;

    // ── Delta row ──────────────────────────────────────────────────────────
    private final TextView  deltaTemp;
    private final TextView  deltaHumidity;
    private final TextView  deltaPressure;
    private final TextView  deltaWind;
    private final TextView  deltaPrecip;

    // ── Status ─────────────────────────────────────────────────────────────
    private final TextView  statusText;

    // ── Constructor ────────────────────────────────────────────────────────

    public ComparisonView(View root) {
        selfLocation      = root.findViewById(R.id.comp_self_location);
        selfCoords        = root.findViewById(R.id.comp_self_coords);
        selfSourceTag     = root.findViewById(R.id.comp_self_source_tag);
        selfIcon          = root.findViewById(R.id.comp_self_icon);
        selfWeatherDesc   = root.findViewById(R.id.comp_self_weather_desc);
        selfTemp          = root.findViewById(R.id.comp_self_temp);
        selfFeels         = root.findViewById(R.id.comp_self_feels);
        selfHumidity      = root.findViewById(R.id.comp_self_humidity);
        selfPressure      = root.findViewById(R.id.comp_self_pressure);
        selfWind          = root.findViewById(R.id.comp_self_wind);
        selfPrecip        = root.findViewById(R.id.comp_self_precip);

        centerLocation    = root.findViewById(R.id.comp_center_location);
        centerCoords      = root.findViewById(R.id.comp_center_coords);
        centerSourceTag   = root.findViewById(R.id.comp_center_source_tag);
        centerIcon        = root.findViewById(R.id.comp_center_icon);
        centerWeatherDesc = root.findViewById(R.id.comp_center_weather_desc);
        centerTemp        = root.findViewById(R.id.comp_center_temp);
        centerFeels       = root.findViewById(R.id.comp_center_feels);
        centerHumidity    = root.findViewById(R.id.comp_center_humidity);
        centerPressure    = root.findViewById(R.id.comp_center_pressure);
        centerWind        = root.findViewById(R.id.comp_center_wind);
        centerPrecip      = root.findViewById(R.id.comp_center_precip);

        deltaTemp         = root.findViewById(R.id.comp_delta_temp);
        deltaHumidity     = root.findViewById(R.id.comp_delta_humidity);
        deltaPressure     = root.findViewById(R.id.comp_delta_pressure);
        deltaWind         = root.findViewById(R.id.comp_delta_wind);
        deltaPrecip       = root.findViewById(R.id.comp_delta_precip);

        statusText        = root.findViewById(R.id.comp_status_text);
    }

    // ── Binding ────────────────────────────────────────────────────────────

    public void showLoading() {
        if (statusText != null) {
            statusText.setVisibility(View.VISIBLE);
            statusText.setText(R.string.wait);
        }
    }

    public void showError(String msg) {
        if (statusText != null) {
            statusText.setVisibility(View.VISIBLE);
            statusText.setText("Error: " + msg);
        }
    }

    @SuppressLint("SetTextI18n")
    public void bind(ComparisonModel model) {
        if (statusText != null) statusText.setVisibility(View.GONE);
        bindCard(model.selfMarker,
                selfLocation, selfCoords, selfSourceTag,
                selfIcon, selfWeatherDesc,
                selfTemp, selfFeels, selfHumidity,
                selfPressure, selfWind, selfPrecip);
        bindCard(model.mapCenter,
                centerLocation, centerCoords, centerSourceTag,
                centerIcon, centerWeatherDesc,
                centerTemp, centerFeels, centerHumidity,
                centerPressure, centerWind, centerPrecip);
        bindDeltas(model);
    }

    /**
     * Update the location header on the self card independently
     * (geocoding result arrives asynchronously).
     */
    public void bindSelfSnapshot(LocationSnapshot snapshot) {
        setText(selfLocation,  snapshot.getDisplayName());
        setText(selfCoords,    snapshot.getCoordsLabel());
        setText(selfSourceTag, snapshot.getSource().label);
    }

    /**
     * Update the location header on the centre card independently.
     */
    public void bindCenterSnapshot(LocationSnapshot snapshot) {
        setText(centerLocation,  snapshot.getDisplayName());
        setText(centerCoords,    snapshot.getCoordsLabel());
        setText(centerSourceTag, snapshot.getSource().label);
    }

    // ── Private ────────────────────────────────────────────────────────────

    @SuppressLint("SetTextI18n")
    private void bindCard(WeatherModel w,
                          TextView location, TextView coords, TextView sourceTag,
                          ImageView icon, TextView desc,
                          TextView temp, TextView feels, TextView humidity,
                          TextView pressure, TextView wind, TextView precip) {
        // Location header rows are populated separately via bindSelfSnapshot /
        // bindCenterSnapshot as geocoding arrives asynchronously.
        // Coords shows the model's own lat/lon as a fallback until geocoding arrives.
        if (coords != null && w.getLatitude() != 0)
            coords.setText(String.format("%.4f, %.4f", w.getLatitude(), w.getLongitude()));

        WmoCodeMapper.WmoInfo info = WmoCodeMapper.resolve(w.getWeatherCode());
        if (icon != null) icon.setImageResource(info.drawableResId);
        if (desc != null) desc.setText(info.labelResId);

        setText(temp,     String.format("%.1f / %.1f°C", w.getTemperatureMin(), w.getTemperatureMax()));
        setText(feels,    String.format("%.1f°C",  w.getApparentTemperature()));
        setText(humidity, String.format("%.0f %%", w.getHumidity()));
        setText(pressure, String.format("%.1f hPa",w.getPressure()));
        setText(wind,     String.format("%.1f m/s  %.0f°", w.getWindSpeed(), w.getWindDirection()));
        setText(precip,   w.getPrecipitationSum() == 0
                ? "—"
                : String.format("%.1f mm", w.getPrecipitationSum()));
    }

    @SuppressLint("SetTextI18n")
    private void bindDeltas(ComparisonModel m) {
        setText(deltaTemp,      "ΔT " + ComparisonModel.formatDelta(m.deltaTemperatureMax(), "°C"));
        setText(deltaHumidity,  "ΔH " + ComparisonModel.formatDelta(m.deltaHumidity(),       "%"));
        setText(deltaPressure,  "ΔP " + ComparisonModel.formatDelta(m.deltaPressure(),        "hPa"));
        setText(deltaWind,      "ΔW " + ComparisonModel.formatDelta(m.deltaWindSpeed(),       "m/s"));
        setText(deltaPrecip,    "ΔR " + ComparisonModel.formatDelta(m.deltaPrecipitationSum(),"mm"));
    }

    private static void setText(TextView tv, String text) {
        if (tv != null) tv.setText(text);
    }
}
