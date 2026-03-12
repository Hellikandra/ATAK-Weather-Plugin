package com.atakmap.android.weather.presentation.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.LocationSnapshot;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.util.WmoCodeMapper;
import com.atakmap.android.weather.plugin.R;

/**
 * View helper for Tab 1 — current conditions panel.
 *
 * Sprint 1 changes:
 *  - bindLocation(LocationSnapshot) replaces bindLocationName(String)
 *    Shows name on textView_town and coordinates on textview_coords
 *  - showSourceLabel() shows "Self" or "Map centre" as a small tag
 *    so the user always knows which position the data describes
 */
public class CurrentWeatherView {

    // ── Bound views ───────────────────────────────────────────────────────────
    private final TextView  textDate;
    private final TextView  textTown;
    private final TextView  textCoords;      // NEW: "50.6971° N,  5.2583° E"
    private final TextView  textSourceLabel; // NEW: "Self" / "Map centre"
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
    private final TextView  textSeekBarLabel;

    // ── METAR card (visible only when source is AWC) ───────────────────────
    private final LinearLayout metarCard;
    private final TextView     textMetarStation;
    private final TextView     textMetarFltCat;
    private final TextView     textMetarRaw;

    private final Context context;

    // ─────────────────────────────────────────────────────────────────────────

    public CurrentWeatherView(View root, Context context) {
        this.context         = context;
        textDate             = root.findViewById(R.id.textView_date);
        textTown             = root.findViewById(R.id.textView_town);
        textCoords           = root.findViewById(R.id.textview_coords);
        textSourceLabel      = root.findViewById(R.id.textview_source_label);
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
        // METAR card
        metarCard            = root.findViewById(R.id.metar_card);
        textMetarStation     = root.findViewById(R.id.textview_metar_station);
        textMetarFltCat      = root.findViewById(R.id.textview_metar_fltcat);
        textMetarRaw         = root.findViewById(R.id.textview_metar_raw);
    }

    // ── Bind methods ──────────────────────────────────────────────────────────

    public void showLoading() {
        if (textDate != null) textDate.setText(R.string.wait);
    }

    public void showError(String message) {
        if (textDate != null)
            textDate.setText(context.getString(R.string.error_prefix) + message);
    }

    /**
     * Bind the current weather card.
     */
    @SuppressLint("SetTextI18n")
    public void bindCurrentWeather(WeatherModel weather, String requestTime) {
        if (textDate != null)
            textDate.setText(context.getString(R.string.now) + requestTime);

        if (textAirTemp != null)
            textAirTemp.setText(String.format("%.1f°C / %.1f°C",
                    weather.getTemperatureMin(), weather.getTemperatureMax()));
        if (textRealFeel != null)
            textRealFeel.setText(String.format("%.1f°C", weather.getApparentTemperature()));
        if (textVisibility != null)
            textVisibility.setText(String.format("%.0f m", weather.getVisibility()));
        if (textHumidity != null)
            textHumidity.setText(String.format("%.0f %%", weather.getHumidity()));
        if (textPressure != null)
            textPressure.setText(String.format("%.1f hPa", weather.getPressure()));
        if (textWind != null)
            textWind.setText(String.format("%.1f m/s / %.0f°",
                    weather.getWindSpeed(), weather.getWindDirection()));

        bindPrecipitation(weather.getPrecipitationSum(), weather.getPrecipitationHours());
        applyWmoCode(weather.getWeatherCode());

        // Show METAR card only when data comes from AWC
        if (weather.isMetarSource()) {
            bindMetar(weather);
        } else {
            hideMetarCard();
        }
    }

    /**
     * Populate and reveal the AWC METAR detail card.
     *
     * Flight category colours (FAA standard):
     *   VFR  → Green  #00AA00
     *   MVFR → Blue   #0066FF
     *   IFR  → Red    #CC0000
     *   LIFR → Magenta #AA00AA
     */
    public void bindMetar(WeatherModel weather) {
        if (metarCard == null) return;
        metarCard.setVisibility(View.VISIBLE);

        if (textMetarStation != null)
            textMetarStation.setText(weather.getIcaoId().isEmpty() ? "—" : weather.getIcaoId());

        if (textMetarFltCat != null) {
            String cat = weather.getFlightCategory();
            textMetarFltCat.setText(cat.isEmpty() ? "—" : cat);
            int bg;
            switch (cat) {
                case "VFR":  bg = Color.parseColor("#00AA00"); break;
                case "MVFR": bg = Color.parseColor("#0066FF"); break;
                case "IFR":  bg = Color.parseColor("#CC0000"); break;
                case "LIFR": bg = Color.parseColor("#AA00AA"); break;
                default:     bg = Color.parseColor("#555555"); break;
            }
            textMetarFltCat.setBackgroundColor(bg);
        }

        if (textMetarRaw != null) {
            String raw = weather.getRawMetar();
            textMetarRaw.setText(raw.isEmpty() ? "Raw METAR not available" : raw);
        }
    }

    /** Hide the METAR card (used when source is Open-Meteo). */
    public void hideMetarCard() {
        if (metarCard != null) metarCard.setVisibility(View.GONE);
    }

    /**
     * Bind a resolved LocationSnapshot to the header.
     * Shows: name on textView_town, coords on textview_coords,
     * source label ("Self" / "Map centre") on textview_source_label.
     */
    public void bindLocation(LocationSnapshot snapshot) {
        if (textTown != null)
            textTown.setText(snapshot.getDisplayName());
        if (textCoords != null)
            textCoords.setText(snapshot.getCoordsLabel());
        if (textSourceLabel != null) {
            textSourceLabel.setText(snapshot.getSource().label);
            textSourceLabel.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Bind the hourly detail panel when the SeekBar position changes.
     */
    @SuppressLint("SetTextI18n")
    public void bindHourlyEntry(HourlyEntryModel entry, String hourLabel) {
        if (textSeekBarLabel != null) textSeekBarLabel.setText(hourLabel);
        if (textRealFeel   != null) textRealFeel.setText(String.format("%.1f°C",  entry.getApparentTemperature()));
        if (textVisibility != null) textVisibility.setText(String.format("%.0f m", entry.getVisibility()));
        if (textHumidity   != null) textHumidity.setText(String.format("%.0f %%",  entry.getHumidity()));
        if (textPressure   != null) textPressure.setText(String.format("%.1f hPa", entry.getPressure()));
        if (textWind       != null) textWind.setText(String.format("%.1f m/s / %.0f°",
                entry.getWindSpeed(), entry.getWindDirection()));
        applyWmoCode(entry.getWeatherCode());
    }

    /**
     * Configure SeekBar max and change listener.
     */
    public void configureSeekBar(int maxIndex, SeekBar.OnSeekBarChangeListener listener) {
        if (seekBar != null) {
            seekBar.setMax(maxIndex);
            seekBar.setProgress(0);
            seekBar.setOnSeekBarChangeListener(listener);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void applyWmoCode(int code) {
        WmoCodeMapper.WmoInfo info = WmoCodeMapper.resolve(code);
        if (textWeatherDesc  != null) textWeatherDesc.setText(info.labelResId);
        if (imageWeatherIcon != null) imageWeatherIcon.setImageResource(info.drawableResId);
    }

    private void bindPrecipitation(double sum, double hours) {
        if (textPrecipitation == null) return;
        if (sum == 0.0) {
            textPrecipitation.setText(R.string.nopre);
        } else {
            textPrecipitation.setText(String.format("%.1f %s  %.0f %s",
                    sum,   context.getString(R.string.mm),
                    hours, context.getString(R.string.hour)));
        }
    }
}
