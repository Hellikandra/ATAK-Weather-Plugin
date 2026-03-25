package com.atakmap.android.weather.presentation.view;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.atakmap.android.weather.data.cache.ForecastRecorder.WeatherSnapshot;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.util.WeatherUnitConverter;
import com.atakmap.coremap.log.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * View helper for displaying historical weather comparison data.
 *
 * <p>Sprint 12 (S12.2): Shows comparison between recorded snapshots
 * and current conditions, including trends (rising/falling), min/max/avg
 * statistics, and a simple text-based sparkline of temperature over time.</p>
 */
public class HistoricalComparisonView {

    private static final String TAG = "HistoricalCompView";

    private final LinearLayout container;
    private final Context context;

    /**
     * Construct from a parent LinearLayout that will contain the comparison.
     *
     * @param container the LinearLayout to populate
     * @param context   plugin context
     */
    public HistoricalComparisonView(LinearLayout container, Context context) {
        this.container = container;
        this.context = context;
    }

    /**
     * Show comparison between recorded snapshots and current conditions.
     *
     * @param history list of historical snapshots, ordered by timestamp
     * @param current the current weather model
     */
    public void bindComparison(List<WeatherSnapshot> history,
                                WeatherModel current) {
        if (container == null) return;
        container.removeAllViews();

        if (history == null || history.isEmpty()) {
            addText("No historical data available for this location.", 0xFFAAAAAA);
            return;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("MM/dd HH:mm", Locale.US);

        // ── Header ───────────────────────────────────────────────────────────
        addText("Historical Comparison (" + history.size() + " records)", 0xFFDDDDDD, true);

        String oldest = sdf.format(new Date(history.get(0).timestamp));
        String newest = sdf.format(new Date(history.get(history.size() - 1).timestamp));
        addText("Period: " + oldest + " to " + newest, 0xFFAAAAAA);

        // ── Temperature statistics ───────────────────────────────────────────
        double tempMin = Double.MAX_VALUE;
        double tempMax = -Double.MAX_VALUE;
        double tempSum = 0;

        double windMin = Double.MAX_VALUE;
        double windMax = -Double.MAX_VALUE;
        double windSum = 0;

        double pressMin = Double.MAX_VALUE;
        double pressMax = -Double.MAX_VALUE;

        for (WeatherSnapshot s : history) {
            if (s.temp < tempMin) tempMin = s.temp;
            if (s.temp > tempMax) tempMax = s.temp;
            tempSum += s.temp;

            if (s.windSpeed < windMin) windMin = s.windSpeed;
            if (s.windSpeed > windMax) windMax = s.windSpeed;
            windSum += s.windSpeed;

            if (s.pressure < pressMin) pressMin = s.pressure;
            if (s.pressure > pressMax) pressMax = s.pressure;
        }

        double tempAvg = tempSum / history.size();
        double windAvg = windSum / history.size();

        addDivider();
        addText("Temperature", 0xFFDDDDDD, true);
        addText(String.format(Locale.US, "  Min: %s  Max: %s  Avg: %s",
                WeatherUnitConverter.fmtTemp(tempMin),
                WeatherUnitConverter.fmtTemp(tempMax),
                WeatherUnitConverter.fmtTemp(tempAvg)), 0xFFCCCCCC);

        // Temperature trend (last few records)
        if (history.size() >= 2) {
            WeatherSnapshot recent = history.get(history.size() - 1);
            WeatherSnapshot prior = history.get(history.size() - 2);
            double diff = recent.temp - prior.temp;
            String trend;
            if (diff > 0.5) trend = "RISING";
            else if (diff < -0.5) trend = "FALLING";
            else trend = "STABLE";
            addText("  Trend: " + trend + " (" + String.format(Locale.US, "%+.1f", diff) + ")", 0xFFCCCCCC);
        }

        // Current vs historical
        if (current != null) {
            double curTemp = current.getTemperatureMax();
            double deltaFromAvg = curTemp - tempAvg;
            String comparison = String.format(Locale.US, "  Current: %s (%s vs avg)",
                    WeatherUnitConverter.fmtTemp(curTemp),
                    String.format(Locale.US, "%+.1f", deltaFromAvg));
            addText(comparison, deltaFromAvg > 2 ? 0xFFCC6644 :
                    (deltaFromAvg < -2 ? 0xFF4488CC : 0xFFCCCCCC));
        }

        // ── Wind statistics ──────────────────────────────────────────────────
        addDivider();
        addText("Wind", 0xFFDDDDDD, true);
        addText(String.format(Locale.US, "  Min: %s  Max: %s  Avg: %s",
                WeatherUnitConverter.fmtWind(windMin),
                WeatherUnitConverter.fmtWind(windMax),
                WeatherUnitConverter.fmtWind(windAvg)), 0xFFCCCCCC);

        if (current != null) {
            double curWind = current.getWindSpeed();
            String windComparison = String.format(Locale.US, "  Current: %s",
                    WeatherUnitConverter.fmtWind(curWind));
            addText(windComparison, curWind > windMax ? 0xFFCC4444 : 0xFFCCCCCC);
        }

        // ── Pressure statistics ──────────────────────────────────────────────
        addDivider();
        addText("Pressure", 0xFFDDDDDD, true);
        addText(String.format(Locale.US, "  Range: %s - %s",
                WeatherUnitConverter.fmtPressure(pressMin),
                WeatherUnitConverter.fmtPressure(pressMax)), 0xFFCCCCCC);

        if (history.size() >= 2) {
            WeatherSnapshot recent = history.get(history.size() - 1);
            WeatherSnapshot prior = history.get(history.size() - 2);
            double pressDiff = recent.pressure - prior.pressure;
            String pressTrend;
            if (pressDiff > 1.0) pressTrend = "RISING";
            else if (pressDiff < -1.0) pressTrend = "FALLING";
            else pressTrend = "STEADY";
            addText("  Trend: " + pressTrend, 0xFFCCCCCC);
        }

        // ── Text sparkline ───────────────────────────────────────────────────
        addDivider();
        addText("Temperature Sparkline", 0xFFDDDDDD, true);
        String sparkline = buildSparkline(history, tempMin, tempMax);
        addMonoText(sparkline, 0xFF88AACC);
    }

    // ── Sparkline builder ────────────────────────────────────────────────────

    /**
     * Build a simple text-based sparkline using block characters.
     */
    private String buildSparkline(List<WeatherSnapshot> history,
                                    double min, double max) {
        if (history.isEmpty()) return "";

        // Use up to 40 data points
        int step = Math.max(1, history.size() / 40);
        StringBuilder sb = new StringBuilder();
        char[] blocks = {'\u2581', '\u2582', '\u2583', '\u2584',
                         '\u2585', '\u2586', '\u2587', '\u2588'};

        double range = max - min;
        if (range < 0.1) range = 1.0; // avoid division by zero

        for (int i = 0; i < history.size(); i += step) {
            double normalized = (history.get(i).temp - min) / range;
            int idx = (int) (normalized * (blocks.length - 1));
            idx = Math.max(0, Math.min(blocks.length - 1, idx));
            sb.append(blocks[idx]);
        }

        return sb.toString();
    }

    // ── View helpers ─────────────────────────────────────────────────────────

    private void addText(String text, int color) {
        addText(text, color, false);
    }

    private void addText(String text, int color, boolean bold) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(10);
        tv.setTextColor(color);
        tv.setPadding(4, 2, 4, 2);
        if (bold) tv.setTypeface(null, android.graphics.Typeface.BOLD);
        container.addView(tv);
    }

    private void addMonoText(String text, int color) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(color);
        tv.setPadding(4, 2, 4, 4);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        container.addView(tv);
    }

    private void addDivider() {
        android.view.View divider = new android.view.View(context);
        divider.setBackgroundColor(0xFF444444);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.setMargins(0, 4, 0, 4);
        container.addView(divider, params);
    }
}
