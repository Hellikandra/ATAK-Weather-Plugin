package com.atakmap.android.weather.presentation.view;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.atakmap.android.weather.domain.model.WindProfileModel;
import com.atakmap.android.weather.util.WeatherUnitConverter;

import java.util.List;
import java.util.Locale;

/**
 * WindChartView — animated per-altitude wind profile display.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * Layout (one row per altitude tier, highest at top)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  ┌──────┬──────────────────────────────────────┬───────┬──────────────┐
 *  │ 180m │ ████████░░░░ speed bar (gradient)    │  →    │ dir° temp°C │
 *  │ 120m │ ████████████░░░░░░░░░░               │  ↗    │ dir° temp°C │
 *  │  80m │ ████░░░░░░░░░░░░░░░░░░░░░░           │  ↑    │ dir° temp°C │
 *  │  10m │ ██████████████████░░░░░░░░  G x.x    │  ↖    │ dir° temp°C │
 *  └──────┴──────────────────────────────────────┴───────┴──────────────┘
 *
 *  Speed bar — left-aligned, width ∝ speed.  Fill is a colour gradient:
 *    dark→vivid using the same 7-tier palette as the 3D cones.
 *    A faint vertical "max over full forecast" tick anchors each bar.
 *
 *  Arrow — points DOWNWIND (meteorological convention: arrow tip points
 *    where wind is going, tail points where it comes from).
 *    Arrow colour matches the speed tier.  Stroke weight scales with speed.
 *
 *  Numeric — direction°, temperature°C, gusts at 10 m.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * Sprint 9 — Pressure-level rendering
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  Surface entries (10m, 80m, 120m, 180m): solid dots + solid lines.
 *  Pressure entries (1000..300 hPa): hollow dots + dashed lines.
 *  Legend shown when pressure data is present.
 *  Y-axis scales to max altitude (up to 12km for pressure data).
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * Animation
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * When setSelectedHour() is called with a new hour, a 220 ms ValueAnimator
 * (DecelerateInterpolator) interpolates PER ROW:
 *
 *   • Bar width   — linearly from prevSpeed  → targetSpeed
 *   • Arrow angle — shortest-arc rotation   prevDir → targetDir
 *   • Row alpha   — brief dip to 65% at mid-animation then back to 100%,
 *                   creating a "refresh flash" that signals data has changed
 *
 * No external animation library required — pure android.animation.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * Colour scale  (identical to WindEffectShape.speedColor())
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *   0– 2 m/s  grey    #969696   Calm
 *   2– 5 m/s  sky     #64C8FF   Light
 *   5–10 m/s  green   #00DC00   Moderate
 *  10–15 m/s  amber   #FFD700   Fresh
 *  15–20 m/s  orange  #FF6400   Strong
 *  20–28 m/s  red     #FF0000   Near-gale
 *    >28 m/s  magenta #B400B4   Storm
 */
public class WindChartView extends View {

    // ── Layout constants ──────────────────────────────────────────────────────
    private static final float ROW_HEIGHT_DP  = 58f;
    private static final float MAX_SPEED_SCALE = 30f;   // m/s → full bar width
    private static final int   ANIM_MS        = 220;
    private static final float LEGEND_HEIGHT_DP = 24f;

    // Altitude tiers — replaced at runtime via setAltitudes() when the data source changes.
    // Open-Meteo default: 10/80/120/180 m.  METAR/AWC: 10/760/1500/3000/4200 m.
    private int[]    altM     = {10, 80, 120, 180};
    private String[] altLabel = {" 10m", " 80m", "120m", "180m"};
    private int      altCount = 4;

    // Per-row source type tracking (Sprint 9)
    private boolean[] isPressureRow = new boolean[altCount];

    // ── Data ──────────────────────────────────────────────────────────────────
    private List<WindProfileModel> profiles;
    private int   currentHour = 0;
    private boolean hasPressureData = false;

    /** Altitude labels that are currently hidden (e.g. "80m", "925 hPa"). */
    private java.util.Set<String> hiddenAltitudes = java.util.Collections.emptySet();

    // Per-row animated state (re-allocated when altCount changes via setAltitudes)
    private float[] animSpeed  = new float[altCount];
    private float[] animDir    = new float[altCount];
    private float[] fromSpeed  = new float[altCount];
    private float[] fromDir    = new float[altCount];
    private float[] toSpeed    = new float[altCount];
    private float[] toDir      = new float[altCount];
    private float[] maxSpeed   = new float[altCount]; // max over entire dataset

    // Flash alpha: 1.0 normally, dips to 0.65 at mid-animation
    private float flashAlpha  = 1.0f;
    private ValueAnimator animator;

    // ── Paints ────────────────────────────────────────────────────────────────
    private final Paint labelPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint divPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barBgPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashedDivPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint legendPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint legendLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint legendDashedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public WindChartView(Context context) {
        super(context);
        float dp = context.getResources().getDisplayMetrics().density;

        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextSize(11f * dp);
        labelPaint.setTypeface(Typeface.MONOSPACE);

        valuePaint.setColor(Color.parseColor("#CCCCCC"));
        valuePaint.setTextSize(10f * dp);
        valuePaint.setTypeface(Typeface.MONOSPACE);

        divPaint.setColor(Color.parseColor("#44FFFFFF"));
        divPaint.setStrokeWidth(1f);

        dashedDivPaint.setColor(Color.parseColor("#44FFFFFF"));
        dashedDivPaint.setStrokeWidth(1f);
        dashedDivPaint.setPathEffect(new DashPathEffect(new float[]{6 * dp, 4 * dp}, 0));

        barBgPaint.setColor(Color.parseColor("#22FFFFFF"));
        barBgPaint.setStyle(Paint.Style.FILL);

        barFillPaint.setStyle(Paint.Style.FILL);  // shader set per row in onDraw

        arrowPaint.setStrokeCap(Paint.Cap.ROUND);
        arrowPaint.setStyle(Paint.Style.STROKE);
        arrowPaint.setAntiAlias(true);

        tickPaint.setColor(Color.parseColor("#66FFFFFF"));
        tickPaint.setStrokeWidth(1.5f * dp);

        legendPaint.setColor(Color.parseColor("#AAFFFFFF"));
        legendPaint.setTextSize(9f * dp);
        legendPaint.setTypeface(Typeface.MONOSPACE);

        legendLinePaint.setColor(Color.parseColor("#AAFFFFFF"));
        legendLinePaint.setStrokeWidth(2f * dp);
        legendLinePaint.setStrokeCap(Paint.Cap.ROUND);

        legendDashedPaint.setColor(Color.parseColor("#AAFFFFFF"));
        legendDashedPaint.setStrokeWidth(2f * dp);
        legendDashedPaint.setStrokeCap(Paint.Cap.ROUND);
        legendDashedPaint.setPathEffect(new DashPathEffect(new float[]{4 * dp, 3 * dp}, 0));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════════

    /** Bind a new dataset. Snaps to hour 0 without animation. */
    /**
     * Override the altitude tier configuration for this chart.
     *
     * Call this BEFORE {@link #setProfiles(List)} whenever the data source changes:
     * <ul>
     *   <li>Open-Meteo / DWD / ECMWF: {@code {10, 80, 120, 180}} m</li>
     *   <li>METAR / AWC pressure-level: {@code {10, 760, 1500, 3000, 4200}} m</li>
     * </ul>
     *
     * The method re-allocates all per-row animation arrays and resets animation state.
     * If {@code altitudesM} is null or empty the default (Open-Meteo) tiers are restored.
     *
     * @param altitudesM   altitude values in metres, sorted ascending
     * @param labels       display labels (same length as altitudesM); pass null to auto-generate
     */
    public void setAltitudes(int[] altitudesM, String[] labels) {
        if (altitudesM == null || altitudesM.length == 0) {
            altM     = new int[]    {10, 80, 120, 180};
            altLabel = new String[] {" 10m", " 80m", "120m", "180m"};
        } else {
            altM = altitudesM.clone();
            if (labels != null && labels.length == altitudesM.length) {
                altLabel = labels.clone();
            } else {
                // Auto-generate labels using unit-aware formatter
                altLabel = new String[altitudesM.length];
                for (int i = 0; i < altitudesM.length; i++) {
                    altLabel[i] = WeatherUnitConverter.fmtAltitude(altitudesM[i]);
                }
            }
        }
        altCount = altM.length;
        // Re-allocate per-row animation arrays
        animSpeed     = new float[altCount];
        animDir       = new float[altCount];
        fromSpeed     = new float[altCount];
        fromDir       = new float[altCount];
        toSpeed       = new float[altCount];
        toDir         = new float[altCount];
        maxSpeed      = new float[altCount];
        isPressureRow = new boolean[altCount];
        // Reset view height
        requestLayout();
        invalidate();
    }

    /**
     * Auto-detect altitude tiers from the first frame of a profile list and call
     * {@link #setAltitudes(int[], String[])} accordingly.  Safe to call with null/empty input.
     */
    public void setAltitudesFromProfiles(List<WindProfileModel> profiles) {
        if (profiles == null || profiles.isEmpty()) return;
        WindProfileModel first = profiles.get(0);
        if (first.getAltitudes() == null || first.getAltitudes().isEmpty()) return;
        int n = first.getAltitudes().size();
        int[] alts = new int[n];
        String[] labels = new String[n];
        for (int i = 0; i < n; i++) {
            WindProfileModel.AltitudeEntry entry = first.getAltitudes().get(i);
            alts[i] = entry.altitudeMeters;
            // Build label: use pressure label for pressure entries
            if (entry.isPressureLevel() && entry.pressureHPa != null) {
                labels[i] = entry.pressureHPa + "hPa";
            } else {
                labels[i] = WeatherUnitConverter.fmtAltitude(entry.altitudeMeters);
            }
        }
        setAltitudes(alts, labels);
        // Track pressure rows
        for (int i = 0; i < n; i++) {
            isPressureRow[i] = first.getAltitudes().get(i).isPressureLevel();
        }
        hasPressureData = first.hasPressureData();
    }

    /** Source label shown in the top-right corner of the chart (e.g. "AWC METAR"). */
    private String sourceLabel = null;
    private final Paint sourceLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public void setSourceLabel(String label) {
        this.sourceLabel = label;
        invalidate();
    }

    /**
     * Set which altitude labels should be hidden.
     * Hidden rows are drawn dimmed (alpha 0.15) so the user can still see them.
     */
    public void setHiddenAltitudes(java.util.Set<String> hidden) {
        this.hiddenAltitudes = hidden != null ? hidden : java.util.Collections.emptySet();
        invalidate();
    }

    /** @return true if the altitude row at the given index is hidden. */
    boolean isRowHidden(int rowIndex) {
        if (hiddenAltitudes.isEmpty() || rowIndex < 0 || rowIndex >= altCount) return false;
        String label = altLabel[rowIndex].trim();
        return hiddenAltitudes.contains(label);
    }

    public void setProfiles(List<WindProfileModel> profiles) {
        this.profiles     = profiles;
        this.currentHour  = 0;
        computeMaxSpeeds();
        snapToHour(0);
        invalidate();
    }

    /**
     * Animate to a new hour.  Called by WindProfileView when the seekbar moves.
     * A no-op if the hour has not actually changed.
     */
    public void setSelectedHour(int hour) {
        if (profiles == null || profiles.isEmpty()) return;
        int next = Math.min(hour, profiles.size() - 1);
        if (next == currentHour) return;

        // Capture from/to values per row
        WindProfileModel prevFrame = getFrame(currentHour);
        WindProfileModel nextFrame = getFrame(next);
        for (int r = 0; r < altCount; r++) {
            WindProfileModel.AltitudeEntry pe = findAlt(prevFrame, altM[r]);
            WindProfileModel.AltitudeEntry ne = findAlt(nextFrame, altM[r]);
            fromSpeed[r] = (pe != null) ? (float) pe.windSpeed     : animSpeed[r];
            fromDir  [r] = (pe != null) ? (float) pe.windDirection : animDir  [r];
            toSpeed  [r] = (ne != null) ? (float) ne.windSpeed     : fromSpeed[r];
            toDir    [r] = (ne != null) ? (float) ne.windDirection : fromDir  [r];
        }

        currentHour = next;
        startAnim();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        float dp = getContext().getResources().getDisplayMetrics().density;
        float legendExtra = hasPressureData ? LEGEND_HEIGHT_DP * dp : 0;
        int   h  = (int)(ROW_HEIGHT_DP * altCount * dp + legendExtra);
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), resolveSize(h, heightSpec));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Drawing
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (profiles == null || profiles.isEmpty()) return;

        float dp   = getContext().getResources().getDisplayMetrics().density;
        float rowH = ROW_HEIGHT_DP * dp;
        float w    = getWidth();

        // Fixed column positions
        float labelW  = 48f * dp;             // altitude text (wider for pressure labels)
        float barL    = labelW + 6f * dp;     // speed bar left edge
        float barR    = w * 0.58f;            // speed bar right edge
        float barW    = barR - barL;
        float arrowCx = w * 0.70f;            // arrow centre x
        float valX    = w * 0.78f;            // numeric readout x

        WindProfileModel liveFrame = getFrame(currentHour);

        for (int r = 0; r < altCount; r++) {
            // Draw highest altitude at top, lowest at bottom
            int drawRow = (altCount - 1) - r;
            float top = drawRow * rowH;
            float mid = top + rowH * 0.52f;
            float barH   = rowH * 0.36f;
            float barTop = mid - barH / 2f;
            boolean pressure = isPressureRow[r];
            boolean rowHidden = isRowHidden(r);

            // ── Dim hidden rows ─────────────────────────────────────────
            if (rowHidden) {
                canvas.save();
                canvas.saveLayerAlpha(0, top, w, top + rowH, 38); // ~15% opacity
            }

            // ── Row divider (between visual rows, not at the very top) ──
            if (drawRow > 0) {
                Paint divP = pressure ? dashedDivPaint : divPaint;
                canvas.drawLine(0, top, w, top, divP);
            }

            float spd = animSpeed[r];
            float dir = animDir  [r];
            int   col = speedColor(spd);

            int rowAlpha = (int)(flashAlpha * 255);

            // ── Altitude label ───────────────────────────────────────────
            labelPaint.setAlpha(rowAlpha);
            if (pressure) {
                labelPaint.setColor(Color.parseColor("#BBDDFF"));
            } else {
                labelPaint.setColor(Color.WHITE);
            }
            canvas.drawText(altLabel[r], 4f * dp, mid + 4f * dp, labelPaint);
            labelPaint.setColor(Color.WHITE);  // restore

            // ── Speed bar background ─────────────────────────────────────
            barBgPaint.setAlpha((int)(0.13f * 255));
            canvas.drawRoundRect(new RectF(barL, barTop, barR, barTop + barH),
                    3f * dp, 3f * dp, barBgPaint);

            // ── Speed bar fill (gradient: dark → vivid) ──────────────────
            float fillFrac = Math.min(spd / MAX_SPEED_SCALE, 1.0f);
            float fillW    = barW * fillFrac;
            if (fillW > 2f) {
                int colDark = scaleColor(col, 0.35f);
                barFillPaint.setShader(new LinearGradient(
                        barL, 0, barL + fillW, 0,
                        setAlphaOn(colDark, rowAlpha),
                        setAlphaOn(col,     rowAlpha),
                        Shader.TileMode.CLAMP));
                if (pressure) {
                    // Dashed-style fill for pressure rows: slightly transparent
                    barFillPaint.setAlpha((int)(rowAlpha * 0.7f));
                }
                canvas.drawRoundRect(new RectF(barL, barTop, barL + fillW, barTop + barH),
                        3f * dp, 3f * dp, barFillPaint);
                barFillPaint.setAlpha(255); // restore
            }

            // ── Max-speed reference tick ─────────────────────────────────
            float maxFrac = Math.min(maxSpeed[r] / MAX_SPEED_SCALE, 1.0f);
            float maxX    = barL + barW * maxFrac;
            tickPaint.setAlpha(rowAlpha / 2);
            canvas.drawLine(maxX, barTop - 2f * dp, maxX, barTop + barH + 2f * dp, tickPaint);

            // ── Speed label inside bar ────────────────────────────────────
            String spdTxt = WeatherUnitConverter.fmtWind(spd);
            float  spdTw  = valuePaint.measureText(spdTxt);
            float  spdX   = barL + fillW - spdTw - 4f * dp;
            if (spdX > barL + 2f * dp) {
                valuePaint.setAlpha(rowAlpha);
                canvas.drawText(spdTxt, spdX, barTop + barH - 3f * dp, valuePaint);
            }

            // ── Directional arrow ────────────────────────────────────────
            float arrowLen = rowH * 0.38f;
            float thickness = dp * (1.5f + fillFrac * 2.0f); // thicker when faster
            arrowPaint.setColor(setAlphaOn(col, rowAlpha));
            arrowPaint.setStrokeWidth(thickness);
            if (pressure) {
                arrowPaint.setPathEffect(new DashPathEffect(new float[]{3 * dp, 2 * dp}, 0));
            } else {
                arrowPaint.setPathEffect(null);
            }
            drawArrow(canvas, arrowCx, mid, arrowLen, dir, dp);
            arrowPaint.setPathEffect(null); // restore

            // ── Dot indicator ─────────────────────────────────────────────
            Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            dotPaint.setColor(setAlphaOn(col, rowAlpha));
            float dotCx = barL - 8f * dp;
            if (pressure) {
                dotPaint.setStyle(Paint.Style.STROKE);
                dotPaint.setStrokeWidth(1.5f * dp);
                canvas.drawCircle(dotCx, mid, 3f * dp, dotPaint);
            } else {
                dotPaint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(dotCx, mid, 3f * dp, dotPaint);
            }

            // ── Numeric readout ──────────────────────────────────────────
            WindProfileModel.AltitudeEntry live = findAlt(liveFrame, altM[r]);
            if (live != null) {
                valuePaint.setAlpha(rowAlpha);
                String dirTemp = String.format(Locale.US, "%.0f\u00b0", live.windDirection);
                if (!Double.isNaN(live.temperature)) {
                    dirTemp += " " + WeatherUnitConverter.fmtTemp(live.temperature);
                }
                canvas.drawText(dirTemp, valX, mid, valuePaint);
                if (live.altitudeMeters <= 10 && live.windGusts > 0) {
                    canvas.drawText(
                            "G" + WeatherUnitConverter.fmtWind(live.windGusts),
                            valX, mid + 14f * dp, valuePaint);
                }
                // Show pressure level for pressure rows
                if (pressure && live.pressureHPa != null) {
                    valuePaint.setAlpha((int)(rowAlpha * 0.6f));
                    canvas.drawText(
                            WeatherUnitConverter.fmtAltitude(live.altitudeMeters),
                            valX, mid + 14f * dp, valuePaint);
                }
            }

            // Restore dimming for hidden rows
            if (rowHidden) {
                canvas.restore(); // saveLayerAlpha
                canvas.restore(); // save
            }
        }

        // ── Legend (only when pressure data present) ─────────────────────
        if (hasPressureData) {
            float legendY = altCount * rowH + 4f * dp;
            float legendMid = legendY + LEGEND_HEIGHT_DP * dp * 0.5f;
            float xOff = 8f * dp;

            // Solid line + "Surface"
            canvas.drawLine(xOff, legendMid, xOff + 20f * dp, legendMid, legendLinePaint);
            Paint solidDot = new Paint(Paint.ANTI_ALIAS_FLAG);
            solidDot.setColor(Color.parseColor("#AAFFFFFF"));
            solidDot.setStyle(Paint.Style.FILL);
            canvas.drawCircle(xOff + 10f * dp, legendMid, 2.5f * dp, solidDot);
            canvas.drawText("Surface", xOff + 24f * dp, legendMid + 4f * dp, legendPaint);

            // Dashed line + "Pressure Level"
            float xOff2 = xOff + 100f * dp;
            canvas.drawLine(xOff2, legendMid, xOff2 + 20f * dp, legendMid, legendDashedPaint);
            Paint hollowDot = new Paint(Paint.ANTI_ALIAS_FLAG);
            hollowDot.setColor(Color.parseColor("#AAFFFFFF"));
            hollowDot.setStyle(Paint.Style.STROKE);
            hollowDot.setStrokeWidth(1.5f * dp);
            canvas.drawCircle(xOff2 + 10f * dp, legendMid, 2.5f * dp, hollowDot);
            canvas.drawText("Pressure Level", xOff2 + 24f * dp, legendMid + 4f * dp, legendPaint);
        }

        // ── Source label (top-right corner) ───────────────────────────────
        if (sourceLabel != null && !sourceLabel.isEmpty()) {
            float dp2 = getContext().getResources().getDisplayMetrics().density;
            sourceLabelPaint.setColor(0xAAFFFFFF);
            sourceLabelPaint.setTextSize(10f * dp2);
            sourceLabelPaint.setTextAlign(Paint.Align.RIGHT);
            sourceLabelPaint.setTypeface(Typeface.MONOSPACE);
            canvas.drawText(sourceLabel, getWidth() - 6f * dp2, 14f * dp2, sourceLabelPaint);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Arrow drawing
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Arrow points DOWNWIND (dirDeg + 180°).
     * Tail at upwind side, tip with arrowhead at downwind side.
     */
    private void drawArrow(Canvas canvas, float cx, float cy,
                           float len, float dirDeg, float dp) {
        double toRad = Math.toRadians(dirDeg + 180.0);
        float dx = (float) Math.sin(toRad);
        float dy = (float)-Math.cos(toRad);

        // Shaft
        float x1 = cx - dx * len * 0.5f, y1 = cy - dy * len * 0.5f; // tail (upwind)
        float x2 = cx + dx * len * 0.5f, y2 = cy + dy * len * 0.5f; // tip  (downwind)
        canvas.drawLine(x1, y1, x2, y2, arrowPaint);

        // Arrowhead at tip
        float ah = 7f * dp;
        Path head = new Path();
        double lRad = Math.toRadians(dirDeg + 180.0 + 145.0);
        double rRad = Math.toRadians(dirDeg + 180.0 - 145.0);
        head.moveTo(x2, y2);
        head.lineTo(x2 + (float) Math.sin(lRad) * ah,
                y2 - (float) Math.cos(lRad) * ah);
        head.moveTo(x2, y2);
        head.lineTo(x2 + (float) Math.sin(rRad) * ah,
                y2 - (float) Math.cos(rRad) * ah);
        canvas.drawPath(head, arrowPaint);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Animation
    // ══════════════════════════════════════════════════════════════════════════

    private void startAnim() {
        if (animator != null && animator.isRunning()) animator.cancel();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(ANIM_MS);
        animator.setInterpolator(new DecelerateInterpolator());

        animator.addUpdateListener(anim -> {
            float f = (float) anim.getAnimatedValue();

            // Flash: dip to 0.65 at midpoint, recover to 1.0
            flashAlpha = f < 0.5f
                    ? 1.0f - 0.35f * (f / 0.5f)
                    : 0.65f + 0.35f * ((f - 0.5f) / 0.5f);

            for (int r = 0; r < altCount; r++) {
                animSpeed[r] = fromSpeed[r] + f * (toSpeed[r] - fromSpeed[r]);

                // Shortest-arc direction interpolation
                float delta = toDir[r] - fromDir[r];
                if (delta >  180f) delta -= 360f;
                if (delta < -180f) delta += 360f;
                animDir[r] = fromDir[r] + f * delta;
            }
            invalidate();
        });

        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                // Snap to exact targets to avoid floating-point drift
                for (int r = 0; r < altCount; r++) {
                    animSpeed[r] = toSpeed[r];
                    animDir  [r] = toDir  [r];
                }
                flashAlpha = 1.0f;
                invalidate();
            }
        });

        animator.start();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private void snapToHour(int hour) {
        WindProfileModel frame = getFrame(hour);
        for (int r = 0; r < altCount; r++) {
            WindProfileModel.AltitudeEntry e = findAlt(frame, altM[r]);
            float s = (e != null) ? (float) e.windSpeed     : 0f;
            float d = (e != null) ? (float) e.windDirection : 0f;
            animSpeed[r] = fromSpeed[r] = toSpeed[r] = s;
            animDir  [r] = fromDir  [r] = toDir  [r] = d;
        }
    }

    private void computeMaxSpeeds() {
        for (int r = 0; r < altCount; r++) maxSpeed[r] = 0f;
        if (profiles == null) return;
        for (WindProfileModel frame : profiles)
            for (int r = 0; r < altCount; r++) {
                WindProfileModel.AltitudeEntry e = findAlt(frame, altM[r]);
                if (e != null && e.windSpeed > maxSpeed[r])
                    maxSpeed[r] = (float) e.windSpeed;
            }
    }

    private WindProfileModel getFrame(int hour) {
        if (profiles == null || profiles.isEmpty()) return null;
        return profiles.get(Math.max(0, Math.min(hour, profiles.size() - 1)));
    }

    private static WindProfileModel.AltitudeEntry findAlt(WindProfileModel frame, int altM) {
        if (frame == null) return null;
        for (WindProfileModel.AltitudeEntry e : frame.getAltitudes())
            if (e.altitudeMeters == altM) return e;
        return null;
    }

    // ── Colour utilities ──────────────────────────────────────────────────────

    /** Same palette as WindEffectShape — must stay in sync. */
    static int speedColor(double ms) {
        if (ms <  2) return Color.parseColor("#969696");
        if (ms <  5) return Color.parseColor("#64C8FF");
        if (ms < 10) return Color.parseColor("#00DC00");
        if (ms < 15) return Color.parseColor("#FFD700");
        if (ms < 20) return Color.parseColor("#FF6400");
        if (ms < 28) return Color.parseColor("#FF0000");
        return               Color.parseColor("#B400B4");
    }

    /** Return colour with each RGB channel scaled by factor (darkens for gradient start). */
    private static int scaleColor(int color, float factor) {
        return Color.rgb(
                (int)(Color.red  (color) * factor),
                (int)(Color.green(color) * factor),
                (int)(Color.blue (color) * factor));
    }

    /** Return colour with alpha replaced (does not affect RGB). */
    private static int setAlphaOn(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
