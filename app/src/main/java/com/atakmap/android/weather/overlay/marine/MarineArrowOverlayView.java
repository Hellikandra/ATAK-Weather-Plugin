package com.atakmap.android.weather.overlay.marine;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.view.View;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.weather.overlay.heatmap.ColourScale;
import com.atakmap.coremap.maps.coords.GeoPoint;

/**
 * Marine wave/current direction arrow overlay — completely independent from wind arrows.
 *
 * <p>Renders directional arrows for wave height, swell direction, or ocean current
 * on a geo-projected grid. Uses marine-appropriate colour scales.</p>
 */
public class MarineArrowOverlayView extends View {

    private final MapView mapView;
    private final Paint arrowPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrowPath = new Path();

    // Grid data
    private double[][] gridSpeed;
    private double[][] gridDirection;
    private double gridNorth, gridSouth, gridWest, gridEast;
    private int gridRows, gridCols;
    private boolean hasData = false;
    private boolean visible = false;

    // Config
    private int gridCells = 8;
    private float arrowSizeDp = 18f;
    private String paramKey = "wave_height"; // determines colour scale

    private ColourScale colourScale;
    private final MapView.OnMapMovedListener mapMovedListener;

    public MarineArrowOverlayView(MapView mapView) {
        super(mapView.getContext());
        this.mapView = mapView;
        setWillNotDraw(false);
        setLayoutParams(new MapView.LayoutParams(
                MapView.LayoutParams.MATCH_PARENT,
                MapView.LayoutParams.MATCH_PARENT));

        arrowPaint.setStyle(Paint.Style.FILL);
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setColor(Color.BLACK);
        outlinePaint.setStrokeWidth(1.5f);

        colourScale = ColourScale.forParameter("wave_height");
        // Fix #22 audit — VSYNC-aligned redraw eliminates 1-frame drift.
        mapMovedListener = (v, animate) -> postInvalidateOnAnimation();
    }

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

    public void setArrowsVisible(boolean v) {
        this.visible = v;
        setVisibility(v ? VISIBLE : GONE);
        postInvalidate();
    }

    public void setParameterKey(String key) {
        this.paramKey = key;
        this.colourScale = ColourScale.forParameter(key);
        postInvalidate();
    }

    /**
     * Set per-cell grid data for marine arrows.
     * @param speedGrid   [row][col] magnitude (wave height, current speed, etc.)
     * @param dirGrid     [row][col] direction in degrees
     */
    public void setGridData(double[][] speedGrid, double[][] dirGrid,
                             double north, double south,
                             double west, double east) {
        this.gridSpeed = speedGrid;
        this.gridDirection = dirGrid;
        this.gridNorth = north;
        this.gridSouth = south;
        this.gridWest = west;
        this.gridEast = east;
        if (speedGrid != null) {
            this.gridRows = speedGrid.length;
            this.gridCols = gridRows > 0 ? speedGrid[0].length : 0;
        }
        this.hasData = (speedGrid != null && dirGrid != null && gridRows > 0 && gridCols > 0);

        // Apply land mask
        if (hasData) {
            LandMaskUtil.applyLandMask(this.gridSpeed, north, south, west, east);
        }

        postInvalidate();
    }

    public void clearGridData() {
        this.hasData = false;
        this.gridSpeed = null;
        this.gridDirection = null;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!visible || !hasData || gridSpeed == null || gridDirection == null) return;

        float dp = getContext().getResources().getDisplayMetrics().density;
        float arrowSize = arrowSizeDp * dp;
        float mapRotation = (float) mapView.getMapRotation();
        int screenW = getWidth(), screenH = getHeight();
        if (screenW <= 0 || screenH <= 0) return;

        double latStep = (gridNorth - gridSouth) / Math.max(1, gridRows - 1);
        double lonStep = (gridEast - gridWest) / Math.max(1, gridCols - 1);

        int rowSkip = Math.max(1, gridRows / gridCells);
        int colSkip = Math.max(1, gridCols / gridCells);

        for (int r = 0; r < gridRows; r += rowSkip) {
            for (int c = 0; c < gridCols; c += colSkip) {
                double spd = gridSpeed[r][c];
                double dir = gridDirection[r][c];
                if (Double.isNaN(spd) || Double.isNaN(dir) || spd <= 0) continue;

                double lat = gridSouth + r * latStep;
                double lon = gridWest + c * lonStep;

                PointF screen = mapView.forward(new GeoPoint(lat, lon));
                if (screen == null) continue;
                if (screen.x < -arrowSize || screen.x > screenW + arrowSize
                        || screen.y < -arrowSize || screen.y > screenH + arrowSize) {
                    continue;
                }

                int color = colourScale.getColor(spd);
                arrowPaint.setColor(Color.argb(200, Color.red(color),
                        Color.green(color), Color.blue(color)));

                float screenDir = (float) dir - mapRotation;
                drawArrow(canvas, screen.x, screen.y, screenDir, spd, arrowSize);
            }
        }
    }

    private void drawArrow(Canvas canvas, float cx, float cy,
                           float screenDir, double speed, float size) {
        float scale = Math.max(0.4f, Math.min(1.2f, (float)(speed / 3.0))); // scale for marine
        float s = size * scale;

        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(screenDir);

        arrowPath.reset();
        arrowPath.moveTo(0, -s * 0.5f);
        arrowPath.lineTo(-s * 0.18f, -s * 0.1f);
        arrowPath.lineTo(0, s * 0.5f);
        arrowPath.lineTo(s * 0.18f, -s * 0.1f);
        arrowPath.close();
        canvas.drawPath(arrowPath, arrowPaint);
        canvas.drawPath(arrowPath, outlinePaint);

        canvas.restore();
    }
}
