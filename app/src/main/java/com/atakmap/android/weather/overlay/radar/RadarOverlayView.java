package com.atakmap.android.weather.overlay.radar;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.view.View;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * RadarOverlayView — transparent View placed on top of MapView that paints
 * geo-registered radar tile bitmaps at their correct screen positions.
 *
 * <h3>Geo → Screen projection (Sprint 1 fix)</h3>
 *
 * Uses ATAK's native {@code MapView.forward(GeoPoint)} which delegates to the
 * internal {@code MapSceneModel}.  This correctly accounts for:
 * <ul>
 *   <li>Map <b>tilt</b> (3D perspective) — tiles foreshorten towards the horizon</li>
 *   <li>Map <b>rotation</b></li>
 *   <li>Map <b>zoom / resolution</b></li>
 *   <li>Globe curvature (Mercator → screen projection)</li>
 * </ul>
 *
 * Each tile's 4 geo-corners (NW, NE, SE, SW) are forward-projected to screen
 * coordinates, then rendered via {@link Matrix#setPolyToPoly(float[], int, float[], int, int)}
 * which creates a perspective-correct affine/projective transform.  This makes
 * tiles lie flat on the map surface even under tilt, rather than appearing
 * perpendicular to the earth.
 *
 * <h3>Why a View, not a RenderStack Layer</h3>
 *
 * ATAK's proper overlay pattern uses {@code Layer} + {@code GLLayer2} +
 * {@code GLLayerSpi2}, registered on {@code MapView.RenderStack.MAP_SURFACE_OVERLAYS}
 * (see ATAK's {@code HeatMapOverlay} / {@code GLHeatMap} / {@code ElevationOverlaysMapComponent}).
 * That approach requires OpenGL texture rendering on the GL thread.
 *
 * <p>This View-based approach uses ATAK's projection API for correct geo-registration
 * but still doesn't participate in ATAK's GL render pipeline (no frustum culling,
 * no GPU texture caching, no depth buffer integration).  Migration to a proper
 * {@code GLLayer2} is planned for Sprint 5.</p>
 *
 * <h3>Color controls (Sprint 1.1c)</h3>
 *
 * Mirrors ATAK's {@code HeatMapOverlay.setColorComponents(saturation, value, alpha)}:
 * <ul>
 *   <li><b>Saturation</b> (0–1): 0 = greyscale, 1 = full color</li>
 *   <li><b>Value/Brightness</b> (0–1): multiplier on RGB channels</li>
 *   <li><b>Intensity/Alpha</b> (0–1): overall transparency (stacks with per-tile alpha)</li>
 * </ul>
 * Implemented via {@link ColorMatrixColorFilter} on the shared {@link Paint} —
 * no per-pixel CPU processing, hardware-accelerated on the Canvas.
 *
 * <h3>Thread safety</h3>
 *
 * Tile list, alpha, and color components are mutated on the main thread only
 * (callers use {@code mapView.post()}).  {@code invalidate()} is safe from any thread.
 */
public class RadarOverlayView extends View {

    private static final String TAG = "RadarOverlayView";

    /** Radians conversion factor. */
    private static final double DEG_TO_RAD = Math.PI / 180.0;

    /**
     * Maximum angular distance (radians) between map centre and tile centre
     * for the tile to be drawn.  Tiles beyond this threshold are on the far
     * side of the globe and should be culled to avoid wraparound artefacts.
     * ~85° gives a comfortable margin before the horizon.
     */
    private static final double MAX_VISIBLE_ANGLE = 85.0 * DEG_TO_RAD;

    // ── Tile descriptor ───────────────────────────────────────────────────────

    static final class Tile {
        final Bitmap   bitmap;
        final GeoPoint nw;
        final GeoPoint se;
        int      alpha;

        Tile(Bitmap b, GeoPoint nw, GeoPoint se, int alpha) {
            this.bitmap = b; this.nw = nw; this.se = se; this.alpha = alpha;
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private final MapView        mapView;
    private final List<Tile>     tiles = new ArrayList<>();
    private final Paint          paint = new Paint(Paint.FILTER_BITMAP_FLAG);

    /** Reusable matrix for perspective tile rendering. */
    private final Matrix         tileMatrix = new Matrix();

    /** Registered while the view is attached; null otherwise. */
    private MapView.OnMapMovedListener movedListener;

    // ── Color components (mirroring HeatMapOverlay) ──────────────────────────

    /**
     * HSV-like color controls matching ATAK's HeatMapOverlay pattern.
     * All values are 0.0 – 1.0.
     */
    private float colorSaturation = 1.0f;   // 0 = greyscale, 1 = full color
    private float colorValue      = 1.0f;   // 0 = black, 1 = full brightness
    private float colorIntensity  = 1.0f;   // 0 = invisible, 1 = fully opaque

    /** Whether the color matrix needs recomputing. */
    private boolean colorMatrixDirty = true;

    // ── Constructor ───────────────────────────────────────────────────────────

    public RadarOverlayView(MapView mapView) {
        super(mapView.getContext());
        this.mapView = mapView;
        setWillNotDraw(false);
        setLayoutParams(new MapView.LayoutParams(
                MapView.LayoutParams.MATCH_PARENT,
                MapView.LayoutParams.MATCH_PARENT));
        rebuildColorFilter();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void attach() {
        if (getParent() != null) return;
        mapView.addView(this);
        movedListener = (v, animate) -> post(RadarOverlayView.this::invalidate);
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

    public void setTiles(List<Tile> newTiles) {
        tiles.clear();
        if (newTiles != null) tiles.addAll(newTiles);
        invalidate();
    }

    public void clearTiles() {
        tiles.clear();
        invalidate();
    }

    /** Set alpha for all current tiles (0-255) and redraw. */
    @Override
    public void setAlpha(float alpha) {
        // Override View.setAlpha to update per-tile alpha instead
        int a = Math.round(alpha * 255);
        for (Tile t : tiles) t.alpha = a;
        invalidate();
    }

    /** Convenience: set alpha as int 0-255 for all tiles. */
    public void setAlpha(int alpha) {
        for (Tile t : tiles) t.alpha = alpha;
        invalidate();
    }

    // ── Color controls (Sprint 1.1c) ────────────────────────────────────────

    /**
     * Set all color components at once. Mirrors ATAK's
     * {@code HeatMapOverlay.setColorComponents(saturation, value, alpha)}.
     *
     * @param saturation 0.0 = greyscale → 1.0 = full color
     * @param value      0.0 = black → 1.0 = full brightness
     * @param intensity  0.0 = invisible → 1.0 = fully opaque
     */
    public void setColorComponents(float saturation, float value, float intensity) {
        this.colorSaturation = clamp01(saturation);
        this.colorValue      = clamp01(value);
        this.colorIntensity  = clamp01(intensity);
        this.colorMatrixDirty = true;
        invalidate();
    }

    /** @return current saturation (0–1). */
    public float getColorSaturation() { return colorSaturation; }

    /** @return current value/brightness (0–1). */
    public float getColorValue() { return colorValue; }

    /** @return current intensity/alpha (0–1). */
    public float getColorIntensity() { return colorIntensity; }

    /**
     * Set saturation only (0–1). 0 = greyscale, 1 = full color.
     */
    public void setSaturation(float s) {
        this.colorSaturation = clamp01(s);
        this.colorMatrixDirty = true;
        invalidate();
    }

    /**
     * Set value/brightness only (0–1). 0 = dark, 1 = full brightness.
     */
    public void setValue(float v) {
        this.colorValue = clamp01(v);
        this.colorMatrixDirty = true;
        invalidate();
    }

    /**
     * Set intensity/overall-alpha only (0–1). 0 = invisible, 1 = fully opaque.
     * This stacks multiplicatively with per-tile alpha and the opacity seekbar.
     */
    public void setIntensity(float i) {
        this.colorIntensity = clamp01(i);
        this.colorMatrixDirty = true;
        invalidate();
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    /**
     * Draws each radar tile using ATAK's native {@code MapView.forward(GeoPoint)}
     * projection, which accounts for tilt, rotation, zoom, and globe curvature.
     *
     * <p>Each tile's 4 geo-corners are projected to screen space, then the bitmap
     * is rendered via a perspective {@link Matrix} from {@code setPolyToPoly()}.
     * This makes tiles lie flat on the map surface even under 3D tilt.</p>
     */
    @Override
    protected void onDraw(Canvas canvas) {
        if (tiles.isEmpty()) return;

        // Rebuild the color matrix filter if any color component changed
        if (colorMatrixDirty) {
            rebuildColorFilter();
            colorMatrixDirty = false;
        }

        final int viewW = getWidth();
        final int viewH = getHeight();
        if (viewW <= 0 || viewH <= 0) return;

        for (Tile tile : tiles) {
            try {
                drawTilePerspective(canvas, tile, viewW, viewH);
            } catch (Exception e) {
                Log.w(TAG, "tile draw: " + e.getMessage());
            }
        }
    }

    /**
     * Draw a single tile using perspective-correct projection.
     *
     * <p>Projects all 4 geo-corners via {@code mapView.forward()}, builds a
     * projective transform via {@link Matrix#setPolyToPoly}, and draws the
     * bitmap through that matrix.</p>
     */
    private void drawTilePerspective(Canvas canvas, Tile tile, int viewW, int viewH) {
        final double nwLat = tile.nw.getLatitude();
        final double nwLon = tile.nw.getLongitude();
        final double seLat = tile.se.getLatitude();
        final double seLon = tile.se.getLongitude();

        // ── Globe back-face culling ──────────────────────────────────────────
        // In 3D globe view, tiles on the far side of the earth get projected
        // to screen coordinates (often wildly distorted).  Skip tiles whose
        // centre is more than ~85° from the camera centre — they are on the
        // far side of the globe and should not be drawn.
        final double centerLat = mapView.getLatitude();
        final double centerLon = mapView.getLongitude();
        final double tileCLat = (nwLat + seLat) * 0.5;
        final double tileCLon = (nwLon + seLon) * 0.5;
        if (angularDistance(centerLat, centerLon, tileCLat, tileCLon) > MAX_VISIBLE_ANGLE)
            return;

        // Forward-project all 4 geo-corners to screen coordinates.
        // mapView.forward() uses the internal MapSceneModel which handles
        // tilt, rotation, zoom, and globe curvature automatically.
        PointF pNW = mapView.forward(tile.nw);
        PointF pNE = mapView.forward(new GeoPoint(nwLat, seLon));
        PointF pSE = mapView.forward(tile.se);
        PointF pSW = mapView.forward(new GeoPoint(seLat, nwLon));

        if (pNW == null || pNE == null || pSE == null || pSW == null) return;

        // Quick frustum check: skip if all 4 projected corners are well off-screen.
        // Use generous margin (half viewport) to avoid popping at edges.
        final float margin = Math.max(viewW, viewH);
        if (pNW.x < -margin && pNE.x < -margin && pSE.x < -margin && pSW.x < -margin) return;
        if (pNW.x > viewW + margin && pNE.x > viewW + margin && pSE.x > viewW + margin && pSW.x > viewW + margin) return;
        if (pNW.y < -margin && pNE.y < -margin && pSE.y < -margin && pSW.y < -margin) return;
        if (pNW.y > viewH + margin && pNE.y > viewH + margin && pSE.y > viewH + margin && pSW.y > viewH + margin) return;

        // Bitmap source corners: (0,0)=NW, (w,0)=NE, (w,h)=SE, (0,h)=SW
        final int bw = tile.bitmap.getWidth();
        final int bh = tile.bitmap.getHeight();

        float[] src = {
                0,  0,      // NW
                bw, 0,      // NE
                bw, bh,     // SE
                0,  bh      // SW
        };

        float[] dst = {
                pNW.x, pNW.y,   // NW
                pNE.x, pNE.y,   // NE
                pSE.x, pSE.y,   // SE
                pSW.x, pSW.y    // SW
        };

        // setPolyToPoly with 4 points creates a full projective (perspective)
        // transform — tiles render as proper quadrilaterals when tilted.
        if (!tileMatrix.setPolyToPoly(src, 0, dst, 0, 4)) return;

        // Per-tile alpha stacks with the intensity component in the color filter
        paint.setAlpha(Math.round(tile.alpha * colorIntensity));
        canvas.drawBitmap(tile.bitmap, tileMatrix, paint);
    }

    // ── Color matrix ──────────────────────────────────────────────────────────

    /**
     * Build a combined saturation + brightness {@link ColorMatrixColorFilter}.
     *
     * <p>The color matrix is a 4x5 matrix that transforms [R, G, B, A] → [R', G', B', A'].
     * We compose two effects:</p>
     * <ol>
     *   <li><b>Saturation:</b> uses Android's built-in {@link ColorMatrix#setSaturation(float)}
     *       (0 = greyscale via luminance weights, 1 = identity).</li>
     *   <li><b>Brightness/Value:</b> scale matrix that multiplies RGB by {@code colorValue}.</li>
     * </ol>
     *
     * <p>The intensity (alpha) component is applied separately via
     * {@link Paint#setAlpha(int)} to allow per-tile variation.</p>
     */
    private void rebuildColorFilter() {
        // Step 1: Saturation matrix
        ColorMatrix satMatrix = new ColorMatrix();
        satMatrix.setSaturation(colorSaturation);

        // Step 2: Brightness/value scale matrix
        // Multiplies R, G, B by colorValue, leaves A unchanged
        ColorMatrix valMatrix = new ColorMatrix(new float[]{
                colorValue, 0, 0, 0, 0,   // R
                0, colorValue, 0, 0, 0,   // G
                0, 0, colorValue, 0, 0,   // B
                0, 0, 0,          1, 0    // A (untouched — handled by Paint.setAlpha)
        });

        // Compose: apply saturation first, then brightness
        satMatrix.postConcat(valMatrix);

        paint.setColorFilter(new ColorMatrixColorFilter(satMatrix));
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }

    /**
     * Great-circle angular distance between two points (radians).
     * Uses the spherical law of cosines — accurate enough for culling.
     */
    private static double angularDistance(double lat1, double lon1,
                                          double lat2, double lon2) {
        double rlat1 = lat1 * DEG_TO_RAD;
        double rlat2 = lat2 * DEG_TO_RAD;
        double dlon  = (lon2 - lon1) * DEG_TO_RAD;
        double cos   = Math.sin(rlat1) * Math.sin(rlat2)
                      + Math.cos(rlat1) * Math.cos(rlat2) * Math.cos(dlon);
        // Clamp to [-1,1] to guard against floating-point overshoot
        return Math.acos(Math.max(-1.0, Math.min(1.0, cos)));
    }
}
