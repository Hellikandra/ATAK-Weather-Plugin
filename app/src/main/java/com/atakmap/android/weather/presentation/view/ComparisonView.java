package com.atakmap.android.weather.presentation.view;

import android.annotation.SuppressLint;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.atakmap.android.weather.domain.model.ComparisonModel;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.util.WmoCodeMapper;
import com.atakmap.android.weather.plugin.R;

/**
 * View helper for Tab 6 — Self Marker vs Map Center (stacked card layout).
 *
 * Card 1 (top)    : Self Marker
 * Delta row       : signed Δ values (Map Center − Self)
 * Card 2 (bottom) : Map Center
 *
 * All IDs match tab_comparison.xml exactly.
 */
public class ComparisonView {

    // ── Self card ──────────────────────────────────────────────────────────
    private final TextView  selfLocation;
    private final ImageView selfIcon;
    private final TextView  selfWeatherDesc;
    private final TextView  selfTemp;
    private final TextView  selfFeels;
    private final TextView  selfHumidity;
    private final TextView  selfPressure;
    private final TextView  selfWind;
    private final TextView  selfPrecip;

    // ── Map center card ────────────────────────────────────────────────────
    private final TextView  centerLocation;
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
        selfIcon          = root.findViewById(R.id.comp_self_icon);
        selfWeatherDesc   = root.findViewById(R.id.comp_self_weather_desc);
        selfTemp          = root.findViewById(R.id.comp_self_temp);
        selfFeels         = root.findViewById(R.id.comp_self_feels);
        selfHumidity      = root.findViewById(R.id.comp_self_humidity);
        selfPressure      = root.findViewById(R.id.comp_self_pressure);
        selfWind          = root.findViewById(R.id.comp_self_wind);
        selfPrecip        = root.findViewById(R.id.comp_self_precip);

        centerLocation    = root.findViewById(R.id.comp_center_location);
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
        statusText.setVisibility(View.VISIBLE);
        statusText.setText(R.string.wait);
    }

    public void showError(String msg) {
        statusText.setVisibility(View.VISIBLE);
        statusText.setText("Error: " + msg);
    }

    @SuppressLint("SetTextI18n")
    public void bind(ComparisonModel model) {
        statusText.setVisibility(View.GONE);
        bindCard(model.selfMarker,
                selfLocation, selfIcon, selfWeatherDesc,
                selfTemp, selfFeels, selfHumidity,
                selfPressure, selfWind, selfPrecip);
        bindCard(model.mapCenter,
                centerLocation, centerIcon, centerWeatherDesc,
                centerTemp, centerFeels, centerHumidity,
                centerPressure, centerWind, centerPrecip);
        bindDeltas(model);
    }

    /** Update only the location label on the self card (from reverse geocode). */
    public void bindSelfLocation(String name)   { if (selfLocation   != null) selfLocation.setText(name); }
    public void bindCenterLocation(String name) { if (centerLocation != null) centerLocation.setText(name); }

    // ── Private ────────────────────────────────────────────────────────────

    @SuppressLint("SetTextI18n")
    private void bindCard(WeatherModel w,
                          TextView location, ImageView icon, TextView desc,
                          TextView temp, TextView feels, TextView humidity,
                          TextView pressure, TextView wind, TextView precip) {
        WmoCodeMapper.WmoInfo info = WmoCodeMapper.resolve(w.getWeatherCode());
        icon.setImageResource(info.drawableResId);
        desc.setText(info.labelResId);

        temp.setText(String.format("%.1f / %.1f°C",
                w.getTemperatureMin(), w.getTemperatureMax()));
        feels.setText(String.format("%.1f°C", w.getApparentTemperature()));
        humidity.setText(String.format("%.0f %%", w.getHumidity()));
        pressure.setText(String.format("%.1f hPa", w.getPressure()));
        wind.setText(String.format("%.1f m/s  %.0f°",
                w.getWindSpeed(), w.getWindDirection()));

        if (w.getPrecipitationSum() == 0) {
            precip.setText(R.string.nopre);
        } else {
            precip.setText(String.format("%.1f mm", w.getPrecipitationSum()));
        }
    }

    @SuppressLint("SetTextI18n")
    private void bindDeltas(ComparisonModel m) {
        deltaTemp.setText("ΔT "     + ComparisonModel.formatDelta(m.deltaTemperatureMax(), "°C"));
        deltaHumidity.setText("ΔH " + ComparisonModel.formatDelta(m.deltaHumidity(),       "%"));
        deltaPressure.setText("ΔP " + ComparisonModel.formatDelta(m.deltaPressure(),        "hPa"));
        deltaWind.setText("ΔW "     + ComparisonModel.formatDelta(m.deltaWindSpeed(),       "m/s"));
        deltaPrecip.setText("ΔR "   + ComparisonModel.formatDelta(m.deltaPrecipitationSum(),"mm"));
    }
}
