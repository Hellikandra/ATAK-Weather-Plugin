package com.atakmap.android.weather.presentation.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.atakmap.android.weather.domain.model.HourlyEntryModel;

import java.util.Calendar;
import java.util.EnumMap;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Multi-line overlapping weather chart (Canvas View).
 *
 * Features:
 *   • Multi-series overlay (temperature, humidity, wind, pressure)
 *   • Y-axis per-series min/max labels with anti-collision
 *   • Day-of-week markers at midnight boundaries
 *   • Temperature range band
 *   • Pinch-to-zoom (horizontal) + pan gesture
 *   • Programmatic zoom via setZoomLevel() for +/- buttons
 *   • Adaptive X-axis labels (no overlap)
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
    private static final float MB = 32f;   // increased for rotated labels

    // ── Chart mode constants ────────────────────────────────────────────
    public static final int CHART_TEMP     = 0;
    public static final int CHART_WIND     = 1;
    public static final int CHART_PRECIP   = 2;
    public static final int CHART_COMBINED = 3;

    private int chartMode = CHART_COMBINED;

    // ── Time range ───────────────────────────────────────────────────────
    private int timeRangeHours = 0;

    // ── Zoom / Pan ───────────────────────────────────────────────────────
    private float zoomLevel = 1.0f;
    private static final float MIN_ZOOM = 1.0f;
    private static final float MAX_ZOOM = 6.0f;
    /** Pan offset in data indices (0 = leftmost). */
    private float panOffset = 0f;

    // ── State ──────────────────────────────────────────────────────────────
    private List<HourlyEntryModel>      data;
    private List<HourlyEntryModel>      effectiveData;
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

    // ── Gesture detectors ──────────────────────────────────────────────────
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector      panDetector;

    // ── Zoom change listener ───────────────────────────────────────────────
    public interface ZoomChangeListener {
        void onZoomChanged(float zoomLevel, int visibleHours, int totalHours);
    }
    private ZoomChangeListener zoomChangeListener;

    public void setZoomChangeListener(ZoomChangeListener l) { this.zoomChangeListener = l; }

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

        dayLinePaint.setColor(Color.parseColor("#55FFFFFF"));
        dayLinePaint.setStrokeWidth(1f);
        dayLinePaint.setPathEffect(new DashPathEffect(new float[]{3, 6}, 0));

        dayLabelPaint.setColor(Color.parseColor("#99FFFFFF"));
        dayLabelPaint.setTextSize(17f);
        dayLabelPaint.setTextAlign(Paint.Align.CENTER);

        rangeBandPaint.setColor(Color.parseColor("#18FF5C8A"));
        rangeBandPaint.setStyle(Paint.Style.FILL);

        // Pinch-to-zoom
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float oldZoom = zoomLevel;
                zoomLevel *= detector.getScaleFactor();
                zoomLevel = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoomLevel));
                clampPan();
                if (oldZoom != zoomLevel) {
                    notifyZoomChanged();
                    invalidate();
                }
                return true;
            }
        });

        // Pan (horizontal scroll)
        panDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (zoomLevel <= 1.0f) return false;
                int n = getVisibleDataCount();
                float chartW = getWidth() - ML - MR;
                if (chartW <= 0 || n <= 1) return false;
                float pxPerIndex = (chartW * zoomLevel) / (n - 1);
                panOffset += distanceX / pxPerIndex;
                clampPan();
                invalidate();
                return true;
            }
        });
    }

    // ── Public API ─────────────────────────────────────────────────────────

    public void setData(List<HourlyEntryModel> data) {
        this.data = data;
        applyTimeRange();
    }

    public void setTimeRange(int hours) {
        this.timeRangeHours = hours;
        applyTimeRange();
        panOffset = 0;
        notifyZoomChanged();
        invalidate();
    }

    public int getTimeRange() { return timeRangeHours; }

    public void setChartMode(int mode) {
        this.chartMode = mode;
        android.view.ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp != null) {
            lp.height = (mode == CHART_COMBINED)
                    ? android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    : (int) (280 * getResources().getDisplayMetrics().density);
            setLayoutParams(lp);
        }
        invalidate();
    }

    public int getChartMode() { return chartMode; }

    /** Set zoom level programmatically (from +/- buttons). */
    public void setZoomLevel(float zoom) {
        zoomLevel = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
        clampPan();
        notifyZoomChanged();
        invalidate();
    }

    public float getZoomLevel() { return zoomLevel; }

    /** Zoom in by one step. */
    public void zoomIn() { setZoomLevel(zoomLevel * 1.5f); }

    /** Zoom out by one step. */
    public void zoomOut() { setZoomLevel(zoomLevel / 1.5f); }

    private void applyTimeRange() {
        if (data == null) {
            effectiveData = null;
            return;
        }
        if (timeRangeHours <= 0 || timeRangeHours >= data.size()) {
            effectiveData = data;
        } else {
            effectiveData = data.subList(0, Math.min(timeRangeHours, data.size()));
        }
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

    // ── Touch ──────────────────────────────────────────────────────────────

    /**
     * Touch handling: multi-touch (pinch) → zoom, two-finger scroll → pan.
     * Single-finger touches are NOT consumed so the overlay SeekBar can handle
     * cursor scrubbing. This works because the SeekBar sits on top in the
     * FrameLayout z-order and handles single-finger drags.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Always feed gestures to detectors
        scaleDetector.onTouchEvent(event);
        panDetector.onTouchEvent(event);
        // Only consume if multi-finger gesture active
        if (event.getPointerCount() >= 2) return true;
        return super.onTouchEvent(event);
    }

    // ── Draw ───────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        List<HourlyEntryModel> drawData = (effectiveData != null) ? effectiveData : data;
        if (drawData == null || drawData.isEmpty()) return;

        float w = getWidth()  - ML - MR;
        float h = getHeight() - MT - MB;

        // Clip to chart area for zoomed drawing
        canvas.save();
        canvas.clipRect(ML, 0, ML + w, getHeight());

        drawGrid(canvas, w, h);
        drawDayBoundaries(canvas, w, h);
        drawTempRangeBand(canvas, w, h);

        for (Series s : activeSeries) {
            if (Boolean.TRUE.equals(visible.get(s)) && isSeriesVisibleForMode(s)) {
                drawSeries(canvas, s, w, h);
            }
        }

        drawNowMarker(canvas, w, h);
        drawCursor(canvas, w, h);
        drawXLabels(canvas, w, h);

        canvas.restore();

        // Y-labels outside clip area (in left margin)
        drawYLabels(canvas, w, h);
    }

    // ── Private drawing ────────────────────────────────────────────────────

    private boolean isSeriesVisibleForMode(Series s) {
        switch (chartMode) {
            case CHART_TEMP:   return s == Series.TEMPERATURE;
            case CHART_WIND:   return s == Series.WIND;
            case CHART_PRECIP: return s == Series.HUMIDITY;
            case CHART_COMBINED:
            default:           return true;
        }
    }

    private void drawGrid(Canvas canvas, float w, float h) {
        for (int i = 0; i <= 4; i++) {
            float y = MT + h * i / 4f;
            canvas.drawLine(ML, y, ML + w, y, gridPaint);
        }
    }

    private void drawTempRangeBand(Canvas canvas, float w, float h) {
        if (data == null || !Boolean.TRUE.equals(visible.get(Series.TEMPERATURE))
                || !isSeriesVisibleForMode(Series.TEMPERATURE)) return;
        double[] raw = extractAll(Series.TEMPERATURE);
        double min = min(raw), max = max(raw);
        if (max == min) return;
        float yMin = yPos(min, h, min, max);
        float yMax = yPos(max, h, min, max);
        canvas.drawRect(ML, yMax, ML + w, yMin, rangeBandPaint);
    }

    private void drawSeries(Canvas canvas, Series series, float w, float h) {
        double[] raw = extractAll(series);
        double min = min(raw), max = max(raw);
        if (max == min) max = min + 1;

        int   n    = raw.length;
        Path  line = new Path();
        Path  fill = new Path();

        float x0 = xPosZoomed(0, w, n);
        float y0 = yPos(raw[0], h, min, max);
        line.moveTo(x0, y0);
        fill.moveTo(x0, MT + h);
        fill.lineTo(x0, y0);

        for (int i = 1; i < n; i++) {
            float x = xPosZoomed(i, w, n);
            float y = yPos(raw[i], h, min, max);
            line.lineTo(x, y);
            fill.lineTo(x, y);
        }
        fill.lineTo(xPosZoomed(n - 1, w, n), MT + h);
        fill.close();

        canvas.drawPath(fill, fillPaint.get(series));
        canvas.drawPath(line, seriesPaint.get(series));

        // Dot at cursor
        int si = Math.min(selectedIndex, n - 1);
        float cx = xPosZoomed(si, w, n);
        float cy = yPos(raw[si], h, min, max);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(series.color);
        dot.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, 4f, dot);
    }

    private void drawYLabels(Canvas canvas, float w, float h) {
        if (data == null) return;

        final float TEXT_SIZE = 24f;
        final float RADIUS    = 4f;
        final float X_EDGE    = ML - 4f;

        yLabelPaint.setTextSize(TEXT_SIZE);
        yLabelPaint.setTextAlign(Paint.Align.RIGHT);

        java.util.List<float[]>  anchors = new java.util.ArrayList<>();
        java.util.List<String>   labels  = new java.util.ArrayList<>();
        java.util.List<Integer>  cols    = new java.util.ArrayList<>();

        for (Series s : activeSeries) {
            if (!Boolean.TRUE.equals(visible.get(s))) continue;
            double[] raw = extractAll(s);
            if (raw.length == 0) continue;
            double minV = min(raw), maxV = max(raw);
            int col = (s.color & 0x00FFFFFF) | 0xDD000000;

            anchors.add(new float[]{ yPos(maxV, h, minV, maxV) });
            labels.add(formatYValue(s, maxV));
            cols.add(col);

            anchors.add(new float[]{ yPos(minV, h, minV, maxV) });
            labels.add(formatYValue(s, minV));
            cols.add(col);

            Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            dotPaint.setColor(col);
            dotPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(ML, yPos(maxV, h, minV, maxV), RADIUS, dotPaint);
        }

        if (labels.isEmpty()) return;

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
            case TEMPERATURE: return String.format(Locale.US, "%.0f°", v);
            case HUMIDITY:    return String.format(Locale.US, "%.0f%%", v);
            case WIND:        return String.format(Locale.US, "%.0fm/s", v);
            case PRESSURE:    return String.format(Locale.US, "%.0f", v);
            default:          return String.valueOf((int) v);
        }
    }

    private void drawDayBoundaries(Canvas canvas, float w, float h) {
        List<HourlyEntryModel> dd = getDrawData();
        if (dd == null) return;
        int n = dd.size();
        String[] days = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};

        for (int i = 0; i < n; i++) {
            String iso = dd.get(i).getIsoTime();
            if (iso == null || !iso.contains("T00:00")) continue;

            float x = xPosZoomed(i, w, n);
            if (x < ML - 5 || x > ML + w + 5) continue; // off-screen
            canvas.drawLine(x, MT, x, MT + h, dayLinePaint);

            try {
                int year  = Integer.parseInt(iso.substring(0, 4));
                int month = Integer.parseInt(iso.substring(5, 7));
                int day   = Integer.parseInt(iso.substring(8, 10));
                Calendar cal = new GregorianCalendar(year, month - 1, day);
                int dow = cal.get(Calendar.DAY_OF_WEEK);
                canvas.drawText(days[dow - 1], x, MT + h + MB - 8f, dayLabelPaint);
            } catch (Exception ignored) {}
        }
    }

    private void drawNowMarker(Canvas canvas, float w, float h) {
        List<HourlyEntryModel> dd = getDrawData();
        if (dd == null || dd.isEmpty()) return;
        int n = dd.size();
        float x = xPosZoomed(0, w, n);
        if (x < ML - 5 || x > ML + w + 5) return;
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
        List<HourlyEntryModel> dd = getDrawData();
        if (dd == null) return;
        int n = dd.size();
        float x = xPosZoomed(selectedIndex, w, n);
        if (x < ML - 5 || x > ML + w + 5) return;
        canvas.drawLine(x, MT, x, MT + h, markerPaint);
    }

    /**
     * X-axis labels with adaptive spacing to prevent overlap.
     * Uses the zoomed coordinate system, and only draws labels that are visible.
     */
    private void drawXLabels(Canvas canvas, float w, float h) {
        List<HourlyEntryModel> dd = getDrawData();
        if (dd == null || dd.isEmpty()) return;
        int n = dd.size();

        // Calculate visible data points per pixel for adaptive step
        float visibleRange = n / zoomLevel;
        int step;
        if (visibleRange <= 24)      step = 3;   // every 3h when zoomed in a lot
        else if (visibleRange <= 48) step = 6;   // every 6h
        else if (visibleRange <= 96) step = 12;  // every 12h
        else                         step = 24;  // every 24h for 7-day view

        axisLabelPaint.setTextAlign(Paint.Align.CENTER);

        for (int i = 0; i < n; i += step) {
            String iso = dd.get(i).getIsoTime();
            if (iso == null || iso.length() < 16) continue;

            float x = xPosZoomed(i, w, n);
            if (x < ML - 10 || x > ML + w + 10) continue; // off-screen

            String hhmm = iso.substring(11, 16);
            boolean isMidnight = hhmm.startsWith("00:");
            boolean isNoon     = hhmm.startsWith("12:");

            String label;
            if (isMidnight) {
                label = abbrevDay(iso);
                axisLabelPaint.setTextSize(13f);
                axisLabelPaint.setColor(0xFFFFCC44);
            } else if (isNoon) {
                label = "12h";
                axisLabelPaint.setTextSize(11f);
                axisLabelPaint.setColor(0xCCFFFFFF);
            } else {
                label = hhmm.substring(0, 2) + "h";
                axisLabelPaint.setTextSize(10f);
                axisLabelPaint.setColor(0x99FFFFFF);
            }

            canvas.drawText(label, x, MT + h + 16f, axisLabelPaint);
        }
    }

    private static String abbrevDay(String iso) {
        if (iso == null || iso.length() < 10) return "";
        try {
            int yr  = Integer.parseInt(iso.substring(0, 4));
            int mo  = Integer.parseInt(iso.substring(5, 7));
            int day = Integer.parseInt(iso.substring(8, 10));
            Calendar cal = new GregorianCalendar(yr, mo - 1, day);
            String[] days = {"Sun","Mon","Tue","Wed","Thu","Fri","Sat"};
            return days[cal.get(Calendar.DAY_OF_WEEK) - 1];
        } catch (Exception e) { return ""; }
    }

    // ── Data / coordinate helpers ──────────────────────────────────────────

    private List<HourlyEntryModel> getDrawData() {
        return (effectiveData != null) ? effectiveData : data;
    }

    private int getVisibleDataCount() {
        List<HourlyEntryModel> dd = getDrawData();
        return (dd != null) ? dd.size() : 0;
    }

    private double[] extractAll(Series series) {
        List<HourlyEntryModel> dd = getDrawData();
        if (dd == null) return new double[0];
        double[] v = new double[dd.size()];
        for (int i = 0; i < dd.size(); i++) v[i] = extract(series, dd.get(i));
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

    /**
     * X position with zoom and pan applied.
     * When zoom > 1, the chart content is wider than the view; panOffset shifts
     * the visible window.
     */
    private float xPosZoomed(int i, float w, int n) {
        if (n <= 1) return ML;
        float rawFraction = (float) i / (n - 1f);
        // Apply zoom: stretch the total width, then shift by panOffset
        float totalZoomedWidth = w * zoomLevel;
        float panPx = (n <= 1) ? 0 : panOffset / (n - 1f) * totalZoomedWidth;
        return ML + rawFraction * totalZoomedWidth - panPx;
    }

    private float yPos(double v, float h, double min, double max) {
        return MT + h - (float)((v - min) / (max - min) * h);
    }

    private void clampPan() {
        int n = getVisibleDataCount();
        if (n <= 1 || zoomLevel <= 1.0f) { panOffset = 0; return; }
        float maxPan = (n - 1) * (1.0f - 1.0f / zoomLevel);
        panOffset = Math.max(0, Math.min(maxPan, panOffset));
    }

    private void notifyZoomChanged() {
        if (zoomChangeListener != null) {
            int total = getVisibleDataCount();
            int vis = (int) Math.ceil(total / zoomLevel);
            zoomChangeListener.onZoomChanged(zoomLevel, vis, total);
        }
    }

    private double min(double[] a) {
        if (a.length == 0) return 0;
        double m = a[0]; for (double x : a) if (x < m) m = x; return m;
    }
    private double max(double[] a) {
        if (a.length == 0) return 0;
        double m = a[0]; for (double x : a) if (x > m) m = x; return m;
    }
}
