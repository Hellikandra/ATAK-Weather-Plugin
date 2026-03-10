package com.atakmap.android.weather.presentation.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import com.atakmap.android.weather.domain.model.HourlyEntryModel;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Custom Canvas View — multi-line overlapping weather chart.
 *
 * Each series is independently normalised to [0,1] so that all lines
 * fit on the same canvas without one dominating (pressure ~1000 would
 * dwarf temperature ~20 on a shared scale).
 *
 * Series colours match the legend chips in tab_charts.xml:
 *   Temperature  — #FF5C8A  red
 *   Humidity     — #4FC3F7  cyan
 *   Wind         — #FFB74D  orange
 *   Pressure     — #81C784  green
 *
 * Toggle series visibility with setSeriesVisible().
 * Drive the cursor (SeekBar position) with setSelectedIndex().
 */
public class WeatherChartView extends View {

    // ── Series enum ────────────────────────────────────────────────────────
    public enum Series {
        TEMPERATURE ("#FF5C8A"),
        HUMIDITY    ("#4FC3F7"),
        WIND        ("#FFB74D"),
        PRESSURE    ("#81C784");

        public final int color;
        Series(String hex) { this.color = Color.parseColor(hex); }
    }

    // ── Margins ────────────────────────────────────────────────────────────
    private static final float ML = 8f;   // left
    private static final float MR = 8f;   // right
    private static final float MT = 12f;  // top
    private static final float MB = 28f;  // bottom  (x-axis labels)

    // ── State ──────────────────────────────────────────────────────────────
    private List<HourlyEntryModel>    data;
    private int                       selectedIndex = 0;
    private final Map<Series, Boolean> visible = new EnumMap<>(Series.class);

    // ── Paints (one per series + shared) ──────────────────────────────────
    private final Map<Series, Paint>  seriesPaint   = new EnumMap<>(Series.class);
    private final Map<Series, Paint>  fillPaint     = new EnumMap<>(Series.class);
    private final Paint               gridPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint               axisLabelPaint= new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint               markerPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── Constructor ────────────────────────────────────────────────────────
    public WeatherChartView(Context context) {
        super(context);
        for (Series s : Series.values()) {
            visible.put(s, true);

            Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
            lp.setColor(s.color);
            lp.setStrokeWidth(2.5f);
            lp.setStyle(Paint.Style.STROKE);
            seriesPaint.put(s, lp);

            Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG);
            fp.setColor(s.color & 0x28FFFFFF);   // ~16% alpha fill
            fp.setStyle(Paint.Style.FILL);
            fillPaint.put(s, fp);
        }
        gridPaint.setColor(Color.parseColor("#33FFFFFF"));
        gridPaint.setStrokeWidth(1f);
        gridPaint.setPathEffect(new DashPathEffect(new float[]{5, 5}, 0));

        axisLabelPaint.setColor(Color.WHITE);
        axisLabelPaint.setTextSize(20f);

        markerPaint.setColor(Color.parseColor("#CCFFFFFF"));
        markerPaint.setStrokeWidth(1.5f);
        markerPaint.setStyle(Paint.Style.STROKE);
        markerPaint.setPathEffect(new DashPathEffect(new float[]{4, 4}, 0));
    }

    // ── Public API ─────────────────────────────────────────────────────────

    public void setData(List<HourlyEntryModel> data) {
        this.data = data;
        invalidate();
    }

    public void setSelectedIndex(int index) {
        this.selectedIndex = index;
        invalidate();
    }

    /** Toggle a series line on/off; returns new state. */
    public boolean toggleSeries(Series series) {
        boolean next = !Boolean.TRUE.equals(visible.get(series));
        visible.put(series, next);
        invalidate();
        return next;
    }

    public void setSeriesVisible(Series series, boolean show) {
        visible.put(series, show);
        invalidate();
    }

    /**
     * Returns the current value of the given series at the cursor position.
     * Returns NaN if no data or series is not visible.
     */
    public double valueAt(Series series, int index) {
        if (data == null || index < 0 || index >= data.size()) return Double.NaN;
        return extract(series, data.get(index));
    }

    // ── Draw ───────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (data == null || data.isEmpty()) return;

        float w = getWidth()  - ML - MR;
        float h = getHeight() - MT - MB;

        drawGrid(canvas, w, h);
        for (Series s : Series.values()) {
            if (Boolean.TRUE.equals(visible.get(s))) {
                drawSeries(canvas, s, w, h);
            }
        }
        drawCursor(canvas, w, h);
        drawXLabels(canvas, w, h);
    }

    // ── Private ────────────────────────────────────────────────────────────

    private void drawGrid(Canvas canvas, float w, float h) {
        for (int i = 0; i <= 4; i++) {
            float y = MT + h * i / 4f;
            canvas.drawLine(ML, y, ML + w, y, gridPaint);
        }
    }

    private void drawSeries(Canvas canvas, Series series, float w, float h) {
        double[] raw = extractAll(series);
        double min = min(raw), max = max(raw);
        if (max == min) max = min + 1;

        int n = raw.length;
        Path linePath = new Path();
        Path fillPath = new Path();

        float x0 = xPos(0, w, n);
        float y0 = yPos(raw[0], h, min, max);
        linePath.moveTo(x0, y0);
        fillPath.moveTo(x0, MT + h);
        fillPath.lineTo(x0, y0);

        for (int i = 1; i < n; i++) {
            float x = xPos(i, w, n);
            float y = yPos(raw[i], h, min, max);
            linePath.lineTo(x, y);
            fillPath.lineTo(x, y);
        }
        fillPath.lineTo(xPos(n - 1, w, n), MT + h);
        fillPath.close();

        canvas.drawPath(fillPath, fillPaint.get(series));
        canvas.drawPath(linePath, seriesPaint.get(series));

        // Dot at cursor
        float cx = xPos(selectedIndex, w, n);
        float cy = yPos(raw[Math.min(selectedIndex, n - 1)], h, min, max);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(series.color);
        dot.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, 4f, dot);
    }

    private void drawCursor(Canvas canvas, float w, float h) {
        if (data == null) return;
        float x = xPos(selectedIndex, w, data.size());
        canvas.drawLine(x, MT, x, MT + h, markerPaint);
    }

    private void drawXLabels(Canvas canvas, float w, float h) {
        if (data == null) return;
        int step = data.size() > 48 ? 24 : 12;
        for (int i = 0; i < data.size(); i += step) {
            float x = xPos(i, w, data.size());
            canvas.drawText("+" + i + "h", x - 14f, MT + h + 20f, axisLabelPaint);
        }
    }

    private double[] extractAll(Series series) {
        double[] v = new double[data.size()];
        for (int i = 0; i < data.size(); i++) v[i] = extract(series, data.get(i));
        return v;
    }

    private double extract(Series series, HourlyEntryModel e) {
        switch (series) {
            case TEMPERATURE: return e.getApparentTemperature();
            case HUMIDITY:    return e.getHumidity();
            case WIND:        return e.getWindSpeed();
            case PRESSURE:    return e.getPressure();
            default:          return 0;
        }
    }

    private float xPos(int i, float w, int n) {
        return ML + (n <= 1 ? 0 : w * i / (n - 1f));
    }

    private float yPos(double v, float h, double min, double max) {
        return MT + h - (float) ((v - min) / (max - min) * h);
    }

    private double min(double[] a) { double m = a[0]; for (double x : a) if (x < m) m = x; return m; }
    private double max(double[] a) { double m = a[0]; for (double x : a) if (x > m) m = x; return m; }
}
