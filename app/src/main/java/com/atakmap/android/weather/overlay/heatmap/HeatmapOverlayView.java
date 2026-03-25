package com.atakmap.android.weather.overlay.heatmap;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.maps.coords.GeoPoint;

/**
 * Transparent view overlaid on MapView that renders a heatmap bitmap
 * geo-registered to the map surface via perspective sub-tile projection.
 *
 * <p>The bitmap is split into an NxN grid of sub-tiles, each independently
 * projected using 4-corner {@link Matrix#setPolyToPoly}. This handles globe
 * curvature correctly even for large geo extents — each sub-tile covers a
 * small enough area that a single perspective transform is accurate.</p>
 *
 * <p>This matches the approach used by {@code RadarOverlayView} for radar
 * tiles, and approximates how ATAK's native {@code ElevationHeatmapLayer}
 * renders on {@code RenderStack.MAP_SURFACE_OVERLAYS}.</p>
 */
public class HeatmapOverlayView extends View {

    /** Number of sub-tiles per axis. 4x4 = 16 sub-quads. */
    private static final int SUB_TILES = 4;

    /** Max angular distance (degrees) from camera center before culling. */
    private static final double MAX_VISIBLE_ANGLE = 85.0;

    private final MapView mapView;
    private final Paint   paint    = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix  tileMtx  = new Matrix();

    private Bitmap   bitmap;
    private double   geoNorth, geoSouth, geoEast, geoWest;
    private boolean  hasBounds = false;
    private int      alpha = 153; // 60%

    private MapView.OnMapMovedListener movedListener;

    public HeatmapOverlayView(MapView mapView) {
        super(mapView.getContext());
        this.mapView = mapView;
        setWillNotDraw(false);
        setLayoutParams(new MapView.LayoutParams(
                MapView.LayoutParams.MATCH_PARENT,
                MapView.LayoutParams.MATCH_PARENT));
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public void attach() {
        if (getParent() != null) return;
        mapView.addView(this);
        movedListener = (v, animate) -> post(HeatmapOverlayView.this::invalidate);
        mapView.addOnMapMovedListener(movedListener);
    }

    public void detach() {
        if (movedListener != null) {
            mapView.removeOnMapMovedListener(movedListener);
            movedListener = null;
        }
        if (getParent() != null) {
            mapView.removeView(this);
        }
    }

    /**
     * Set the heatmap bitmap and its geo-bounds.
     */
    public void setHeatmap(Bitmap bmp, double north, double south,
                            double east, double west) {
        this.bitmap   = bmp;
        this.geoNorth = north;
        this.geoSouth = south;
        this.geoEast  = east;
        this.geoWest  = west;
        this.hasBounds = true;
        invalidate();
    }

    public void clearHeatmap() {
        this.bitmap = null;
        this.hasBounds = false;
        invalidate();
    }

    public void setOverlayAlpha(int alpha) {
        this.alpha = Math.max(0, Math.min(255, alpha));
        invalidate();
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    /**
     * Draws the heatmap as a grid of sub-tiles, each with independent
     * 4-corner perspective projection. This handles globe curvature correctly
     * for large geo extents by keeping each sub-quad small enough for an
     * accurate projective transform.
     */
    @Override
    protected void onDraw(Canvas canvas) {
        if (bitmap == null || bitmap.isRecycled() || !hasBounds) return;

        final int viewW = getWidth();
        final int viewH = getHeight();
        if (viewW <= 0 || viewH <= 0) return;

        // Globe back-face culling: skip if data center is on far side
        final double centerLat = mapView.getLatitude();
        final double centerLon = mapView.getLongitude();
        final double dataCLat  = (geoNorth + geoSouth) * 0.5;
        final double dataCLon  = (geoEast + geoWest) * 0.5;
        if (angularDistance(centerLat, centerLon, dataCLat, dataCLon) > MAX_VISIBLE_ANGLE) {
            return;
        }

        final int bw = bitmap.getWidth();
        final int bh = bitmap.getHeight();

        double latStep = (geoNorth - geoSouth) / SUB_TILES;
        double lonStep = (geoEast - geoWest)   / SUB_TILES;

        int bmpColStep = bw / SUB_TILES;
        int bmpRowStep = bh / SUB_TILES;

        paint.setAlpha(alpha);

        final float margin = Math.max(viewW, viewH) * 0.5f;

        for (int row = 0; row < SUB_TILES; row++) {
            for (int col = 0; col < SUB_TILES; col++) {
                // Geo bounds of this sub-tile
                double tileN = geoNorth - row * latStep;
                double tileS = tileN - latStep;
                double tileW = geoWest + col * lonStep;
                double tileE = tileW + lonStep;

                // Globe culling per sub-tile
                double tileCLat = (tileN + tileS) * 0.5;
                double tileCLon = (tileE + tileW) * 0.5;
                if (angularDistance(centerLat, centerLon, tileCLat, tileCLon) > MAX_VISIBLE_ANGLE) {
                    continue;
                }

                // Forward-project all 4 corners
                PointF pNW = mapView.forward(new GeoPoint(tileN, tileW));
                PointF pNE = mapView.forward(new GeoPoint(tileN, tileE));
                PointF pSE = mapView.forward(new GeoPoint(tileS, tileE));
                PointF pSW = mapView.forward(new GeoPoint(tileS, tileW));

                if (pNW == null || pNE == null || pSE == null || pSW == null) continue;

                // Frustum culling
                if (pNW.x < -margin && pNE.x < -margin && pSE.x < -margin && pSW.x < -margin) continue;
                if (pNW.x > viewW + margin && pNE.x > viewW + margin && pSE.x > viewW + margin && pSW.x > viewW + margin) continue;
                if (pNW.y < -margin && pNE.y < -margin && pSE.y < -margin && pSW.y < -margin) continue;
                if (pNW.y > viewH + margin && pNE.y > viewH + margin && pSE.y > viewH + margin && pSW.y > viewH + margin) continue;

                // Bitmap source rect for this sub-tile
                int srcLeft   = col * bmpColStep;
                int srcTop    = row * bmpRowStep;
                int srcRight  = (col == SUB_TILES - 1) ? bw : srcLeft + bmpColStep;
                int srcBottom = (row == SUB_TILES - 1) ? bh : srcTop + bmpRowStep;

                float sw = srcRight - srcLeft;
                float sh = srcBottom - srcTop;

                float[] src = {
                        0,  0,     // NW (relative to sub-tile)
                        sw, 0,     // NE
                        sw, sh,    // SE
                        0,  sh     // SW
                };

                float[] dst = {
                        pNW.x, pNW.y,
                        pNE.x, pNE.y,
                        pSE.x, pSE.y,
                        pSW.x, pSW.y
                };

                tileMtx.reset();
                if (!tileMtx.setPolyToPoly(src, 0, dst, 0, 4)) continue;

                // Canvas clip + translate to draw sub-region of the bitmap
                canvas.save();
                canvas.concat(tileMtx);
                // Draw the sub-tile portion of the bitmap
                canvas.drawBitmap(bitmap,
                        new Rect(srcLeft, srcTop, srcRight, srcBottom),
                        new RectF(0, 0, sw, sh),
                        paint);
                canvas.restore();
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Approximate angular distance between two lat/lon points (great-circle).
     * Used for globe back-face culling — no need for high precision.
     */
    private static double angularDistance(double lat1, double lon1,
                                          double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return Math.toDegrees(2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
    }
}
