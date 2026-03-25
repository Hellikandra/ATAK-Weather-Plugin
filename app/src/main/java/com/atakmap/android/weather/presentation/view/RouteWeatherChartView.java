package com.atakmap.android.weather.presentation.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Simple chart showing weather conditions along a route.
 * Displays wind speed (line) and precipitation % (filled area) per waypoint.
 */
public class RouteWeatherChartView extends View {

    private final Paint windPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint precipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint precipFill  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path  windPath    = new Path();
    private final Path  precipPath  = new Path();

    private final List<WaypointData> data = new ArrayList<>();

    public static class WaypointData {
        public final int index;
        public final double windSpeed;     // m/s
        public final double windDir;       // degrees
        public final double humidity;      // %
        public final double tempMax;       // C
        public final String wmoLabel;

        public WaypointData(int index, double windSpeed, double windDir,
                            double humidity, double tempMax, String wmoLabel) {
            this.index = index;
            this.windSpeed = windSpeed;
            this.windDir = windDir;
            this.humidity = humidity;
            this.tempMax = tempMax;
            this.wmoLabel = wmoLabel;
        }
    }

    public RouteWeatherChartView(Context context) {
        super(context);
        float dp = context.getResources().getDisplayMetrics().density;

        windPaint.setColor(Color.parseColor("#FFB74D")); // orange
        windPaint.setStrokeWidth(2.5f * dp);
        windPaint.setStyle(Paint.Style.STROKE);

        precipPaint.setColor(Color.parseColor("#4FC3F7")); // blue
        precipPaint.setStrokeWidth(1.5f * dp);
        precipPaint.setStyle(Paint.Style.STROKE);

        precipFill.setColor(Color.parseColor("#334FC3F7")); // blue transparent
        precipFill.setStyle(Paint.Style.FILL);

        gridPaint.setColor(Color.parseColor("#22FFFFFF"));
        gridPaint.setStrokeWidth(1f);

        labelPaint.setColor(Color.parseColor("#AAFFFFFF"));
        labelPaint.setTextSize(8f * dp);
        labelPaint.setTypeface(Typeface.MONOSPACE);

        axisPaint.setColor(Color.parseColor("#55FFFFFF"));
        axisPaint.setStrokeWidth(1f);
    }

    public void setData(List<WaypointData> waypoints) {
        data.clear();
        if (waypoints != null) data.addAll(waypoints);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (data.isEmpty()) {
            labelPaint.setTextAlign(Paint.Align.CENTER);
            labelPaint.setTextSize(11f * getContext().getResources().getDisplayMetrics().density);
            canvas.drawText("No route data", getWidth() / 2f, getHeight() / 2f, labelPaint);
            return;
        }

        float dp = getContext().getResources().getDisplayMetrics().density;
        float w = getWidth();
        float h = getHeight();

        float leftMargin  = 30f * dp;
        float rightMargin = 30f * dp;
        float topMargin   = 10f * dp;
        float botMargin   = 18f * dp;

        float plotW = w - leftMargin - rightMargin;
        float plotH = h - topMargin - botMargin;

        // Find max values for scaling
        double maxWind = 1;
        double maxHumidity = 100;
        for (WaypointData wp : data) {
            if (wp.windSpeed > maxWind) maxWind = wp.windSpeed;
        }
        maxWind = Math.ceil(maxWind / 5) * 5; // round up to nearest 5

        int n = data.size();

        // Grid lines (horizontal)
        for (int i = 0; i <= 4; i++) {
            float y = topMargin + plotH * (1f - i / 4f);
            canvas.drawLine(leftMargin, y, w - rightMargin, y, gridPaint);
            // Left axis: wind speed
            float windVal = (float) (maxWind * i / 4.0);
            labelPaint.setTextAlign(Paint.Align.RIGHT);
            labelPaint.setColor(Color.parseColor("#FFB74D"));
            labelPaint.setTextSize(7f * dp);
            canvas.drawText(String.format(Locale.US, "%.0f", windVal),
                    leftMargin - 3f * dp, y + 3f * dp, labelPaint);
            // Right axis: humidity
            float humVal = (float) (maxHumidity * i / 4.0);
            labelPaint.setTextAlign(Paint.Align.LEFT);
            labelPaint.setColor(Color.parseColor("#4FC3F7"));
            canvas.drawText(String.format(Locale.US, "%.0f%%", humVal),
                    w - rightMargin + 3f * dp, y + 3f * dp, labelPaint);
        }

        // Plot wind speed line
        windPath.reset();
        precipPath.reset();

        for (int i = 0; i < n; i++) {
            float x = leftMargin + (plotW * i) / Math.max(1, n - 1);
            float yWind = topMargin + plotH * (1f - (float)(data.get(i).windSpeed / maxWind));
            float yHum  = topMargin + plotH * (1f - (float)(data.get(i).humidity / maxHumidity));

            if (i == 0) {
                windPath.moveTo(x, yWind);
                precipPath.moveTo(x, yHum);
            } else {
                windPath.lineTo(x, yWind);
                precipPath.lineTo(x, yHum);
            }

            // WP label at bottom
            if (n <= 20 || i % Math.max(1, n / 10) == 0) {
                labelPaint.setTextAlign(Paint.Align.CENTER);
                labelPaint.setColor(Color.parseColor("#88FFFFFF"));
                labelPaint.setTextSize(7f * dp);
                canvas.drawText("WP" + (i + 1), x, h - 2f * dp, labelPaint);
            }

            // Wind direction arrow at each point
            canvas.save();
            canvas.translate(x, yWind - 8f * dp);
            canvas.rotate((float) data.get(i).windDir);
            canvas.drawLine(0, -3f * dp, 0, 3f * dp, windPaint);
            canvas.drawLine(0, -3f * dp, -1.5f * dp, -1f * dp, windPaint);
            canvas.drawLine(0, -3f * dp, 1.5f * dp, -1f * dp, windPaint);
            canvas.restore();
        }

        // Draw humidity filled area
        Path fillPath = new Path(precipPath);
        float lastX = leftMargin + plotW;
        float baseY = topMargin + plotH;
        fillPath.lineTo(lastX, baseY);
        fillPath.lineTo(leftMargin, baseY);
        fillPath.close();
        canvas.drawPath(fillPath, precipFill);

        // Draw lines on top
        canvas.drawPath(windPath, windPaint);
        canvas.drawPath(precipPath, precipPaint);

        // Axis labels
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setColor(Color.parseColor("#FFB74D"));
        labelPaint.setTextSize(8f * dp);
        canvas.save();
        canvas.rotate(-90, 8f * dp, topMargin + plotH / 2f);
        canvas.drawText("Wind m/s", 8f * dp, topMargin + plotH / 2f, labelPaint);
        canvas.restore();

        labelPaint.setColor(Color.parseColor("#4FC3F7"));
        canvas.save();
        canvas.rotate(90, w - 8f * dp, topMargin + plotH / 2f);
        canvas.drawText("Humidity %", w - 8f * dp, topMargin + plotH / 2f, labelPaint);
        canvas.restore();
    }
}
