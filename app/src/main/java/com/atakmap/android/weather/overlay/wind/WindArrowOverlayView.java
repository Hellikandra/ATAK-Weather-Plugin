package com.atakmap.android.weather.overlay.wind;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.view.View;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.overlay.heatmap.ColourScale;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.List;

/**
 * Wind arrow vector field overlay — renders directional arrows on a geo-projected
 * grid across the visible map area, color-coded by wind speed.
 *
 * <p>Uses the same View + {@code mapView.forward(GeoPoint)} projection as
 * {@code HeatmapOverlayView} and {@code RadarOverlayView}. Arrows are placed at
 * geo-coordinates and projected to screen pixels on every draw, so they follow
 * the globe correctly in 3D mode.</p>
 *
 * <p>The current implementation uses a uniform wind field (single hourly entry
 * for all grid cells) because the hourly forecast is a single-point forecast.
 * For multi-point grid data, per-cell interpolation can be added later.</p>
 */
public class WindArrowOverlayView extends View {

    private static final String TAG = "WindArrowOverlay";

    private final MapView mapView;
    private final Paint arrowPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrowPath = new Path();

    // Thread-safe: data set from background callbacks, drawn on UI thread.
    private volatile List<HourlyEntryModel> hourlyData;
    private volatile int hourIndex = 0;
    private volatile float opacity = 0.85f;
    private volatile boolean visible = false;

    // Grid data — per-cell wind from heatmap dataset (volatile for thread safety)
    private volatile double[][] gridWindSpeed;    // [row][col]
    private volatile double[][] gridWindDir;      // [row][col]
    private volatile double gridNorth, gridSouth, gridWest, gridEast;
    private volatile int gridRows, gridCols;
    private volatile boolean hasGridData = false;

    /** Arrow drawing styles. */
    public enum ArrowStyle { ARROW, BARB, CHEVRON, DOT }

    // Grid config — mutable for user control
    private int gridCells = 8;
    private float arrowSizeDp = 18f;
    private boolean fillArrow = true;
    private ArrowStyle arrowStyle = ArrowStyle.ARROW;

    private final ColourScale windScale;

    // City labels (wind + other parameter values)
    private CityWindDatabase cityDb;
    private boolean showCityLabels = true;
    private final Paint cityLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cityBgPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Extra parameter grid for city value labels (temp, humidity, pressure)
    private double[][] cityParamGrid;
    private double cityParamNorth, cityParamSouth, cityParamWest, cityParamEast;
    private int cityParamRows, cityParamCols;
    private String cityParamKey = "";   // e.g. "temperature_2m"
    private String cityParamUnit = "";  // e.g. "°C"
    private boolean hasCityParamData = false;

    // Fix #22 audit — VSYNC-aligned redraw eliminates 1-frame drift.
    private final MapView.OnMapMovedListener mapMovedListener =
            (view, animate) -> postInvalidateOnAnimation();

    public WindArrowOverlayView(Context context, MapView mapView) {
        super(context);
        this.mapView = mapView;
        setWillNotDraw(false);
        setLayoutParams(new MapView.LayoutParams(
                MapView.LayoutParams.MATCH_PARENT,
                MapView.LayoutParams.MATCH_PARENT));

        arrowPaint.setStyle(Paint.Style.FILL);
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setColor(Color.BLACK);
        outlinePaint.setStrokeWidth(1.5f);

        windScale = ColourScale.forParameter("wind_speed_10m");

        // City label styling
        float dp = context.getResources().getDisplayMetrics().density;
        cityLabelPaint.setColor(Color.WHITE);
        cityLabelPaint.setTextSize(10f * dp);
        cityLabelPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        cityLabelPaint.setTextAlign(Paint.Align.CENTER);
        cityLabelPaint.setShadowLayer(2f * dp, 0, 0, Color.BLACK);

        cityBgPaint.setColor(Color.argb(140, 0, 0, 0));
        cityBgPaint.setStyle(Paint.Style.FILL);

        // Load city database
        cityDb = new CityWindDatabase();
        cityDb.load(context);
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    public void attach() {
        if (getParent() != null) return;
        mapView.addView(this);
        mapView.addOnMapMovedListener(mapMovedListener);
        setVisibility(visible ? VISIBLE : GONE);
    }

    public void detach() {
        mapView.removeOnMapMovedListener(mapMovedListener);
        if (getParent() != null) mapView.removeView(this);
    }

    public void setWindData(List<HourlyEntryModel> data) {
        this.hourlyData = data;
        postInvalidate();
    }

    public void setHourIndex(int index) {
        this.hourIndex = index;
        postInvalidate();
    }

    public void setArrowOpacity(float alpha) {
        this.opacity = alpha;
        postInvalidate();
    }

    public void setArrowsVisible(boolean v) {
        this.visible = v;
        // View stays VISIBLE if either arrows or city labels are active
        setVisibility((v || showCityLabels) ? VISIBLE : GONE);
        postInvalidate();
    }

    public boolean isArrowsVisible() { return visible; }

    /**
     * Set per-cell wind grid data from the heatmap dataset.
     * When set, arrows use per-cell direction/speed instead of uniform field.
     *
     * @param windSpeedGrid  [row][col] wind speed values
     * @param windDirGrid    [row][col] wind direction values
     * @param north          northern latitude bound of grid
     * @param south          southern latitude bound
     * @param west           western longitude bound
     * @param east           eastern longitude bound
     */
    public void setGridWindData(double[][] windSpeedGrid, double[][] windDirGrid,
                                 double north, double south,
                                 double west, double east) {
        this.gridWindSpeed = windSpeedGrid;
        this.gridWindDir   = windDirGrid;
        this.gridNorth     = north;
        this.gridSouth     = south;
        this.gridWest      = west;
        this.gridEast      = east;
        if (windSpeedGrid != null) {
            this.gridRows = windSpeedGrid.length;
            this.gridCols = gridRows > 0 ? windSpeedGrid[0].length : 0;
        }
        this.hasGridData = (windSpeedGrid != null && windDirGrid != null
                && gridRows > 0 && gridCols > 0);
        postInvalidate();
    }

    /** Clear per-cell grid data; falls back to uniform single-point mode. */
    public void clearGridData() {
        this.hasGridData = false;
        this.gridWindSpeed = null;
        this.gridWindDir = null;
        postInvalidate();
    }

    /** Set the grid density (arrows per axis). Range: 3–16. */
    public void setGridDensity(int cells) {
        this.gridCells = Math.max(3, Math.min(16, cells));
        postInvalidate();
    }

    /** Set the arrow size in dp. Range: 8–40. */
    public void setArrowSizeDp(float dp) {
        this.arrowSizeDp = Math.max(8f, Math.min(40f, dp));
        postInvalidate();
    }

    /** Set whether arrows are filled (true) or outline-only (false). */
    public void setFillArrow(boolean fill) {
        this.fillArrow = fill;
        postInvalidate();
    }

    /** Set the arrow drawing style. */
    public void setArrowStyle(ArrowStyle style) {
        this.arrowStyle = style;
        postInvalidate();
    }

    public ArrowStyle getArrowStyle() { return arrowStyle; }
    public int getGridDensity() { return gridCells; }
    public float getArrowSizeDp() { return arrowSizeDp; }
    public boolean isFillArrow() { return fillArrow; }

    /** Show/hide city-anchored wind labels. */
    public void setShowCityLabels(boolean show) {
        this.showCityLabels = show;
        setVisibility((visible || show) ? VISIBLE : GONE);
        postInvalidate();
    }
    public boolean isShowCityLabels() { return showCityLabels; }

    /**
     * Set parameter grid for city value labels (temperature, humidity, pressure).
     * Values are interpolated at city positions and shown as labels.
     */
    public void setCityParameterGrid(double[][] grid, String paramKey, String unit,
                                      double north, double south,
                                      double west, double east) {
        this.cityParamGrid = grid;
        this.cityParamKey = paramKey != null ? paramKey : "";
        this.cityParamUnit = unit != null ? unit : "";
        this.cityParamNorth = north;
        this.cityParamSouth = south;
        this.cityParamWest = west;
        this.cityParamEast = east;
        if (grid != null) {
            this.cityParamRows = grid.length;
            this.cityParamCols = cityParamRows > 0 ? grid[0].length : 0;
        }
        this.hasCityParamData = (grid != null && cityParamRows > 0 && cityParamCols > 0);
        postInvalidate();
    }

    public void clearCityParameterGrid() {
        this.hasCityParamData = false;
        this.cityParamGrid = null;
        postInvalidate();
    }

    // ── Drawing (geo-projected) ────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // Need at least one feature enabled
        if (!visible && !showCityLabels) return;

        float dp = getContext().getResources().getDisplayMetrics().density;
        float arrowSize = arrowSizeDp * dp;
        float mapRotation = (float) mapView.getMapRotation();
        int alphaVal = Math.round(opacity * 255);
        outlinePaint.setAlpha(alphaVal);

        int screenW = getWidth();
        int screenH = getHeight();
        if (screenW <= 0 || screenH <= 0) return;

        // ── Grid/uniform arrows (only when arrows are visible) ──────────
        if (visible) {
            if (hasGridData) {
                drawGridArrows(canvas, arrowSize, mapRotation, alphaVal, screenW, screenH);
            } else {
                drawUniformArrows(canvas, arrowSize, mapRotation, alphaVal, screenW, screenH);
            }
        }

        // ── City labels (independent of arrow visibility) ──────────────
        if (showCityLabels && cityDb != null && cityDb.isLoaded()) {
            drawCityWindLabels(canvas, arrowSize * 1.2f, mapRotation, alphaVal, screenW, screenH);
        }
    }

    /** Draw arrows using per-cell grid wind data (different direction per cell). */
    private void drawGridArrows(Canvas canvas, float arrowSize, float mapRotation,
                                 int alphaVal, int screenW, int screenH) {
        if (gridWindSpeed == null || gridWindDir == null) return;

        double latStep = (gridNorth - gridSouth) / Math.max(1, gridRows - 1);
        double lonStep = (gridEast - gridWest) / Math.max(1, gridCols - 1);

        // Sample grid at display density (skip rows/cols if grid is denser than display)
        int rowSkip = Math.max(1, gridRows / gridCells);
        int colSkip = Math.max(1, gridCols / gridCells);

        for (int r = 0; r < gridRows; r += rowSkip) {
            for (int c = 0; c < gridCols; c += colSkip) {
                double ws = gridWindSpeed[r][c];
                double wd = gridWindDir[r][c];
                if (ws <= 0 || Double.isNaN(ws) || Double.isNaN(wd)) continue;

                double lat = gridSouth + r * latStep;
                double lon = gridWest + c * lonStep;

                PointF screen = mapView.forward(new GeoPoint(lat, lon));
                if (screen == null) continue;
                if (screen.x < -arrowSize || screen.x > screenW + arrowSize
                        || screen.y < -arrowSize || screen.y > screenH + arrowSize) {
                    continue;
                }

                int color = windScale.getColor(ws);
                arrowPaint.setColor(Color.argb(alphaVal, Color.red(color),
                        Color.green(color), Color.blue(color)));

                float screenDir = (float) wd - mapRotation;
                drawArrow(canvas, screen.x, screen.y, screenDir, ws, arrowSize);
            }
        }
    }

    /** Draw arrows using uniform wind from single-point hourly forecast. */
    private void drawUniformArrows(Canvas canvas, float arrowSize, float mapRotation,
                                    int alphaVal, int screenW, int screenH) {
        if (hourlyData == null || hourlyData.isEmpty()) return;
        if (hourIndex < 0 || hourIndex >= hourlyData.size()) return;

        HourlyEntryModel entry = hourlyData.get(hourIndex);
        double windSpeed = entry.getWindSpeed();
        double windDir   = entry.getWindDirection();
        if (windSpeed <= 0) return;

        GeoBounds bounds = mapView.getBounds();
        if (bounds == null) return;

        double north = bounds.getNorth();
        double south = bounds.getSouth();
        double west  = bounds.getWest();
        double east  = bounds.getEast();
        if (north <= south || east <= west) return;

        int color = windScale.getColor(windSpeed);
        arrowPaint.setColor(Color.argb(alphaVal, Color.red(color),
                Color.green(color), Color.blue(color)));

        double latStep = (north - south) / gridCells;
        double lonStep = (east - west) / gridCells;
        float screenDir = (float) windDir - mapRotation;

        for (int gy = 0; gy <= gridCells; gy++) {
            for (int gx = 0; gx <= gridCells; gx++) {
                double lat = south + gy * latStep;
                double lon = west + gx * lonStep;

                PointF screen = mapView.forward(new GeoPoint(lat, lon));
                if (screen == null) continue;
                if (screen.x < -arrowSize || screen.x > screenW + arrowSize
                        || screen.y < -arrowSize || screen.y > screenH + arrowSize) {
                    continue;
                }

                drawArrow(canvas, screen.x, screen.y, screenDir, windSpeed, arrowSize);
            }
        }
    }

    /**
     * Draw a single wind indicator at screen position (cx, cy).
     */
    private void drawArrow(Canvas canvas, float cx, float cy,
                           float screenDir, double windSpeed, float size) {
        float scale = Math.max(0.4f, Math.min(1.2f, (float) windSpeed / 15f));
        float s = size * scale;

        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(screenDir);  // corrected for map rotation

        switch (arrowStyle) {
            case BARB:    drawBarb(canvas, s);         break;
            case CHEVRON: drawChevron(canvas, s);      break;
            case DOT:     drawDotLine(canvas, s);      break;
            case ARROW:
            default:      drawClassicArrow(canvas, s); break;
        }

        canvas.restore();
    }

    // ── Arrow styles ──────────────────────────────────────────────────────────

    /** Classic filled/outline arrow (diamond shape). */
    private void drawClassicArrow(Canvas canvas, float s) {
        arrowPath.reset();
        arrowPath.moveTo(0, -s * 0.5f);           // tip
        arrowPath.lineTo(-s * 0.18f, -s * 0.1f);  // left barb
        arrowPath.lineTo(0, s * 0.5f);             // tail
        arrowPath.lineTo(s * 0.18f, -s * 0.1f);   // right barb
        arrowPath.close();
        if (fillArrow) canvas.drawPath(arrowPath, arrowPaint);
        canvas.drawPath(arrowPath, outlinePaint);
    }

    /** Wind barb style: staff with angled barbs indicating speed. */
    private void drawBarb(Canvas canvas, float s) {
        // Staff: vertical line
        canvas.drawLine(0, -s * 0.5f, 0, s * 0.5f, outlinePaint);
        canvas.drawLine(0, -s * 0.5f, 0, s * 0.5f, arrowPaint);
        // Barb lines at the top (wind-from end)
        float barbLen = s * 0.35f;
        canvas.drawLine(0, -s * 0.5f, barbLen, -s * 0.35f, outlinePaint);
        canvas.drawLine(0, -s * 0.5f, barbLen, -s * 0.35f, arrowPaint);
        canvas.drawLine(0, -s * 0.3f, barbLen * 0.7f, -s * 0.15f, outlinePaint);
        canvas.drawLine(0, -s * 0.3f, barbLen * 0.7f, -s * 0.15f, arrowPaint);
        // Small circle at tail
        canvas.drawCircle(0, s * 0.5f, s * 0.06f, arrowPaint);
    }

    /** Chevron/V-shape pointing into the wind direction. */
    private void drawChevron(Canvas canvas, float s) {
        arrowPath.reset();
        arrowPath.moveTo(-s * 0.25f, s * 0.15f);
        arrowPath.lineTo(0, -s * 0.35f);
        arrowPath.lineTo(s * 0.25f, s * 0.15f);
        float savedWidth = outlinePaint.getStrokeWidth();
        outlinePaint.setStrokeWidth(Math.max(2f, s * 0.08f));
        canvas.drawPath(arrowPath, outlinePaint);
        if (fillArrow) {
            arrowPath.close();
            canvas.drawPath(arrowPath, arrowPaint);
        }
        outlinePaint.setStrokeWidth(savedWidth);
    }

    /** Dot at center + line pointing in wind direction with small arrowhead. */
    private void drawDotLine(Canvas canvas, float s) {
        float dotR = s * 0.12f;
        canvas.drawCircle(0, 0, dotR, arrowPaint);
        canvas.drawCircle(0, 0, dotR, outlinePaint);
        canvas.drawLine(0, 0, 0, -s * 0.45f, outlinePaint);
        canvas.drawLine(0, 0, 0, -s * 0.45f, arrowPaint);
        float tipY = -s * 0.45f;
        canvas.drawLine(0, tipY, -s * 0.08f, tipY + s * 0.1f, outlinePaint);
        canvas.drawLine(0, tipY, s * 0.08f, tipY + s * 0.1f, outlinePaint);
    }

    // ── City-anchored wind labels ─────────────────────────────────────────

    /**
     * Draw wind arrows + speed labels anchored to city locations.
     * Cities are filtered by zoom level (rank-based).
     * Wind is interpolated from the heatmap grid at each city's position.
     */
    private void drawCityWindLabels(Canvas canvas, float arrowSize, float mapRotation,
                                     int alphaVal, int screenW, int screenH) {
        GeoBounds bounds = mapView.getBounds();
        if (bounds == null) return;
        double mapRes = mapView.getMapResolution();

        java.util.List<CityWindDatabase.City> cities = cityDb.queryVisible(
                bounds.getNorth(), bounds.getSouth(),
                bounds.getWest(), bounds.getEast(),
                mapRes);

        if (cities.isEmpty()) return;

        float dp = getContext().getResources().getDisplayMetrics().density;

        // Zoom-adaptive sizing: larger labels when zoomed in, smaller when zoomed out
        // mapRes ~100 = city zoom (large labels), ~5000 = continental (small labels)
        float zoomFactor = (float) Math.max(0.6, Math.min(1.4, 1000.0 / Math.max(mapRes, 100)));
        arrowSize *= zoomFactor;

        for (CityWindDatabase.City city : cities) {
            // Get wind at city location
            double ws, wd;
            if (hasGridData && gridWindSpeed != null && gridWindDir != null) {
                // Interpolate from grid
                double fracRow = (city.lat - gridSouth) / (gridNorth - gridSouth) * (gridRows - 1);
                double fracCol = (city.lon - gridWest) / (gridEast - gridWest) * (gridCols - 1);
                if (fracRow < 0 || fracRow >= gridRows - 1 || fracCol < 0 || fracCol >= gridCols - 1) continue;
                int r0 = Math.max(0, Math.min(gridRows - 2, (int) fracRow));
                int c0 = Math.max(0, Math.min(gridCols - 2, (int) fracCol));
                double fr = fracRow - r0, fc = fracCol - c0;
                ws = gridWindSpeed[r0][c0]*(1-fr)*(1-fc) + gridWindSpeed[r0][c0+1]*(1-fr)*fc
                   + gridWindSpeed[r0+1][c0]*fr*(1-fc) + gridWindSpeed[r0+1][c0+1]*fr*fc;
                double d00 = Math.toRadians(gridWindDir[r0][c0]);
                double d01 = Math.toRadians(gridWindDir[r0][c0+1]);
                double d10 = Math.toRadians(gridWindDir[r0+1][c0]);
                double d11 = Math.toRadians(gridWindDir[r0+1][c0+1]);
                double sinS = Math.sin(d00)*(1-fr)*(1-fc) + Math.sin(d01)*(1-fr)*fc
                            + Math.sin(d10)*fr*(1-fc) + Math.sin(d11)*fr*fc;
                double cosS = Math.cos(d00)*(1-fr)*(1-fc) + Math.cos(d01)*(1-fr)*fc
                            + Math.cos(d10)*fr*(1-fc) + Math.cos(d11)*fr*fc;
                wd = Math.toDegrees(Math.atan2(sinS, cosS));
                if (wd < 0) wd += 360;
            } else if (hourlyData != null && !hourlyData.isEmpty()
                    && hourIndex >= 0 && hourIndex < hourlyData.size()) {
                // Uniform fallback
                HourlyEntryModel entry = hourlyData.get(hourIndex);
                ws = entry.getWindSpeed();
                wd = entry.getWindDirection();
            } else {
                continue;
            }

            if (ws < 0.1) continue;

            // Project city to screen
            PointF screen = mapView.forward(new GeoPoint(city.lat, city.lon));
            if (screen == null) continue;
            if (screen.x < -50 || screen.x > screenW + 50
                    || screen.y < -50 || screen.y > screenH + 50) continue;

            float cx = screen.x;
            float cy = screen.y;

            // Draw arrow at city position
            int color = windScale.getColor(ws);
            arrowPaint.setColor(Color.argb(alphaVal, Color.red(color),
                    Color.green(color), Color.blue(color)));
            float screenDir = (float) wd - mapRotation;
            drawArrow(canvas, cx, cy, screenDir, ws, arrowSize);

            // ── Wind label: "5 m/s NW" ──
            String compass = degreesToCompass(wd);
            String speedText = String.format(java.util.Locale.US, "%.0f", ws);
            String windLabel = speedText + " m/s " + compass;

            // ── Parameter value label (temp/humidity/pressure if available) ──
            String paramLabel = null;
            if (hasCityParamData && cityParamGrid != null) {
                double val = interpolateGrid(city.lat, city.lon,
                        cityParamGrid, cityParamNorth, cityParamSouth,
                        cityParamWest, cityParamEast, cityParamRows, cityParamCols);
                if (!Double.isNaN(val)) {
                    if ("temperature_2m".equals(cityParamKey)) {
                        paramLabel = String.format(java.util.Locale.US, "%.0f%s", val, cityParamUnit);
                    } else if ("relative_humidity_2m".equals(cityParamKey)) {
                        paramLabel = String.format(java.util.Locale.US, "%.0f%s", val, cityParamUnit);
                    } else if ("surface_pressure".equals(cityParamKey)) {
                        paramLabel = String.format(java.util.Locale.US, "%.0f %s", val, cityParamUnit);
                    } else {
                        paramLabel = String.format(java.util.Locale.US, "%.1f %s", val, cityParamUnit);
                    }
                }
            }

            float pad = 4f * dp * zoomFactor;
            float baseFontSize = 10f * dp * zoomFactor;
            float smallFontSize = 9f * dp * zoomFactor;
            cityLabelPaint.setTextSize(baseFontSize);
            float textH = cityLabelPaint.getTextSize();

            // ── City name (top) ──
            float nameY = cy - arrowSize * 0.7f - 3f * dp;
            cityLabelPaint.setColor(Color.argb((int)(alphaVal * 0.85f), 255, 255, 255));
            cityLabelPaint.setTextSize(smallFontSize);
            float nameW = cityLabelPaint.measureText(city.name);
            canvas.drawRoundRect(
                    cx - nameW / 2f - pad, nameY - textH + 2f * dp,
                    cx + nameW / 2f + pad, nameY + 3f * dp,
                    3f * dp, 3f * dp, cityBgPaint);
            canvas.drawText(city.name, cx, nameY, cityLabelPaint);

            // ── Wind speed + direction (below arrow) ──
            cityLabelPaint.setTextSize(baseFontSize);
            float labelY = cy + arrowSize * 0.7f + 6f * dp;
            float windW = cityLabelPaint.measureText(windLabel);
            canvas.drawRoundRect(
                    cx - windW / 2f - pad, labelY - textH,
                    cx + windW / 2f + pad, labelY + pad,
                    4f * dp, 4f * dp, cityBgPaint);
            cityLabelPaint.setColor(Color.argb(alphaVal, Color.red(color),
                    Color.green(color), Color.blue(color)));
            canvas.drawText(windLabel, cx, labelY, cityLabelPaint);

            // ── Parameter value (below wind label, if available) ──
            if (paramLabel != null) {
                float paramY = labelY + textH + 2f * dp;
                cityLabelPaint.setTextSize(smallFontSize);
                cityLabelPaint.setColor(Color.argb((int)(alphaVal * 0.9f), 200, 220, 255));
                float paramW = cityLabelPaint.measureText(paramLabel);
                canvas.drawRoundRect(
                        cx - paramW / 2f - pad, paramY - textH + 2f * dp,
                        cx + paramW / 2f + pad, paramY + 3f * dp,
                        3f * dp, 3f * dp, cityBgPaint);
                canvas.drawText(paramLabel, cx, paramY, cityLabelPaint);
            }

            cityLabelPaint.setTextSize(baseFontSize); // restore
        }
    }

    /** Convert wind direction degrees to 16-point compass bearing. */
    private static String degreesToCompass(double deg) {
        String[] dirs = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
        int idx = (int) Math.round(((deg % 360) + 360) % 360 / 22.5) % 16;
        return dirs[idx];
    }

    /** Bilinear interpolation of a scalar grid value at (lat, lon). */
    private static double interpolateGrid(double lat, double lon,
                                           double[][] grid,
                                           double north, double south,
                                           double west, double east,
                                           int rows, int cols) {
        if (grid == null || rows < 2 || cols < 2) return Double.NaN;
        if (lat < south || lat > north || lon < west || lon > east) return Double.NaN;

        double fracRow = (lat - south) / (north - south) * (rows - 1);
        double fracCol = (lon - west) / (east - west) * (cols - 1);
        int r0 = Math.max(0, Math.min(rows - 2, (int) fracRow));
        int c0 = Math.max(0, Math.min(cols - 2, (int) fracCol));
        double fr = fracRow - r0, fc = fracCol - c0;

        return grid[r0][c0] * (1-fr)*(1-fc) + grid[r0][c0+1] * (1-fr)*fc
             + grid[r0+1][c0] * fr*(1-fc) + grid[r0+1][c0+1] * fr*fc;
    }
}
