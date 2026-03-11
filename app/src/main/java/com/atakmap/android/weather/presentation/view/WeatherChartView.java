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
 * ── Sprint 4 additions ────────────────────────────────────────────────────────
 *
 * 1. Now-marker (Sprint 6 B-03)
 *    A filled triangle + thin solid line at index 0 (the current hour) so
 *    the user immediately sees where "now" is relative to the forecast window.
 *
 * 2. setActiveSeries(Series[])
 *    Called from WeatherDropDownReceiver after parameter prefs are loaded so
 *    only series whose underlying data was actually fetched are shown.
 *    Passing null restores all four series.
 *
 * 3. explicit invalidate() contract
 *    Callers must call invalidate() after setData() and after setActiveSeries().
 *    The view no longer auto-invalidates inside setData() to avoid redundant
 *    draws during the observation chain.  (setSelectedIndex still auto-invalidates
 *    because it is purely a cursor-move operation with no data change.)
 *
 * Series colours match the legend chips in tab_charts.xml:
 *   Temperature  — #FF5C8A  red
 *   Humidity     — #4FC3F7  cyan
 *   Wind         — #FFB74D  orange
 *   Pressure     — #81C784  green
 */
public class WeatherChartView extends View {

    // ── Series ─────────────────────────────────────────────────────────────
    public enum Series {
        TEMPERATURE ("#FF5C8A"),
        HUMIDITY    ("#4FC3F7"),
        WIND        ("#FFB74D"),
        PRESSURE    ("#81C784");

        public final int color;
        Series(String hex) { this.color = Color.parseColor(hex); }
    }

    // ── Margins ────────────────────────────────────────────────────────────
    private static final float ML = 8f;
    private static final float MR = 8f;
    private static final float MT = 12f;
    private static final float MB = 28f;

    // ── State ──────────────────────────────────────────────────────────────
    private List<HourlyEntryModel>      data;
    private int                         selectedIndex = 0;
    private final Map<Series, Boolean>  visible       = new EnumMap<>(Series.class);
    private Series[]                    activeSeries  = Series.values(); // Sprint 4

    // ── Paints ─────────────────────────────────────────────────────────────
    private final Map<Series, Paint> seriesPaint = new EnumMap<>(Series.class);
    private final Map<Series, Paint> fillPaint   = new EnumMap<>(Series.class);
    private final Paint              gridPaint       = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint              axisLabelPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint              markerPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint              nowLinePaint    = new Paint(Paint.ANTI_ALIAS_FLAG); // Sprint 4/6
    private final Paint              nowTrianglePaint= new Paint(Paint.ANTI_ALIAS_FLAG); // Sprint 6

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
            fp.setColor(s.color & 0x28FFFFFF);
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

        // Sprint 6: now-marker — solid bright white line at index 0
        nowLinePaint.setColor(Color.parseColor("#FFFFFFFF"));
        nowLinePaint.setStrokeWidth(1.5f);
        nowLinePaint.setStyle(Paint.Style.STROKE);

        // Sprint 6: tiny filled triangle pointing down at the top of the now-line
        nowTrianglePaint.setColor(Color.WHITE);
        nowTrianglePaint.setStyle(Paint.Style.FILL);
        nowTrianglePaint.setAntiAlias(true);
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Set hourly data. Caller must call invalidate() after this.
     */
    public void setData(List<HourlyEntryModel> data) {
        this.data = data;
        // deliberate: no invalidate() here — caller does it explicitly
    }

    public void setSelectedIndex(int index) {
        this.selectedIndex = index;
        invalidate(); // cursor move always redraws
    }

    /**
     * Sprint 4: restrict visible series to those whose data was fetched.
     * Pass null to restore all four. Caller must call invalidate() after.
     */
    public void setActiveSeries(Series[] active) {
        this.activeSeries = (active != null) ? active : Series.values();
        // Re-apply visibility — any series not in active set is hidden
        for (Series s : Series.values()) {
            boolean inActive = false;
            for (Series a : this.activeSeries) if (a == s) { inActive = true; break; }
            if (!inActive) visible.put(s, false);
        }
    }

    /** Toggle a series on/off; returns new visible state. */
    public boolean toggleSeries(Series series) {
        boolean next = !Boolean.TRUE.equals(visible.get(series));
        visible.put(series, next);
        invalidate();
        return next;
    }

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

        // Draw each active+visible series
        for (Series s : activeSeries) {
            if (Boolean.TRUE.equals(visible.get(s))) {
                drawSeries(canvas, s, w, h);
            }
        }

        // Sprint 6: now-marker at index 0 (drawn above series, below cursor)
        drawNowMarker(canvas, w, h);
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

        int   n    = raw.length;
        Path  line = new Path();
        Path  fill = new Path();

        float x0 = xPos(0, w, n);
        float y0 = yPos(raw[0], h, min, max);
        line.moveTo(x0, y0);
        fill.moveTo(x0, MT + h);
        fill.lineTo(x0, y0);

        for (int i = 1; i < n; i++) {
            float x = xPos(i, w, n);
            float y = yPos(raw[i], h, min, max);
            line.lineTo(x, y);
            fill.lineTo(x, y);
        }
        fill.lineTo(xPos(n - 1, w, n), MT + h);
        fill.close();

        canvas.drawPath(fill, fillPaint.get(series));
        canvas.drawPath(line, seriesPaint.get(series));

        // Dot at SeekBar cursor
        float cx = xPos(selectedIndex, w, n);
        float cy = yPos(raw[Math.min(selectedIndex, n - 1)], h, min, max);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(series.color);
        dot.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, 4f, dot);
    }

    /**
     * Sprint 6: Now-marker — solid vertical line at index 0 (current hour)
     * with a small downward-pointing triangle cap at the top.
     */
    private void drawNowMarker(Canvas canvas, float w, float h) {
        if (data == null || data.isEmpty()) return;
        float x = xPos(0, w, data.size());
        canvas.drawLine(x, MT, x, MT + h, nowLinePaint);

        // Triangle cap: 6dp base, 5dp height, pointing down from the top
        float tri = 6f;
        Path t = new Path();
        t.moveTo(x - tri, MT - 2f);
        t.lineTo(x + tri, MT - 2f);
        t.lineTo(x,        MT + 5f);
        t.close();
        canvas.drawPath(t, nowTrianglePaint);
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
        return MT + h - (float)((v - min) / (max - min) * h);
    }

    private double min(double[] a) { double m=a[0]; for (double x:a) if(x<m) m=x; return m; }
    private double max(double[] a) { double m=a[0]; for (double x:a) if(x>m) m=x; return m; }
}
