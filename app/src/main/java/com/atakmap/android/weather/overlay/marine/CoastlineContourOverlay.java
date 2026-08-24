package com.atakmap.android.weather.overlay.marine;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.view.View;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.List;

/**
 * Debug overlay: draws coastline contour as cyan dots on the map.
 *
 * <p>Uses a simple View overlay (same pattern as HeatmapOverlayView) to draw
 * dots at each land/water boundary pixel from the CoastlineMask. This avoids
 * ATAK Polyline/Marker API issues and renders correctly in all map modes.</p>
 */
public class CoastlineContourOverlay extends View {

    private static final String TAG = "CoastlineContour";

    private final MapView mapView;
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<double[]> contourPoints;
    private boolean visible = false;
    private MapView.OnMapMovedListener moveListener;

    public CoastlineContourOverlay(MapView mapView) {
        super(mapView.getContext());
        this.mapView = mapView;
        setWillNotDraw(false);
        setLayoutParams(new MapView.LayoutParams(
                MapView.LayoutParams.MATCH_PARENT,
                MapView.LayoutParams.MATCH_PARENT));

        dotPaint.setColor(0xFF39D2C0);  // cyan
        dotPaint.setStyle(Paint.Style.FILL);
    }

    /**
     * Draw the coastline contour from the mask.
     */
    public void drawContour(CoastlineMask mask) {
        if (mask == null) return;
        this.contourPoints = mask.getContourPoints();

        if (!visible) {
            attach();
        }
        visible = true;
        setVisibility(VISIBLE);
        invalidate();

        Log.d(TAG, "Contour: " + (contourPoints != null ? contourPoints.size() : 0) + " edge points");
    }

    public void clear() {
        contourPoints = null;
        visible = false;
        setVisibility(GONE);
        detach();
    }

    private void attach() {
        if (getParent() != null) return;
        mapView.addView(this);
        moveListener = (v, animate) -> postInvalidate();
        mapView.addOnMapMovedListener(moveListener);
    }

    private void detach() {
        if (moveListener != null) {
            mapView.removeOnMapMovedListener(moveListener);
            moveListener = null;
        }
        if (getParent() != null) {
            mapView.removeView(this);
        }
    }

    public void dispose() {
        clear();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!visible || contourPoints == null || contourPoints.isEmpty()) return;

        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float dotRadius = 3f * getContext().getResources().getDisplayMetrics().density;

        int drawn = 0;
        for (double[] pt : contourPoints) {
            PointF screen = mapView.forward(new GeoPoint(pt[0], pt[1]));
            if (screen == null) continue;

            if (screen.x < -dotRadius || screen.x > w + dotRadius
                    || screen.y < -dotRadius || screen.y > h + dotRadius) {
                continue;
            }

            canvas.drawCircle(screen.x, screen.y, dotRadius, dotPaint);
            drawn++;
        }

        // Draw once per map move, no animation loop needed
    }
}
