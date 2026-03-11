package com.atakmap.android.weather.presentation.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import com.atakmap.android.weather.domain.model.WindProfileModel;

import java.util.List;
import java.util.Locale;

/**
 * Canvas-drawn wind barb column for Tab 2 — Wind Profile.
 *
 * Displays the 4 standard altitudes (10 m / 80 m / 120 m / 180 m) as a
 * vertical stack. Each row shows:
 *
 *   [altitude label] — [barb arrow pointing in wind direction] — [speed + temp]
 *
 * ── Barb drawing ─────────────────────────────────────────────────────────────
 *
 * The arrow shaft points FROM where the wind comes (meteorological convention).
 * Length is scaled logarithmically so 2 m/s and 40 m/s are both legible.
 * Speed categories drive the stroke width:
 *   < 5 m/s  — thin, grey
 *   5-15     — normal, cyan
 *  15-25     — thick, orange
 *  > 25      — thick, red
 *
 * ── No external libraries ─────────────────────────────────────────────────────
 * Pure android.graphics — no MPAndroidChart, no Lottie.
 */
public class WindChartView extends View {

    private static final float ROW_HEIGHT_DP = 56f;
    private static final int   ALTITUDES     = 4;

    // Altitude labels in display order (top = highest = 180m)
    private static final int[]    ALT_M     = {180, 120, 80, 10};
    private static final String[] ALT_LABEL = {"180 m", "120 m", " 80 m", " 10 m"};

    private List<WindProfileModel> profiles;
    private int                    selectedHour = 0;

    // ── Paints ────────────────────────────────────────────────────────────────
    private final Paint labelPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint divPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint[] speedPaints; // indexed by speed tier (0-3)

    public WindChartView(Context context) {
        super(context);
        float dp = context.getResources().getDisplayMetrics().density;

        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextSize(11f * dp);
        labelPaint.setTypeface(android.graphics.Typeface.MONOSPACE);

        valuePaint.setColor(Color.parseColor("#CCCCCC"));
        valuePaint.setTextSize(10f * dp);

        divPaint.setColor(Color.parseColor("#33FFFFFF"));
        divPaint.setStrokeWidth(1f);

        speedPaints = new Paint[4];
        int[] colors = {
                Color.parseColor("#888888"),  // calm  < 5
                Color.parseColor("#4FC3F7"),  // light 5-15
                Color.parseColor("#FFB74D"),  // mod  15-25
                Color.parseColor("#FF5C8A")   // strong >25
        };
        float[] widths = {1.5f, 2f, 2.5f, 3f};
        for (int i = 0; i < 4; i++) {
            speedPaints[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
            speedPaints[i].setColor(colors[i]);
            speedPaints[i].setStrokeWidth(widths[i] * dp);
            speedPaints[i].setStyle(Paint.Style.STROKE);
            speedPaints[i].setStrokeCap(Paint.Cap.ROUND);
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setProfiles(List<WindProfileModel> profiles) {
        this.profiles = profiles;
        invalidate();
    }

    public void setSelectedHour(int hour) {
        this.selectedHour = hour;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        float dp   = getContext().getResources().getDisplayMetrics().density;
        int   h    = (int)(ROW_HEIGHT_DP * ALTITUDES * dp);
        int   w    = resolveSize(getSuggestedMinimumWidth(), widthSpec);
        setMeasuredDimension(w, resolveSize(h, heightSpec));
    }

    // ── Draw ───────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (profiles == null || profiles.isEmpty()) return;

        float dp     = getContext().getResources().getDisplayMetrics().density;
        float rowH   = ROW_HEIGHT_DP * dp;
        float width  = getWidth();

        WindProfileModel frame = getFrame();

        for (int r = 0; r < ALTITUDES; r++) {
            float top = r * rowH;
            float mid = top + rowH / 2f;

            // Divider
            if (r > 0) canvas.drawLine(0, top, width, top, divPaint);

            // Find matching altitude entry
            WindProfileModel.AltitudeEntry entry = findAlt(frame, ALT_M[r]);
            if (entry == null) continue;

            // ── Altitude label (left) ─────────────────────────────────────
            canvas.drawText(ALT_LABEL[r], 8f * dp, mid + 4f * dp, labelPaint);

            // ── Wind barb (centre) ────────────────────────────────────────
            float barbCx = width * 0.42f;
            float barbLen = (float)(20f * dp * (1 + Math.log1p(entry.windSpeed) / Math.log1p(30)));
            drawBarb(canvas, barbCx, mid, barbLen, entry.windDirection, speedTier(entry.windSpeed), dp);

            // ── Value readout (right) ─────────────────────────────────────
            String val = String.format(Locale.getDefault(),
                    "%.1fm/s %.0f° %.1f°C",
                    entry.windSpeed, entry.windDirection, entry.temperature);
            // Gusts at 10m
            if (entry.altitudeMeters == 10 && entry.windGusts > 0) {
                val += String.format(Locale.getDefault(), " G%.1f", entry.windGusts);
            }
            float textX = width * 0.56f;
            canvas.drawText(val, textX, mid + 4f * dp, valuePaint);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private WindProfileModel getFrame() {
        int idx = Math.min(selectedHour, profiles.size() - 1);
        return profiles.get(Math.max(0, idx));
    }

    private WindProfileModel.AltitudeEntry findAlt(WindProfileModel frame, int altM) {
        if (frame == null) return null;
        for (WindProfileModel.AltitudeEntry e : frame.getAltitudes()) {
            if (e.altitudeMeters == altM) return e;
        }
        return null;
    }

    /**
     * Draw a wind barb arrow:
     *  - shaft from centre point, pointing INTO the wind direction
     *  - arrowhead at the downwind end
     *
     * @param cx        centre x
     * @param cy        centre y
     * @param len       total shaft length in px
     * @param dirDeg    meteorological wind direction (from)
     * @param tier      speed tier (0-3) → paint selection
     */
    private void drawBarb(Canvas canvas, float cx, float cy,
                          float len, double dirDeg, int tier, float dp) {
        Paint p = speedPaints[tier];

        // Meteorological: direction is where wind comes FROM.
        // Arrow points upwind (into the direction the wind comes from).
        double radFrom = Math.toRadians(dirDeg);  // origin direction
        double radTo   = Math.toRadians(dirDeg + 180); // destination (downwind)

        float hx = (float)(Math.sin(radFrom) * len / 2f);
        float hy = (float)(-Math.cos(radFrom) * len / 2f);
        float tx = (float)(Math.sin(radTo)   * len / 2f);
        float ty = (float)(-Math.cos(radTo)  * len / 2f);

        // Shaft
        canvas.drawLine(cx + hx, cy + hy, cx + tx, cy + ty, p);

        // Arrowhead at downwind end (tx, ty = tail)
        float ahLen = 6f * dp;
        double leftRad  = Math.toRadians(dirDeg + 150);
        double rightRad = Math.toRadians(dirDeg - 150);
        Path arrow = new Path();
        arrow.moveTo(cx + tx, cy + ty);
        arrow.lineTo(cx + tx + (float)(Math.sin(leftRad)  * ahLen),
                cy + ty + (float)(-Math.cos(leftRad)  * ahLen));
        arrow.moveTo(cx + tx, cy + ty);
        arrow.lineTo(cx + tx + (float)(Math.sin(rightRad) * ahLen),
                cy + ty + (float)(-Math.cos(rightRad) * ahLen));
        canvas.drawPath(arrow, p);
    }

    private static int speedTier(double ms) {
        if (ms < 5)  return 0;
        if (ms < 15) return 1;
        if (ms < 25) return 2;
        return 3;
    }
}
