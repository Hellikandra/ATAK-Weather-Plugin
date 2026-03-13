package com.atakmap.android.weather.presentation.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import com.atakmap.android.weather.domain.model.HourlyEntryModel;

import java.util.Calendar;
import java.util.EnumMap;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;

/**
 * Multi-line overlapping weather chart (Canvas View).
 *
 * Sprint 25 additions:
 *   • Y-axis: per-series min/max labels at left edge of chart
 *   • Horizontal reference band: semi-transparent fill between min and max of
 *     the temperature series (human-readable comfortable-range indication)
 *   • Day-of-week markers: thin vertical line + day name ("Mon", "Tue", …) at
 *     00:00 boundaries derived from isoTime ("2024-07-27T00:00")
 *   • ML widened to accommodate Y-axis labels
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
    private static final float ML = 52f;   // left margin wide enough for Y-axis labels
    private static final float MR = 8f;
    private static final float MT = 18f;
    private static final float MB = 28f;

    // ── State ──────────────────────────────────────────────────────────────
    private List<HourlyEntryModel>      data;
    private int                         selectedIndex = 0;
    private final Map<Series, Boolean>  visible       = new EnumMap<>(Series.class);
    private Series[]                    activeSeries  = Series.values();

    // ── Paints ─────────────────────────────────────────────────────────────
    private final Map<Series, Paint> seriesPaint = new EnumMap<>(Series.class);
    private final Map<Series, Paint> fillPaint   = new EnumMap<>(Series.class);
    private final Paint gridPaint        = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisLabelPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint yLabelPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nowLinePaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nowTrianglePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dayLinePaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dayLabelPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rangeBandPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);

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
        axisLabelPaint.setTextSize(19f);

        yLabelPaint.setColor(Color.parseColor("#AAFFFFFF"));
        yLabelPaint.setTextSize(16f);
        yLabelPaint.setTextAlign(Paint.Align.RIGHT);

        markerPaint.setColor(Color.parseColor("#CCFFFFFF"));
        markerPaint.setStrokeWidth(1.5f);
        markerPaint.setStyle(Paint.Style.STROKE);
        markerPaint.setPathEffect(new DashPathEffect(new float[]{4, 4}, 0));

        nowLinePaint.setColor(Color.WHITE);
        nowLinePaint.setStrokeWidth(1.5f);
        nowLinePaint.setStyle(Paint.Style.STROKE);

        nowTrianglePaint.setColor(Color.WHITE);
        nowTrianglePaint.setStyle(Paint.Style.FILL);
        nowTrianglePaint.setAntiAlias(true);

        // Day-boundary: subtle dotted line + day name
        dayLinePaint.setColor(Color.parseColor("#55FFFFFF"));
        dayLinePaint.setStrokeWidth(1f);
        dayLinePaint.setPathEffect(new DashPathEffect(new float[]{3, 6}, 0));

        dayLabelPaint.setColor(Color.parseColor("#99FFFFFF"));
        dayLabelPaint.setTextSize(17f);
        dayLabelPaint.setTextAlign(Paint.Align.CENTER);

        // Temp range band — very subtle light fill between min/max temp
        rangeBandPaint.setColor(Color.parseColor("#18FF5C8A"));
        rangeBandPaint.setStyle(Paint.Style.FILL);
    }

    // ── Public API ─────────────────────────────────────────────────────────

    public void setData(List<HourlyEntryModel> data) {
        this.data = data;
    }

    public void setSelectedIndex(int index) {
        this.selectedIndex = index;
        invalidate();
    }

    public void setActiveSeries(Series[] active) {
        this.activeSeries = (active != null) ? active : Series.values();
        for (Series s : Series.values()) {
            boolean inActive = false;
            for (Series a : this.activeSeries) if (a == s) { inActive = true; break; }
            if (!inActive) visible.put(s, false);
        }
    }

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
        drawDayBoundaries(canvas, w, h);
        drawTempRangeBand(canvas, w, h);

        for (Series s : activeSeries) {
            if (Boolean.TRUE.equals(visible.get(s))) {
                drawSeries(canvas, s, w, h);
            }
        }

        drawNowMarker(canvas, w, h);
        drawCursor(canvas, w, h);
        drawXLabels(canvas, w, h);
        drawYLabels(canvas, w, h);
    }

    // ── Private ────────────────────────────────────────────────────────────

    private void drawGrid(Canvas canvas, float w, float h) {
        for (int i = 0; i <= 4; i++) {
            float y = MT + h * i / 4f;
            canvas.drawLine(ML, y, ML + w, y, gridPaint);
        }
    }

    /**
     * Draw a very light filled band between the TEMPERATURE series' min and
     * max across the whole forecast, giving a visual "range" indicator that
     * helps read expected highs/lows at a glance.
     */
    private void drawTempRangeBand(Canvas canvas, float w, float h) {
        if (data == null || !Boolean.TRUE.equals(visible.get(Series.TEMPERATURE))) return;
        double[] raw = extractAll(Series.TEMPERATURE);
        double min = min(raw), max = max(raw);
        if (max == min) return;
        // Draw a horizontal band between yPos(min) and yPos(max) using the
        // series' own normalization so it always spans full chart height.
        // (For a single visible series this just fills the chart area, which is
        //  the fill path. For multi-series the band is behind the curves.)
        float yMin = yPos(min, h, min, max);   // bottom of band  = MT+h (when v=min)
        float yMax = yPos(max, h, min, max);   // top of band     = MT   (when v=max)
        canvas.drawRect(ML, yMax, ML + w, yMin, rangeBandPaint);
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

        // Dot at cursor
        float cx = xPos(selectedIndex, w, n);
        float cy = yPos(raw[Math.min(selectedIndex, n - 1)], h, min, max);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(series.color);
        dot.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, 4f, dot);
    }

    /**
     * Y-axis: draw min and max value labels for each visible series, stacked
     * at the left edge.  Each label pair is offset vertically by its series
     * position in the active list so they never overlap.
     * Font size is larger and colour-coded to the series so small charts remain
     * readable.
     */
    /**
     * Y-axis labels: for every visible series draw min and max values
     * anchored at the Y pixel positions those values occupy in the chart.
     *
     * Each label is colour-coded to its series, right-aligned into the left
     * margin.  A greedy de-collision pass nudges overlapping labels apart so
     * they remain readable even when all four series are visible simultaneously.
     * A small filled dot on the chart's left edge acts as a leader connecting
     * the label to the data line.
     */
    private void drawYLabels(Canvas canvas, float w, float h) {
        if (data == null) return;

        final float TEXT_SIZE = 24f;  // large enough to read at arm's length
        final float RADIUS    = 4f;
        final float X_EDGE    = ML - 4f;

        yLabelPaint.setTextSize(TEXT_SIZE);
        yLabelPaint.setTextAlign(Paint.Align.RIGHT);

        // Collect (yAnchor, label, colour) for each visible series min and max
        java.util.List<float[]>  anchors = new java.util.ArrayList<>();
        java.util.List<String>   labels  = new java.util.ArrayList<>();
        java.util.List<Integer>  cols    = new java.util.ArrayList<>();
        java.util.List<Boolean>  isMaxes = new java.util.ArrayList<>();

        for (Series s : activeSeries) {
            if (!Boolean.TRUE.equals(visible.get(s))) continue;
            double[] raw = extractAll(s);
            if (raw.length == 0) continue;
            double minV = min(raw), maxV = max(raw);
            int col = (s.color & 0x00FFFFFF) | 0xDD000000;

            anchors.add(new float[]{ yPos(maxV, h, minV, maxV) });
            labels.add(formatYValue(s, maxV));
            cols.add(col);
            isMaxes.add(true);

            anchors.add(new float[]{ yPos(minV, h, minV, maxV) });
            labels.add(formatYValue(s, minV));
            cols.add(col);
            isMaxes.add(false);

            // Dot on chart left edge at max position
            Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            dotPaint.setColor(col);
            dotPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(ML, yPos(maxV, h, minV, maxV), RADIUS, dotPaint);
        }

        if (labels.isEmpty()) return;

        // Nudged Y positions — start from anchors, then de-collide top-to-bottom
        float[] nudged = new float[anchors.size()];
        for (int i = 0; i < anchors.size(); i++) nudged[i] = anchors.get(i)[0];

        Integer[] order = new Integer[anchors.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Float.compare(nudged[a], nudged[b]));

        final float MIN_GAP = TEXT_SIZE + 2f;
        for (int k = 1; k < order.length; k++) {
            int prev = order[k - 1], cur = order[k];
            if (nudged[cur] - nudged[prev] < MIN_GAP) nudged[cur] = nudged[prev] + MIN_GAP;
        }

        for (int i = 0; i < labels.size(); i++) {
            float y = Math.max(MT + TEXT_SIZE, Math.min(MT + h, nudged[i]));
            yLabelPaint.setColor(cols.get(i));
            canvas.drawText(labels.get(i), X_EDGE, y, yLabelPaint);
        }
    }

    private String formatYValue(Series s, double v) {
        switch (s) {
            case TEMPERATURE: return String.format(java.util.Locale.US, "%.0f°", v);
            case HUMIDITY:    return String.format(java.util.Locale.US, "%.0f%%", v);
            case WIND:        return String.format(java.util.Locale.US, "%.0fm/s", v);
            case PRESSURE:    return String.format(java.util.Locale.US, "%.0f", v);
            default:          return String.valueOf((int) v);
        }
    }

    /**
     * Day-of-week boundaries: at every index where the isoTime transitions to
     * 00:00, draw a subtle dotted vertical line and the 3-letter day name just
     * above the bottom of the chart.
     *
     * isoTime format: "2024-07-27T14:00"
     */
    private void drawDayBoundaries(Canvas canvas, float w, float h) {
        if (data == null) return;
        int n = data.size();
        String[] days = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};

        for (int i = 0; i < n; i++) {
            String iso = data.get(i).getIsoTime();
            if (iso == null || !iso.contains("T00:00")) continue;

            float x = xPos(i, w, n);
            canvas.drawLine(x, MT, x, MT + h, dayLinePaint);

            // Parse day-of-week from "YYYY-MM-DDTHH:MM"
            try {
                int year  = Integer.parseInt(iso.substring(0, 4));
                int month = Integer.parseInt(iso.substring(5, 7));
                int day   = Integer.parseInt(iso.substring(8, 10));
                Calendar cal = new GregorianCalendar(year, month - 1, day);
                int dow = cal.get(Calendar.DAY_OF_WEEK); // 1=Sun
                canvas.drawText(days[dow - 1], x, MT + h + MB - 6f, dayLabelPaint);
            } catch (Exception ignored) {}
        }
    }

    private void drawNowMarker(Canvas canvas, float w, float h) {
        if (data == null || data.isEmpty()) return;
        float x = xPos(0, w, data.size());
        canvas.drawLine(x, MT, x, MT + h, nowLinePaint);

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

    /**
     * X-axis labels: compact day+time labels at day boundaries and every 12 h.
     *
     * Format:
     *   - At 00:00 transitions: "Mon" (3-letter day name, slightly larger, dimmer background tick)
     *   - At 12:00:            "12:00"
     *   - Every other step:   "HH:00"
     *
     * A vertical dashed day-boundary line is drawn in {@link #drawDayBoundaries} —
     * the label here is positioned just below the chart area.
     */
    private void drawXLabels(Canvas canvas, float w, float h) {
        if (data == null || data.isEmpty()) return;
        int n    = data.size();
        // Step: every 6h when ≤48 entries, every 12h when >48
        int step = (n <= 48) ? 6 : 12;

        axisLabelPaint.setTextAlign(Paint.Align.CENTER);

        for (int i = 0; i < n; i += step) {
            String iso = data.get(i).getIsoTime(); // "2024-07-27T14:00"
            if (iso == null || iso.length() < 16) continue;

            String hhmm = iso.substring(11, 16); // "00:00" or "14:00"
            boolean isMidnight = hhmm.startsWith("00:");
            boolean isNoon     = hhmm.startsWith("12:");

            String label;
            if (isMidnight) {
                // Show 3-letter day name at midnight transition
                label = abbrevDay(iso);
                axisLabelPaint.setTextSize(15f);
                axisLabelPaint.setColor(0xFFFFCC44); // amber for day transitions
            } else if (isNoon) {
                label = "Noon";
                axisLabelPaint.setTextSize(13f);
                axisLabelPaint.setColor(0xCCFFFFFF);
            } else {
                label = hhmm;
                axisLabelPaint.setTextSize(12f);
                axisLabelPaint.setColor(0x99FFFFFF);
            }

            float x = xPos(i, w, n);
            canvas.drawText(label, x, MT + h + 18f, axisLabelPaint);
        }
    }

    /** Returns 3-letter English day abbreviation from an ISO date-time string. */
    private static String abbrevDay(String iso) {
        if (iso == null || iso.length() < 10) return "";
        try {
            int yr  = Integer.parseInt(iso.substring(0, 4));
            int mo  = Integer.parseInt(iso.substring(5, 7));
            int day = Integer.parseInt(iso.substring(8, 10));
            java.util.Calendar cal = new java.util.GregorianCalendar(yr, mo - 1, day);
            String[] days = {"Sun","Mon","Tue","Wed","Thu","Fri","Sat"};
            return days[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1];
        } catch (Exception e) { return ""; }
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

    private double min(double[] a) { double m = a[0]; for (double x : a) if (x < m) m = x; return m; }
    private double max(double[] a) { double m = a[0]; for (double x : a) if (x > m) m = x; return m; }
}
