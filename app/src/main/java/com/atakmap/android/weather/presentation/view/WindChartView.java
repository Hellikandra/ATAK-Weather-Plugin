package com.atakmap.android.weather.presentation.view;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.atakmap.android.weather.domain.model.WindProfileModel;

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
    private static final int   ALTITUDES      = 4;
    private static final float MAX_SPEED_SCALE = 30f;   // m/s → full bar width
    private static final int   ANIM_MS        = 220;

    // Altitude display order: highest row at top
    private static final int[]    ALT_M     = {180, 120, 80, 10};
    private static final String[] ALT_LABEL = {"180m", "120m", " 80m", " 10m"};

    // ── Data ──────────────────────────────────────────────────────────────────
    private List<WindProfileModel> profiles;
    private int   currentHour = 0;

    // Per-row animated state
    private final float[] animSpeed  = new float[ALTITUDES];
    private final float[] animDir    = new float[ALTITUDES];
    private final float[] fromSpeed  = new float[ALTITUDES];
    private final float[] fromDir    = new float[ALTITUDES];
    private final float[] toSpeed    = new float[ALTITUDES];
    private final float[] toDir      = new float[ALTITUDES];
    private final float[] maxSpeed   = new float[ALTITUDES]; // max over entire dataset

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

    public WindChartView(Context context) {
        super(context);
        float dp = context.getResources().getDisplayMetrics().density;

        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextSize(11f * dp);
        labelPaint.setTypeface(android.graphics.Typeface.MONOSPACE);

        valuePaint.setColor(Color.parseColor("#CCCCCC"));
        valuePaint.setTextSize(10f * dp);
        valuePaint.setTypeface(android.graphics.Typeface.MONOSPACE);

        divPaint.setColor(Color.parseColor("#44FFFFFF"));
        divPaint.setStrokeWidth(1f);

        barBgPaint.setColor(Color.parseColor("#22FFFFFF"));
        barBgPaint.setStyle(Paint.Style.FILL);

        barFillPaint.setStyle(Paint.Style.FILL);  // shader set per row in onDraw

        arrowPaint.setStrokeCap(Paint.Cap.ROUND);
        arrowPaint.setStyle(Paint.Style.STROKE);
        arrowPaint.setAntiAlias(true);

        tickPaint.setColor(Color.parseColor("#66FFFFFF"));
        tickPaint.setStrokeWidth(1.5f * dp);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════════

    /** Bind a new dataset. Snaps to hour 0 without animation. */
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
        for (int r = 0; r < ALTITUDES; r++) {
            WindProfileModel.AltitudeEntry pe = findAlt(prevFrame, ALT_M[r]);
            WindProfileModel.AltitudeEntry ne = findAlt(nextFrame, ALT_M[r]);
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
        int   h  = (int)(ROW_HEIGHT_DP * ALTITUDES * dp);
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
        float labelW  = 40f * dp;             // altitude text
        float barL    = labelW + 6f * dp;     // speed bar left edge
        float barR    = w * 0.60f;            // speed bar right edge
        float barW    = barR - barL;
        float arrowCx = w * 0.72f;            // arrow centre x
        float valX    = w * 0.79f;            // numeric readout x

        WindProfileModel liveFrame = getFrame(currentHour);

        for (int r = 0; r < ALTITUDES; r++) {
            float top = r * rowH;
            float mid = top + rowH * 0.52f;
            float barH   = rowH * 0.36f;
            float barTop = mid - barH / 2f;

            // ── Row divider ──────────────────────────────────────────────
            if (r > 0) canvas.drawLine(0, top, w, top, divPaint);

            float spd = animSpeed[r];
            float dir = animDir  [r];
            int   col = speedColor(spd);

            int rowAlpha = (int)(flashAlpha * 255);

            // ── Altitude label ───────────────────────────────────────────
            labelPaint.setAlpha(rowAlpha);
            canvas.drawText(ALT_LABEL[r], 4f * dp, mid + 4f * dp, labelPaint);

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
                canvas.drawRoundRect(new RectF(barL, barTop, barL + fillW, barTop + barH),
                        3f * dp, 3f * dp, barFillPaint);
            }

            // ── Max-speed reference tick ─────────────────────────────────
            float maxFrac = Math.min(maxSpeed[r] / MAX_SPEED_SCALE, 1.0f);
            float maxX    = barL + barW * maxFrac;
            tickPaint.setAlpha(rowAlpha / 2);
            canvas.drawLine(maxX, barTop - 2f * dp, maxX, barTop + barH + 2f * dp, tickPaint);

            // ── Speed label inside bar ────────────────────────────────────
            String spdTxt = String.format(Locale.US, "%.1fm/s", spd);
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
            drawArrow(canvas, arrowCx, mid, arrowLen, dir, dp);

            // ── Numeric readout ──────────────────────────────────────────
            WindProfileModel.AltitudeEntry live = findAlt(liveFrame, ALT_M[r]);
            if (live != null) {
                valuePaint.setAlpha(rowAlpha);
                canvas.drawText(
                        String.format(Locale.US, "%.0f\u00b0 %.1f\u00b0C",
                                live.windDirection, live.temperature),
                        valX, mid, valuePaint);
                if (live.altitudeMeters == 10 && live.windGusts > 0)
                    canvas.drawText(
                            String.format(Locale.US, "G%.1f", live.windGusts),
                            valX, mid + 14f * dp, valuePaint);
            }
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

            for (int r = 0; r < ALTITUDES; r++) {
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
                for (int r = 0; r < ALTITUDES; r++) {
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
        for (int r = 0; r < ALTITUDES; r++) {
            WindProfileModel.AltitudeEntry e = findAlt(frame, ALT_M[r]);
            float s = (e != null) ? (float) e.windSpeed     : 0f;
            float d = (e != null) ? (float) e.windDirection : 0f;
            animSpeed[r] = fromSpeed[r] = toSpeed[r] = s;
            animDir  [r] = fromDir  [r] = toDir  [r] = d;
        }
    }

    private void computeMaxSpeeds() {
        for (int r = 0; r < ALTITUDES; r++) maxSpeed[r] = 0f;
        if (profiles == null) return;
        for (WindProfileModel frame : profiles)
            for (int r = 0; r < ALTITUDES; r++) {
                WindProfileModel.AltitudeEntry e = findAlt(frame, ALT_M[r]);
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
