package com.atakmap.android.weather.presentation.view;

import android.annotation.SuppressLint;
import android.view.View;
import android.widget.ImageView;
import android.widget.TableLayout;
import android.widget.TextView;

import com.atakmap.android.weather.domain.model.DailyForecastModel;
import com.atakmap.android.weather.util.WmoCodeMapper;
import com.atakmap.android.weather.plugin.R;

import java.util.List;

/**
 * View helper for the 7-day daily forecast strip in Tab 1.
 *
 * Binds the 7 TableLayout columns in the GridLayout.
 * All column indices are driven by the data list, not hardcoded.
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

        for (int i = 0; i < count; i++) {
            DailyForecastModel day = forecast.get(i);
            WmoCodeMapper.WmoInfo info = WmoCodeMapper.resolve(day.getWeatherCode());

            dayLabels[i].setText(day.getDayLabel());
            dateLabels[i].setText(day.getDate());
            weatherIcons[i].setImageResource(info.drawableResId);
            maxTempLabels[i].setText(
                    String.format("High: %.1f°C", day.getTemperatureMax()));
            minTempLabels[i].setText(
                    String.format("Low: %.1f°C", day.getTemperatureMin()));
            weatherDescs[i].setText(info.labelResId);
            columns[i].setVisibility(View.VISIBLE);
        }
    }
}
