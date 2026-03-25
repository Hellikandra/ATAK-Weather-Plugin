package com.atakmap.android.weather.presentation.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import com.atakmap.android.weather.overlay.heatmap.ColourScale;

import java.util.Locale;

/**
 * Horizontal color legend bar for heatmap overlays.
 *
 * <p>Draws a gradient bar from the parameter's colour scale with tick labels
 * at key breakpoints (min, mid, max). Similar to the Elevation Tool legend.</p>
 */
public class HeatmapLegendView extends View {

    private final Paint gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);

    private String paramKey  = "temperature_2m";
    private String paramUnit = "\u00B0C";
    private double minVal    = -20;
    private double maxVal    = 40;
    private int[]  colors    = {};

    public HeatmapLegendView(Context context) {
        super(context);
        float dp = context.getResources().getDisplayMetrics().density;

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setColor(Color.parseColor("#55FFFFFF"));
        borderPaint.setStrokeWidth(1f);

        labelPaint.setColor(Color.parseColor("#CCFFFFFF"));
        labelPaint.setTextSize(9f * dp);
        labelPaint.setTypeface(Typeface.MONOSPACE);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        applyParameter("temperature_2m");
    }

    /**
     * Set which parameter the legend displays. Updates colors, range, and unit.
     */
    public void applyParameter(String paramKey) {
        this.paramKey = paramKey;
        switch (paramKey) {
            case "temperature_2m":
                minVal = -20; maxVal = 40; paramUnit = "\u00B0C";
                colors = new int[]{
                    Color.parseColor("#2196F3"),  // blue
                    Color.parseColor("#00BCD4"),  // cyan
                    Color.parseColor("#4CAF50"),  // green
                    Color.parseColor("#FFEB3B"),  // yellow
                    Color.parseColor("#F44336"),  // red
                };
                break;
            case "wind_speed_10m":
                minVal = 0; maxVal = 25; paramUnit = "m/s";
                colors = new int[]{
                    Color.parseColor("#81C784"),  // green
                    Color.parseColor("#FFF176"),  // yellow
                    Color.parseColor("#FFB74D"),  // orange
                    Color.parseColor("#E57373"),  // red
                    Color.parseColor("#CE93D8"),  // magenta
                };
                break;
            case "relative_humidity_2m":
                minVal = 0; maxVal = 100; paramUnit = "%";
                colors = new int[]{
                    Color.parseColor("#FFEB3B"),  // yellow (dry)
                    Color.parseColor("#4CAF50"),  // green (moderate)
                    Color.parseColor("#2196F3"),  // blue (humid)
                };
                break;
            case "surface_pressure":
                minVal = 970; maxVal = 1040; paramUnit = "hPa";
                colors = new int[]{
                    Color.parseColor("#CE93D8"),  // purple (low)
                    Color.parseColor("#4CAF50"),  // green (normal)
                    Color.parseColor("#FF9800"),  // orange (high)
                };
                break;
            default:
                minVal = 0; maxVal = 100; paramUnit = "";
                colors = new int[]{ Color.BLUE, Color.GREEN, Color.RED };
                break;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (colors.length < 2) return;

        float dp = getContext().getResources().getDisplayMetrics().density;
        float w  = getWidth();
        float h  = getHeight();

        float barLeft   = 20f * dp;
        float barRight  = w - 20f * dp;
        float barTop    = 4f * dp;
        float barBottom = h - 16f * dp;
        float barHeight = barBottom - barTop;

        // Draw gradient bar
        LinearGradient gradient = new LinearGradient(
                barLeft, 0, barRight, 0, colors, null, Shader.TileMode.CLAMP);
        gradientPaint.setShader(gradient);
        RectF barRect = new RectF(barLeft, barTop, barRight, barBottom);
        canvas.drawRoundRect(barRect, 3f * dp, 3f * dp, gradientPaint);
        canvas.drawRoundRect(barRect, 3f * dp, 3f * dp, borderPaint);

        // Draw tick labels
        float labelY = h - 2f * dp;
        float barW = barRight - barLeft;

        // Min
        labelPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(formatVal(minVal), barLeft, labelY, labelPaint);

        // Max
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(formatVal(maxVal) + " " + paramUnit, barRight, labelY, labelPaint);

        // Mid
        double midVal = (minVal + maxVal) / 2.0;
        labelPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(formatVal(midVal), barLeft + barW / 2f, labelY, labelPaint);
    }

    private String formatVal(double v) {
        if (Math.abs(v) >= 100) return String.format(Locale.US, "%.0f", v);
        return String.format(Locale.US, "%.0f", v);
    }
}
