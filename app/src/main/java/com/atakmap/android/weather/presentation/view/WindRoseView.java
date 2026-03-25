package com.atakmap.android.weather.presentation.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import com.atakmap.android.weather.domain.model.WindDataPoint;

import java.util.List;
import java.util.Locale;

/**
 * WindRoseView — Canvas-drawn wind rose widget.
 *
 * <p>Sprint 9 (S9.3): Displays wind direction and speed frequency distribution
 * as a polar bar chart with 16 directional sectors and Beaufort-scale colour bands.</p>
 *
 * <h3>Sectors</h3>
 * 16 sectors of 22.5 degrees each: N, NNE, NE, ENE, E, ESE, SE, SSE,
 * S, SSW, SW, WSW, W, WNW, NW, NNW.
 *
 * <h3>Beaufort Colour Scale</h3>
 * <ul>
 *   <li>0-1 m/s = light blue (#B3E5FC)</li>
 *   <li>2-3 m/s = green (#81C784)</li>
 *   <li>4-5 m/s = yellow (#FFF176)</li>
 *   <li>6-8 m/s = orange (#FFB74D)</li>
 *   <li>9-11 m/s = red (#E57373)</li>
 *   <li>12+ m/s = magenta (#CE93D8)</li>
 * </ul>
 *
 * <h3>Drawing Approach</h3>
 * <ol>
 *   <li>Count wind observations per sector, per Beaufort band.</li>
 *   <li>Draw concentric reference rings (10%, 20%, 30% frequency).</li>
 *   <li>For each sector, draw stacked coloured wedges from center outward.</li>
 *   <li>Draw N/E/S/W compass labels.</li>
 *   <li>Center shows calm wind percentage.</li>
 * </ol>
 */
public class WindRoseView extends View {

    private static final String TAG = "WindRoseView";

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final int NUM_SECTORS = 16;
    private static final int NUM_BANDS   = 6;
    private static final float SECTOR_ANGLE_DEG = 360f / NUM_SECTORS; // 22.5
    private static final float WEDGE_WIDTH_DEG  = 18f;  // slightly narrower than sector

    /** Beaufort band colours. */
    private static final int[] BAND_COLORS = {
            Color.parseColor("#B3E5FC"),  // 0-1 m/s: light blue
            Color.parseColor("#81C784"),  // 2-3 m/s: green
            Color.parseColor("#FFF176"),  // 4-5 m/s: yellow
            Color.parseColor("#FFB74D"),  // 6-8 m/s: orange
            Color.parseColor("#E57373"),  // 9-11 m/s: red
            Color.parseColor("#CE93D8"),  // 12+ m/s: magenta
    };

    private static final String[] SECTOR_LABELS = {
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
    };

    private static final String[] BAND_LABELS = {
            "0-1", "2-3", "4-5", "6-8", "9-11", "12+"
    };

    // ── Data ──────────────────────────────────────────────────────────────────
    /** Sector x Band frequency counts. */
    private int[][] sectorBandCounts = new int[NUM_SECTORS][NUM_BANDS];
    private int     totalObservations = 0;
    private int     calmCount         = 0;
    private float   maxSectorPct      = 0;

    private String title = "Wind Rose";

    // ── Zoom (button-only, no pinch gesture) ───────────────────────────────
    private float scaleFactor = 1.0f;

    // ── Paints ────────────────────────────────────────────────────────────────
    private final Paint ringPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wedgePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint legendPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint legendBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public WindRoseView(Context context) {
        super(context);
        float dp = context.getResources().getDisplayMetrics().density;

        ringPaint.setColor(Color.parseColor("#33FFFFFF"));
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(1f);

        wedgePaint.setStyle(Paint.Style.FILL);

        labelPaint.setColor(Color.parseColor("#CCFFFFFF"));
        labelPaint.setTextSize(10f * dp);
        labelPaint.setTypeface(Typeface.MONOSPACE);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        titlePaint.setColor(Color.WHITE);
        titlePaint.setTextSize(11f * dp);
        titlePaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        titlePaint.setTextAlign(Paint.Align.CENTER);

        centerPaint.setColor(Color.parseColor("#AAFFFFFF"));
        centerPaint.setTextSize(10f * dp);
        centerPaint.setTypeface(Typeface.MONOSPACE);
        centerPaint.setTextAlign(Paint.Align.CENTER);

        legendPaint.setColor(Color.parseColor("#AAFFFFFF"));
        legendPaint.setTextSize(8f * dp);
        legendPaint.setTypeface(Typeface.MONOSPACE);

        legendBoxPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Pinch zoom disabled — use +/- buttons instead for cleaner UX
        return super.onTouchEvent(event);
    }

    /**
     * Set the zoom scale factor programmatically (e.g. from +/- buttons).
     * Clamped to [0.5, 3.0].
     */
    public void setScaleFactor(float factor) {
        scaleFactor = Math.max(0.5f, Math.min(factor, 3.0f));
        invalidate();
    }

    /** Return the current scale factor. */
    public float getScaleFactor() {
        return scaleFactor;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Set the wind data to display.
     *
     * @param data list of wind observations (direction + speed pairs)
     */
    public void setWindData(List<WindDataPoint> data) {
        // Reset
        sectorBandCounts = new int[NUM_SECTORS][NUM_BANDS];
        totalObservations = 0;
        calmCount = 0;

        if (data == null || data.isEmpty()) {
            maxSectorPct = 0;
            invalidate();
            return;
        }

        totalObservations = data.size();

        for (WindDataPoint point : data) {
            int band = point.getBeaufortBand();
            if (band == 0) {
                // Calm: don't assign to a sector, just count
                calmCount++;
            } else {
                int sector = point.getSectorIndex();
                sectorBandCounts[sector][band]++;
            }
        }

        // Find max sector total for scaling
        maxSectorPct = 0;
        for (int s = 0; s < NUM_SECTORS; s++) {
            int sectorTotal = 0;
            for (int b = 0; b < NUM_BANDS; b++) {
                sectorTotal += sectorBandCounts[s][b];
            }
            float pct = (float) sectorTotal / totalObservations * 100f;
            if (pct > maxSectorPct) maxSectorPct = pct;
        }

        invalidate();
    }

    /**
     * Set the chart title.
     *
     * @param title e.g. "Wind Rose — Past 24h"
     */
    public void setTitle(String title) {
        this.title = title;
        invalidate();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Drawing
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float dp = getContext().getResources().getDisplayMetrics().density;
        float w  = getWidth();
        float h  = getHeight();

        // Title at top
        float titleY = 14f * dp;
        if (title != null) {
            canvas.drawText(title, w / 2f, titleY, titlePaint);
        }

        // Empty state
        if (totalObservations == 0) {
            centerPaint.setTextSize(11f * dp);
            canvas.drawText("No wind data", w / 2f, h / 2f, centerPaint);
            return;
        }

        // Rose area
        float topMargin = 22f * dp;
        float bottomMargin = 32f * dp; // legend space
        float sideMargin = 24f * dp;

        float roseW = w - 2 * sideMargin;
        float roseH = h - topMargin - bottomMargin;
        float roseDim = Math.min(roseW, roseH);
        float radius = roseDim / 2f - 12f * dp;
        float cx = w / 2f;

        // Apply zoom scale around the rose center
        float cy0 = topMargin + roseH / 2f;
        canvas.save();
        canvas.translate(cx, cy0);
        canvas.scale(scaleFactor, scaleFactor);
        canvas.translate(-cx, -cy0);
        float cy = topMargin + roseH / 2f;

        // ── Concentric reference rings ─────────────────────────────────
        float ringScale = Math.max(maxSectorPct, 30f);  // scale to at least 30%
        float[] ringPcts = {10f, 20f, 30f};
        for (float pct : ringPcts) {
            if (pct > ringScale) break;
            float ringR = radius * (pct / ringScale);
            canvas.drawCircle(cx, cy, ringR, ringPaint);
            // Ring label
            labelPaint.setTextSize(7f * dp);
            labelPaint.setColor(Color.parseColor("#55FFFFFF"));
            canvas.drawText(String.format(Locale.US, "%.0f%%", pct),
                    cx + ringR + 2f * dp, cy - 2f * dp, labelPaint);
        }
        labelPaint.setColor(Color.parseColor("#CCFFFFFF"));
        labelPaint.setTextSize(10f * dp);

        // ── Draw sector wedges ─────────────────────────────────────────
        for (int s = 0; s < NUM_SECTORS; s++) {
            float sectorAngle = s * SECTOR_ANGLE_DEG; // 0=N, clockwise
            // Canvas arc uses east=0, clockwise. Convert: canvasAngle = sectorAngle - 90
            float wedgeStart = sectorAngle - 90f - WEDGE_WIDTH_DEG / 2f;

            // Stack bands from center outward
            float innerR = 0;
            for (int b = 0; b < NUM_BANDS; b++) {
                int count = sectorBandCounts[s][b];
                if (count == 0) continue;

                float pct = (float) count / totalObservations * 100f;
                float outerR = innerR + radius * (pct / ringScale);

                wedgePaint.setColor(BAND_COLORS[b]);
                wedgePaint.setAlpha(200);

                RectF outerRect = new RectF(cx - outerR, cy - outerR, cx + outerR, cy + outerR);
                if (innerR > 0) {
                    // Draw arc wedge with hole (use path)
                    Path wedgePath = new Path();
                    wedgePath.arcTo(outerRect, wedgeStart, WEDGE_WIDTH_DEG, true);
                    RectF innerRect = new RectF(cx - innerR, cy - innerR, cx + innerR, cy + innerR);
                    wedgePath.arcTo(innerRect, wedgeStart + WEDGE_WIDTH_DEG, -WEDGE_WIDTH_DEG);
                    wedgePath.close();
                    canvas.drawPath(wedgePath, wedgePaint);
                } else {
                    // Simple arc from center
                    canvas.drawArc(outerRect, wedgeStart, WEDGE_WIDTH_DEG, true, wedgePaint);
                }

                innerR = outerR;
            }
        }

        // ── Compass labels — all 16 directions ─────────────────────────
        float labelRadius = radius + 10f * dp;
        for (int s = 0; s < NUM_SECTORS; s++) {
            float angle = s * SECTOR_ANGLE_DEG;
            double rad = Math.toRadians(angle);
            float lx = cx + (float) Math.sin(rad) * labelRadius;
            float ly = cy - (float) Math.cos(rad) * labelRadius;
            boolean major = (s % 4 == 0); // N, E, S, W
            labelPaint.setTextSize(major ? 11f * dp : 8f * dp);
            labelPaint.setColor(major ? Color.WHITE : Color.parseColor("#99FFFFFF"));
            // Vertical centering adjustment
            ly += labelPaint.getTextSize() / 3f;
            canvas.drawText(SECTOR_LABELS[s], lx, ly, labelPaint);
        }

        // ── Sector percentage labels (on top sectors > 5%) ────────────
        Paint pctPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pctPaint.setColor(Color.WHITE);
        pctPaint.setTextSize(7f * dp);
        pctPaint.setTextAlign(Paint.Align.CENTER);
        pctPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        pctPaint.setShadowLayer(2f, 0, 0, Color.BLACK);
        for (int s = 0; s < NUM_SECTORS; s++) {
            int sectorTotal = 0;
            for (int b = 0; b < NUM_BANDS; b++) sectorTotal += sectorBandCounts[s][b];
            float pct = (float) sectorTotal / totalObservations * 100f;
            if (pct < 5f) continue;
            float sectorAngle = s * SECTOR_ANGLE_DEG;
            double rad2 = Math.toRadians(sectorAngle);
            float barR = radius * (pct / ringScale);
            float midR = barR * 0.6f;
            float px = cx + (float) Math.sin(rad2) * midR;
            float py = cy - (float) Math.cos(rad2) * midR + pctPaint.getTextSize() / 3f;
            canvas.drawText(String.format(Locale.US, "%.0f%%", pct), px, py, pctPaint);
        }

        // ── Calm percentage in center ──────────────────────────────────
        float calmPct = (float) calmCount / totalObservations * 100f;
        centerPaint.setTextSize(10f * dp);
        canvas.drawText("Calm", cx, cy - 2f * dp, centerPaint);
        canvas.drawText(String.format(Locale.US, "%.0f%%", calmPct), cx, cy + 12f * dp, centerPaint);

        // Restore canvas from zoom transform before drawing legend
        canvas.restore();

        // ── Legend at bottom ────────────────────────────────────────────
        float legendY = h - 8f * dp;
        float legendX = 8f * dp;
        float boxSize = 8f * dp;
        float spacing = 4f * dp;
        String[] legendLabels = {"0-1", "2-3", "4-5", "6-8", "9-11", "12+"};
        String unit = "m/s";

        for (int b = 0; b < NUM_BANDS; b++) {
            legendBoxPaint.setColor(BAND_COLORS[b]);
            canvas.drawRect(legendX, legendY - boxSize, legendX + boxSize, legendY, legendBoxPaint);
            legendX += boxSize + 2f * dp;
            String text = legendLabels[b];
            canvas.drawText(text, legendX, legendY, legendPaint);
            legendX += legendPaint.measureText(text) + spacing;
        }
        canvas.drawText(unit, legendX, legendY, legendPaint);
    }
}
