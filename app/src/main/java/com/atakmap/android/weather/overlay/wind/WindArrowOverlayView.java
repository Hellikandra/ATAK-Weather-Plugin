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

    // Data — single-point fallback
    private List<HourlyEntryModel> hourlyData;
    private int hourIndex = 0;
    private float opacity = 0.85f;
    private boolean visible = false;

    // Grid data — per-cell wind from heatmap dataset
    private double[][] gridWindSpeed;    // [row][col]
    private double[][] gridWindDir;      // [row][col]
    private double gridNorth, gridSouth, gridWest, gridEast;
    private int gridRows, gridCols;
    private boolean hasGridData = false;

    /** Arrow drawing styles. */
    public enum ArrowStyle { ARROW, BARB, CHEVRON, DOT }

    // Grid config — mutable for user control
    private int gridCells = 8;
    private float arrowSizeDp = 18f;
    private boolean fillArrow = true;
    private ArrowStyle arrowStyle = ArrowStyle.ARROW;

    private final ColourScale windScale;

    private final MapView.OnMapMovedListener mapMovedListener =
            (view, animate) -> post(WindArrowOverlayView.this::invalidate);

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
        setVisibility(v ? VISIBLE : GONE);
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

    // ── Drawing (geo-projected) ────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!visible) return;

        float dp = getContext().getResources().getDisplayMetrics().density;
        float arrowSize = arrowSizeDp * dp;
        float mapRotation = (float) mapView.getMapRotation();
        int alphaVal = Math.round(opacity * 255);
        outlinePaint.setAlpha(alphaVal);

        int screenW = getWidth();
        int screenH = getHeight();
        if (screenW <= 0 || screenH <= 0) return;

        if (hasGridData) {
            // ── Per-cell wind from heatmap grid data ──────────────────────
            drawGridArrows(canvas, arrowSize, mapRotation, alphaVal, screenW, screenH);
        } else {
            // ── Uniform single-point fallback ─────────────────────────────
            drawUniformArrows(canvas, arrowSize, mapRotation, alphaVal, screenW, screenH);
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
        // Line from center toward wind-from
        canvas.drawLine(0, 0, 0, -s * 0.45f, outlinePaint);
        canvas.drawLine(0, 0, 0, -s * 0.45f, arrowPaint);
        // Small arrowhead at tip
        float tipY = -s * 0.45f;
        canvas.drawLine(0, tipY, -s * 0.08f, tipY + s * 0.1f, outlinePaint);
        canvas.drawLine(0, tipY, s * 0.08f, tipY + s * 0.1f, outlinePaint);
    }
}
