package com.atakmap.android.weather.presentation.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.util.WmoCodeMapper;
import com.atakmap.android.weather.plugin.R;

/**
 * View helper for Tab 1 — current conditions panel.
 *
 * Changes vs. previous version:
 *  1. Weather title TextView removed from the layout (improvement #1).
 *  2. textSeekBarLabel added — shows "+NNh  (HH:00)" below the SeekBar (improvement #2).
 *  3. bindHourlyEntry() now also updates the WMO icon + description when the
 *     SeekBar is dragged, using the hourly weathercode field (improvement #6).
 *     (The hourly WMO is sourced from HourlyEntryModel.getWeatherCode().)
 */
public class CurrentWeatherView {

    // ── Bound views ───────────────────────────────────────────────────────────
    private final TextView  textDate;
    private final TextView  textTown;
    private final ImageView imageWeatherIcon;
    private final TextView  textAirTemp;
    private final TextView  textRealFeel;
    private final TextView  textVisibility;
    private final TextView  textWeatherDesc;
    private final TextView  textHumidity;
    private final TextView  textPressure;
    private final TextView  textWind;
    private final TextView  textPrecipitation;
    private final SeekBar   seekBar;
    /** NEW: displays "+NNh  (HH:00)" under the SeekBar */
    private final TextView  textSeekBarLabel;

    private final Context context;

    // ─────────────────────────────────────────────────────────────────────────

    public CurrentWeatherView(View root, Context context) {
        this.context         = context;
        textDate             = root.findViewById(R.id.textView_date);
        textTown             = root.findViewById(R.id.textView_town);
        imageWeatherIcon     = root.findViewById(R.id.image);
        textAirTemp          = root.findViewById(R.id.textview_airT);
        textRealFeel         = root.findViewById(R.id.textview_airTrf);
        textVisibility       = root.findViewById(R.id.textview_visibility);
        textWeatherDesc      = root.findViewById(R.id.textview_weather);
        textHumidity         = root.findViewById(R.id.textview_humidity);
        textPressure         = root.findViewById(R.id.textview_pressure);
        textWind             = root.findViewById(R.id.textview_wind);
        textPrecipitation    = root.findViewById(R.id.textview_precipitation);
        seekBar              = root.findViewById(R.id.seekBar);
        textSeekBarLabel     = root.findViewById(R.id.textview_seekbar_label);
    }

    // ── Bind methods ──────────────────────────────────────────────────────────

    public void showLoading() {
        textDate.setText(R.string.wait);
    }

    public void showError(String message) {
        textDate.setText(context.getString(R.string.error_prefix) + message);
    }

    /** Bind the initial (current) weather card. */
    @SuppressLint("SetTextI18n")
    public void bindCurrentWeather(WeatherModel weather, String requestTime) {
        textDate.setText(context.getString(R.string.now) + requestTime);

        textAirTemp.setText(String.format("%.1f°C / %.1f°C",
                weather.getTemperatureMin(), weather.getTemperatureMax()));
        textRealFeel.setText(String.format("%.1f°C", weather.getApparentTemperature()));
        textVisibility.setText(String.format("%.0f m", weather.getVisibility()));
        textHumidity.setText(String.format("%.0f %%", weather.getHumidity()));
        textPressure.setText(String.format("%.1f hPa", weather.getPressure()));
        textWind.setText(String.format("%.1f m/s / %.0f°",
                weather.getWindSpeed(), weather.getWindDirection()));

        bindPrecipitation(weather.getPrecipitationSum(), weather.getPrecipitationHours());
        applyWmoCode(weather.getWeatherCode());
    }

    /** Bind location name text. */
    public void bindLocationName(String name) {
        textTown.setText(name);
    }

    /**
     * Bind the hourly detail panel to a specific HourlyEntryModel.
     * Called whenever the SeekBar position changes.
     *
     * Improvement #2: updates textSeekBarLabel with the hour label.
     * Improvement #6: updates WMO icon + description from hourly weathercode.
     */
    @SuppressLint("SetTextI18n")
    public void bindHourlyEntry(HourlyEntryModel entry, String hourLabel) {
        // Update time label below SeekBar
        textSeekBarLabel.setText(hourLabel);

        // Update numeric fields
        textRealFeel.setText(String.format("%.1f°C", entry.getApparentTemperature()));
        textVisibility.setText(String.format("%.0f m", entry.getVisibility()));
        textHumidity.setText(String.format("%.0f %%", entry.getHumidity()));
        textPressure.setText(String.format("%.1f hPa", entry.getPressure()));
        textWind.setText(String.format("%.1f m/s / %.0f°",
                entry.getWindSpeed(), entry.getWindDirection()));

        // Improvement #6: WMO code update from hourly data
        applyWmoCode(entry.getWeatherCode());
    }

    /**
     * Configure the SeekBar max = number of hourly entries − 1.
     * The ViewModel computes the label; the listener just calls selectHour().
     */
    public void configureSeekBar(int maxIndex, SeekBar.OnSeekBarChangeListener listener) {
        seekBar.setMax(maxIndex);
        seekBar.setProgress(0);
        seekBar.setOnSeekBarChangeListener(listener);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void applyWmoCode(int code) {
        WmoCodeMapper.WmoInfo info = WmoCodeMapper.resolve(code);
        textWeatherDesc.setText(info.labelResId);
        imageWeatherIcon.setImageResource(info.drawableResId);
    }

    private void bindPrecipitation(double sum, double hours) {
        if (sum == 0.0) {
            textPrecipitation.setText(R.string.nopre);
        } else {
            textPrecipitation.setText(String.format("%.1f %s  %.0f %s",
                    sum,   context.getString(R.string.mm),
                    hours, context.getString(R.string.hour)));
        }
    }
}
