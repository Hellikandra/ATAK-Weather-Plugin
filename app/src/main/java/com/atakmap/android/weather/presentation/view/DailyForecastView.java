package com.atakmap.android.weather.presentation.view;

import android.annotation.SuppressLint;
import android.view.View;
import android.widget.ImageView;
import android.widget.TableLayout;
import android.widget.TextView;

import com.atakmap.android.weather.domain.model.DailyForecastModel;
import com.atakmap.android.weather.domain.service.AstronomicalService;
import com.atakmap.android.weather.util.UnitSystem;
import com.atakmap.android.weather.util.WeatherUnitConverter;
import com.atakmap.android.weather.util.WmoCodeMapper;
import com.atakmap.android.weather.plugin.R;

import java.util.List;
import java.util.Locale;

/**
 * View helper for the 7-day daily forecast strip in Tab 1.
 *
 * <p>Binds the 7 TableLayout columns in the GridLayout.
 * All column indices are driven by the data list, not hardcoded.</p>
 *
 * <p>Sprint 9 (S9.2): Added sunrise/sunset row with BMNT/EENT in aviation mode.</p>
 */
public class DailyForecastView {

    private static final int MAX_DAYS = 7;

    // ── Per-column view refs ─────────────────────────────────────────────────
    private final TableLayout[] columns         = new TableLayout[MAX_DAYS];
    private final TextView[]    dayLabels        = new TextView[MAX_DAYS];
    private final TextView[]    dateLabels       = new TextView[MAX_DAYS];
    private final ImageView[]   weatherIcons     = new ImageView[MAX_DAYS];
    private final TextView[]    maxTempLabels    = new TextView[MAX_DAYS];
    private final TextView[]    minTempLabels    = new TextView[MAX_DAYS];
    private final TextView[]    weatherDescs     = new TextView[MAX_DAYS];
    private final TextView[]    sunLabels        = new TextView[MAX_DAYS];

    // ── Resource ID arrays — parallel to the XML layout ──────────────────────
    private static final int[] COL_IDS = {
            R.id.daily_forecast_tablelayout_1,
            R.id.daily_forecast_tablelayout_2,
            R.id.daily_forecast_tablelayout_3,
            R.id.daily_forecast_tablelayout_4,
            R.id.daily_forecast_tablelayout_5,
            R.id.daily_forecast_tablelayout_6,
            R.id.daily_forecast_tablelayout_7,
    };
    private static final int[] DAY_IDS = {
            R.id.daily_forecast_day_textview_1,  R.id.daily_forecast_day_textview_2,
            R.id.daily_forecast_day_textview_3,  R.id.daily_forecast_day_textview_4,
            R.id.daily_forecast_day_textview_5,  R.id.daily_forecast_day_textview_6,
            R.id.daily_forecast_day_textview_7,
    };
    private static final int[] DATE_IDS = {
            R.id.daily_forecast_date_textview_1, R.id.daily_forecast_date_textview_2,
            R.id.daily_forecast_date_textview_3, R.id.daily_forecast_date_textview_4,
            R.id.daily_forecast_date_textview_5, R.id.daily_forecast_date_textview_6,
            R.id.daily_forecast_date_textview_7,
    };
    private static final int[] ICON_IDS = {
            R.id.daily_forecast_weathercode_imageview_1,
            R.id.daily_forecast_weathercode_imageview_2,
            R.id.daily_forecast_weathercode_imageview_3,
            R.id.daily_forecast_weathercode_imageview_4,
            R.id.daily_forecast_weathercode_imageview_5,
            R.id.daily_forecast_weathercode_imageview_6,
            R.id.daily_forecast_weathercode_imageview_7,
    };
    private static final int[] MAX_IDS = {
            R.id.daily_forecast_temp_max_textview_1,
            R.id.daily_forecast_temp_max_textview_2,
            R.id.daily_forecast_temp_max_textview_3,
            R.id.daily_forecast_temp_max_textview_4,
            R.id.daily_forecast_temp_max_textview_5,
            R.id.daily_forecast_temp_max_textview_6,
            R.id.daily_forecast_temp_max_textview_7,
    };
    private static final int[] MIN_IDS = {
            R.id.daily_forecast_temp_min_textview_1,
            R.id.daily_forecast_temp_min_textview_2,
            R.id.daily_forecast_temp_min_textview_3,
            R.id.daily_forecast_temp_min_textview_4,
            R.id.daily_forecast_temp_min_textview_5,
            R.id.daily_forecast_temp_min_textview_6,
            R.id.daily_forecast_temp_min_textview_7,
    };
    private static final int[] DESC_IDS = {
            R.id.daily_forecast_weatherinfo_textview_1,
            R.id.daily_forecast_weatherinfo_textview_2,
            R.id.daily_forecast_weatherinfo_textview_3,
            R.id.daily_forecast_weatherinfo_textview_4,
            R.id.daily_forecast_weatherinfo_textview_5,
            R.id.daily_forecast_weatherinfo_textview_6,
            R.id.daily_forecast_weatherinfo_textview_7,
    };
    private static final int[] SUN_IDS = {
            R.id.daily_forecast_sun_textview_1,
            R.id.daily_forecast_sun_textview_2,
            R.id.daily_forecast_sun_textview_3,
            R.id.daily_forecast_sun_textview_4,
            R.id.daily_forecast_sun_textview_5,
            R.id.daily_forecast_sun_textview_6,
            R.id.daily_forecast_sun_textview_7,
    };

    // ────────────────────────────────────────────────────────────────────────

    public DailyForecastView(View root) {
        for (int i = 0; i < MAX_DAYS; i++) {
            columns[i]      = root.findViewById(COL_IDS[i]);
            dayLabels[i]    = root.findViewById(DAY_IDS[i]);
            dateLabels[i]   = root.findViewById(DATE_IDS[i]);
            weatherIcons[i] = root.findViewById(ICON_IDS[i]);
            maxTempLabels[i]= root.findViewById(MAX_IDS[i]);
            minTempLabels[i]= root.findViewById(MIN_IDS[i]);
            weatherDescs[i] = root.findViewById(DESC_IDS[i]);
            sunLabels[i]    = root.findViewById(SUN_IDS[i]);
        }
    }

    // ── Bind ─────────────────────────────────────────────────────────────────

    @SuppressLint("SetTextI18n")
    public void bind(List<DailyForecastModel> forecast) {
        int count = Math.min(forecast.size(), MAX_DAYS);

        // Hide columns that have no data
        for (int i = count; i < MAX_DAYS; i++) {
            columns[i].setVisibility(View.GONE);
        }

        boolean isAviation = WeatherUnitConverter.getUnitSystem() == UnitSystem.AVIATION;

        for (int i = 0; i < count; i++) {
            DailyForecastModel day = forecast.get(i);
            WmoCodeMapper.WmoInfo info = WmoCodeMapper.resolve(day.getWeatherCode());

            dayLabels[i].setText(day.getDayLabel());
            dateLabels[i].setText(day.getDate());
            weatherIcons[i].setImageResource(info.drawableResId);
            maxTempLabels[i].setText("High: " + WeatherUnitConverter.fmtTemp(day.getTemperatureMax()));
            minTempLabels[i].setText("Low: " + WeatherUnitConverter.fmtTemp(day.getTemperatureMin()));
            weatherDescs[i].setText(info.labelResId);
            columns[i].setVisibility(View.VISIBLE);

            // Sprint 9 (S9.2): Sunrise/sunset row
            if (sunLabels[i] != null) {
                String sunText = buildSunText(day, isAviation);
                if (sunText != null) {
                    sunLabels[i].setText(sunText);
                    sunLabels[i].setVisibility(View.VISIBLE);
                } else {
                    sunLabels[i].setVisibility(View.GONE);
                }
            }
        }
    }

    /**
     * Build sunrise/sunset display text for a day.
     * Normal mode:  "\u2600\u2191 06:42  \u2600\u2193 20:15  (13h 33m)"
     * Aviation mode: "BMNT 05:42 / EENT 21:15"
     */
    private String buildSunText(DailyForecastModel day, boolean isAviation) {
        if (day.getSunrise() == null || day.getSunset() == null) return null;

        AstronomicalService.SunTimes times =
                AstronomicalService.parseSunTimes(day.getSunrise(), day.getSunset());
        if (times == null) return null;

        if (isAviation) {
            return String.format(Locale.US, "BMNT %s / EENT %s",
                    AstronomicalService.formatTime(times.bmnt),
                    AstronomicalService.formatTime(times.eent));
        } else {
            String duration = AstronomicalService.formatDuration(times.daylightDurationSec);
            return String.format(Locale.US, "\u2600\u2191%s \u2600\u2193%s (%s)",
                    AstronomicalService.formatTime(times.sunrise),
                    AstronomicalService.formatTime(times.sunset),
                    duration);
        }
    }
}
