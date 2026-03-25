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
import com.atakmap.android.weather.domain.model.WeatherAlert;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.service.WeatherAlertService;
import com.atakmap.android.weather.domain.service.WeatherAnalyticsService;
import com.atakmap.android.weather.util.WeatherUnitConverter;
import com.atakmap.android.weather.util.WmoCodeMapper;
import com.atakmap.android.weather.plugin.R;

import java.util.List;

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

    // ── Derived conditions card (Sprint 4 — S4.3) ───────────────────────
    private final LinearLayout derivedCard;
    private final TextView     textFeelsLike;
    private final TextView     textDewPoint;
    private final TextView     textBeaufort;
    private final TextView     textDerivedFltCat;
    private final TextView     textDerivedCondition;

    // ── Alert banner (Sprint 5 — S5.4) ───────────────────────────────────
    private final LinearLayout alertBanner;
    private final TextView     textAlertTitle;
    private final TextView     textAlertDetail;

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
        // Derived conditions card (Sprint 4)
        derivedCard          = root.findViewById(R.id.derived_card);
        textFeelsLike        = root.findViewById(R.id.textview_derived_feels_like);
        textDewPoint         = root.findViewById(R.id.textview_derived_dew_point);
        textBeaufort         = root.findViewById(R.id.textview_derived_beaufort);
        textDerivedFltCat    = root.findViewById(R.id.textview_derived_flt_cat);
        textDerivedCondition = root.findViewById(R.id.textview_derived_condition);
        // Alert banner (Sprint 5)
        alertBanner          = root.findViewById(R.id.alert_banner);
        textAlertTitle       = root.findViewById(R.id.textview_alert_title);
        textAlertDetail      = root.findViewById(R.id.textview_alert_detail);
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
            textAirTemp.setText(WeatherUnitConverter.fmtTempRange(
                    weather.getTemperatureMin(), weather.getTemperatureMax()));
        if (textRealFeel != null)
            textRealFeel.setText(WeatherUnitConverter.fmtTemp(weather.getApparentTemperature()));
        if (textVisibility != null)
            textVisibility.setText(WeatherUnitConverter.fmtVisibility(weather.getVisibility()));
        if (textHumidity != null)
            textHumidity.setText(WeatherUnitConverter.fmtHumidity(weather.getHumidity()));
        if (textPressure != null)
            textPressure.setText(WeatherUnitConverter.fmtPressure(weather.getPressure()));
        if (textWind != null)
            textWind.setText(WeatherUnitConverter.fmtWindDir(
                    weather.getWindSpeed(), weather.getWindDirection()));

        bindPrecipitation(weather.getPrecipitationSum(), weather.getPrecipitationHours());
        applyWmoCode(weather.getWeatherCode());

        // Show METAR card only when data comes from AWC
        if (weather.isMetarSource()) {
            bindMetar(weather);
        } else {
            hideMetarCard();
        }

        // Show derived conditions (Sprint 4 — S4.3)
        bindDerivedConditions(weather);

        // Check weather alerts (Sprint 5 — S5.4)
        bindAlerts(weather);
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
        if (textRealFeel   != null) textRealFeel.setText(WeatherUnitConverter.fmtTemp(entry.getApparentTemperature()));
        if (textVisibility != null) textVisibility.setText(WeatherUnitConverter.fmtVisibility(entry.getVisibility()));
        if (textHumidity   != null) textHumidity.setText(WeatherUnitConverter.fmtHumidity(entry.getHumidity()));
        if (textPressure   != null) textPressure.setText(WeatherUnitConverter.fmtPressure(entry.getPressure()));
        if (textWind       != null) textWind.setText(WeatherUnitConverter.fmtWindDir(
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

    // ── Derived conditions (Sprint 4 — S4.3) ──────────────────────────────────

    /**
     * Populate the "Derived Conditions" card using {@link WeatherAnalyticsService}.
     * Uses the average of min/max as the representative temperature for calculations.
     */
    @SuppressLint("SetTextI18n")
    public void bindDerivedConditions(WeatherModel weather) {
        if (derivedCard == null) return;
        derivedCard.setVisibility(View.VISIBLE);

        double avgTemp = (weather.getTemperatureMin() + weather.getTemperatureMax()) / 2.0;
        double humidity = weather.getHumidity();
        double wind = weather.getWindSpeed();

        // Feels like
        if (textFeelsLike != null) {
            double fl = WeatherAnalyticsService.feelsLike(avgTemp, humidity, wind);
            String src;
            if (avgTemp >= 27.0 && humidity >= 40.0) src = "Heat Index";
            else if (avgTemp <= 10.0 && wind > 1.34) src = "Wind Chill";
            else src = "Ambient";
            textFeelsLike.setText(WeatherUnitConverter.fmtTemp(fl) + " (" + src + ")");
        }

        // Dew point
        if (textDewPoint != null) {
            double dp = WeatherAnalyticsService.dewPoint(avgTemp, humidity);
            textDewPoint.setText(WeatherUnitConverter.fmtTemp(dp));
        }

        // Beaufort
        if (textBeaufort != null) {
            WeatherAnalyticsService.BeaufortResult bft = WeatherAnalyticsService.beaufort(wind);
            textBeaufort.setText("Bft " + bft.force + " — " + bft.description);
        }

        // Flight category
        if (textDerivedFltCat != null) {
            String fltCat = weather.computeFlightCategory();
            textDerivedFltCat.setText(fltCat);
            int color;
            switch (fltCat) {
                case "VFR":  color = Color.parseColor("#00AA00"); break;
                case "MVFR": color = Color.parseColor("#0066FF"); break;
                case "IFR":  color = Color.parseColor("#CC0000"); break;
                case "LIFR": color = Color.parseColor("#AA00AA"); break;
                default:     color = Color.parseColor("#999999"); break;
            }
            textDerivedFltCat.setTextColor(color);
        }

        // Tactical condition
        if (textDerivedCondition != null) {
            String cond = weather.tacticalCondition();
            textDerivedCondition.setText(cond);
            int condColor;
            switch (cond) {
                case "GREEN": condColor = Color.parseColor("#00CC00"); break;
                case "AMBER": condColor = Color.parseColor("#FFAA00"); break;
                case "RED":   condColor = Color.parseColor("#FF3333"); break;
                default:      condColor = Color.parseColor("#999999"); break;
            }
            textDerivedCondition.setTextColor(condColor);
        }
    }

    /** Hide the derived conditions card. */
    public void hideDerivedCard() {
        if (derivedCard != null) derivedCard.setVisibility(View.GONE);
    }

    // ── Weather Alerts (Sprint 5 — S5.4) ────────────────────────────────────

    /**
     * Evaluate weather against default thresholds and show alert banner if any.
     */
    @SuppressLint("SetTextI18n")
    public void bindAlerts(WeatherModel weather) {
        if (alertBanner == null) return;

        List<WeatherAlert> alerts = WeatherAlertService.evaluate(
                weather, WeatherAlertService.AlertThresholds.defaults());

        if (alerts.isEmpty()) {
            alertBanner.setVisibility(View.GONE);
            return;
        }

        alertBanner.setVisibility(View.VISIBLE);

        // Show the most severe alert as title
        WeatherAlert top = alerts.get(0);
        if (textAlertTitle != null) {
            String prefix;
            int bgColor;
            switch (top.getSeverity()) {
                case CRITICAL:
                    prefix = "\u26A0 CRITICAL: ";
                    bgColor = 0x66FF0000;
                    break;
                case WARNING:
                    prefix = "\u26A0 WARNING: ";
                    bgColor = 0x44FF3333;
                    break;
                default:
                    prefix = "\u2139 ADVISORY: ";
                    bgColor = 0x33FFAA00;
                    break;
            }
            textAlertTitle.setText(prefix + top.getTitle());
            alertBanner.setBackgroundColor(bgColor);
        }

        // Show details of all alerts
        if (textAlertDetail != null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(alerts.size(), 3); i++) {
                if (i > 0) sb.append("\n");
                sb.append(alerts.get(i).getDetail());
            }
            if (alerts.size() > 3) {
                sb.append("\n+ ").append(alerts.size() - 3).append(" more");
            }
            textAlertDetail.setText(sb.toString());
        }
    }

    /** Hide the alert banner. */
    public void hideAlertBanner() {
        if (alertBanner != null) alertBanner.setVisibility(View.GONE);
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
