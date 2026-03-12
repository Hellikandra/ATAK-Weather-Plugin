package org.dtakc.weather.atak.map.radar;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
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
 * ── Why a View, not a MapItem ────────────────────────────────────────────────
 *
 * ATAK Markers are point-based (fixed-pixel icon at a single GeoPoint).  There
 * is no public plugin API to stretch a Marker icon to an arbitrary geographic
 * bounding box.
 *
 * ── Geo → Screen projection ──────────────────────────────────────────────────
 *
 * MapView.MapViewProjection does NOT exist in the ATAK 5.6 SDK.  The forward()
 * method family lives in AbstractGLMapItem2 (GL render thread only) and the
 * abstract CapturePP class (capture pipeline only).  Neither is callable from a
 * plain View's onDraw().
 *
 * Instead we implement a Mercator projection manually using SDK-confirmed APIs:
 *
 *   mapView.getLatitude()      — center latitude  (°)
 *   mapView.getLongitude()     — center longitude (°)
 *   mapView.getMapResolution() — meters per pixel at the equator
 *   mapView.getMapRotation()   — map heading (° clockwise from north)
 *
 * For a tile corner at (tileLat, tileLon):
 *
 *   // Mercator y at latitude φ: ln(tan(π/4 + φ/2))
 *   yCentre = ln(tan(π/4 + centerLat*π/360))
 *   yTile   = ln(tan(π/4 + tileLat *π/360))
 *
 *   metersPerDegLon = EARTH_RADIUS * cos(centerLat_rad)
 *   dx_m  = (tileLon - centerLon) * metersPerDegLon * π/180
 *   dy_m  = (yTile - yCentre) * EARTH_RADIUS          ← Mercator
 *
 *   pxX_unrotated = width/2  + dx_m / mapResolution
 *   pxY_unrotated = height/2 - dy_m / mapResolution   ← screen Y inverted
 *
 * Then rotate by mapRotation around the screen centre.
 *
 * ── Redraw trigger ───────────────────────────────────────────────────────────
 *
 * We register an AtakMapView.OnMapMovedListener.  Every time the user pans,
 * zooms, or rotates the map, onMapMoved() fires and we call invalidate().
 * The listener is unregistered in detach() to avoid leaks.
 *
 * ── Thread safety ────────────────────────────────────────────────────────────
 *
 * tile list and alpha are mutated on the main thread only (callers use
 * mapView.post()).  invalidate() is safe from any thread.
 */
public class RadarOverlayView extends View {

    private static final String TAG = "RadarOverlayView";

    // WGS-84 mean radius (metres)
    private static final double EARTH_RADIUS = 6_378_137.0;
    private static final double DEG_TO_RAD   = Math.PI / 180.0;

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

    /** Registered while the view is attached; null otherwise. */
    private MapView.OnMapMovedListener movedListener;

    // ── Constructor ───────────────────────────────────────────────────────────

    public RadarOverlayView(MapView mapView) {
        super(mapView.getContext());
        this.mapView = mapView;
        setWillNotDraw(false);
        setLayoutParams(new MapView.LayoutParams(
                MapView.LayoutParams.MATCH_PARENT,
                MapView.LayoutParams.MATCH_PARENT));
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

    // ── Drawing ───────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        if (tiles.isEmpty()) return;

        final float cx = getWidth()  * 0.5f;
        final float cy = getHeight() * 0.5f;

        // Snapshot map state (all calls are thread-safe on main thread)
        final double centerLat = mapView.getLatitude();
        final double centerLon = mapView.getLongitude();
        final double mpp       = mapView.getMapResolution(); // metres/pixel
        final double rotDeg    = mapView.getMapRotation();   // °CW from north

        if (mpp <= 0) return;

        final double cosRot = Math.cos(-rotDeg * DEG_TO_RAD);
        final double sinRot = Math.sin(-rotDeg * DEG_TO_RAD);

        for (Tile tile : tiles) {
            try {
                float left   = geoToScreenX(tile.nw.getLatitude(), tile.nw.getLongitude(),
                        centerLat, centerLon, mpp, cx, cy, cosRot, sinRot);
                float top    = geoToScreenY(tile.nw.getLatitude(), tile.nw.getLongitude(),
                        centerLat, centerLon, mpp, cx, cy, cosRot, sinRot);
                float right  = geoToScreenX(tile.se.getLatitude(), tile.se.getLongitude(),
                        centerLat, centerLon, mpp, cx, cy, cosRot, sinRot);
                float bottom = geoToScreenY(tile.se.getLatitude(), tile.se.getLongitude(),
                        centerLat, centerLon, mpp, cx, cy, cosRot, sinRot);

                // Normalise (NW corner might map to larger x/y than SE due to rotation)
                float l = Math.min(left,  right);
                float r = Math.max(left,  right);
                float t = Math.min(top,   bottom);
                float b = Math.max(top,   bottom);

                if (r <= l || b <= t) continue;

                paint.setAlpha(tile.alpha);
                canvas.drawBitmap(tile.bitmap, null, new RectF(l, t, r, b), paint);
            } catch (Exception e) {
                Log.w(TAG, "tile draw: " + e.getMessage());
            }
        }
    }

    // ── Mercator geo → screen helpers ─────────────────────────────────────────

    /**
     * Mercator y coordinate (metres north of equator) for a latitude.
     * Uses the standard spherical Mercator formula used by slippy-map tiles.
     */
    private static double mercatorY(double latDeg) {
        double latRad = latDeg * DEG_TO_RAD;
        return EARTH_RADIUS * Math.log(Math.tan(Math.PI / 4.0 + latRad / 2.0));
    }

    /**
     * Convert a GeoPoint to screen X.
     *
     * @param lat      point latitude (°)
     * @param lon      point longitude (°)
     * @param cLat     map centre latitude (°)
     * @param cLon     map centre longitude (°)
     * @param mpp      metres per pixel (mapView.getMapResolution())
     * @param cx       screen centre X (pixels)
     * @param cy       screen centre Y (pixels)
     * @param cosRot   cos(-mapRotation_rad) — pre-computed
     * @param sinRot   sin(-mapRotation_rad) — pre-computed
     */
    private static float geoToScreenX(double lat, double lon,
                                      double cLat, double cLon,
                                      double mpp,
                                      float cx, float cy,
                                      double cosRot, double sinRot) {
        double dx = (lon - cLon) * DEG_TO_RAD
                * EARTH_RADIUS * Math.cos(cLat * DEG_TO_RAD);
        double dy = mercatorY(lat) - mercatorY(cLat);
        double ux = dx / mpp;   // unrotated screen offset X (east = +)
        double uy = -dy / mpp;  // unrotated screen offset Y (south = +)
        return (float)(cx + ux * cosRot - uy * sinRot);
    }

    private static float geoToScreenY(double lat, double lon,
                                      double cLat, double cLon,
                                      double mpp,
                                      float cx, float cy,
                                      double cosRot, double sinRot) {
        double dx = (lon - cLon) * DEG_TO_RAD
                * EARTH_RADIUS * Math.cos(cLat * DEG_TO_RAD);
        double dy = mercatorY(lat) - mercatorY(cLat);
        double ux = dx / mpp;
        double uy = -dy / mpp;
        return (float)(cy + ux * sinRot + uy * cosRot);
    }
}
