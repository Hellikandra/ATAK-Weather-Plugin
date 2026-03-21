package com.atakmap.android.weather.overlay.radar;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.io.ByteArrayOutputStream;
import java.util.UUID;

/**
 * RadarGroundOverlay — places a geo-registered radar tile on the ATAK map.
 *
 * The plugin API does not expose GLSurface directly.  The compatible approach
 * is to encode the bitmap as a Base64 PNG data URI, create a Marker of type
 * "u-d-r" (raster overlay), and set its "iconUri" meta-string.  ATAK's
 * PointMapItem renderer uses iconUri as the display texture.
 *
 * Geographic extent is communicated via:
 *   overlay_north / overlay_south / overlay_east / overlay_west (degrees)
 *   overlay_alpha (0.0–1.0)
 *
 * All radar tiles share the "Weather Radar" group under the root MapGroup.
 */
public class RadarGroundOverlay {

    private static final String TAG        = "RadarGroundOverlay";
    private static final String GROUP_NAME = "Weather Radar";
    private static final String COT_TYPE   = "u-d-r";

    private final MapView  mapView;
    private final Bitmap   bitmap;
    private       int      alpha;      // 0–255
    private final GeoPoint northWest;
    private final GeoPoint southEast;
    private final String   uid;

    private Marker marker;

    public RadarGroundOverlay(MapView mapView, Bitmap bitmap, int alpha,
                              GeoPoint northWest, GeoPoint southEast) {
        this.mapView   = mapView;
        this.bitmap    = bitmap;
        this.alpha     = alpha;
        this.northWest = northWest;
        this.southEast = southEast;
        this.uid       = "wx_radar_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** Place on map. Must be called on the main thread. */
    public void show() {
        try {
            MapGroup grp = getOrCreateGroup();
            if (grp == null) return;

            double cLat = (northWest.getLatitude()  + southEast.getLatitude())  / 2.0;
            double cLon = (northWest.getLongitude() + southEast.getLongitude()) / 2.0;

            marker = grp.createMarker(new GeoPoint(cLat, cLon), uid);
            if (marker == null) return;

            marker.setType(COT_TYPE);
            marker.setMetaString("how", "m-g");
            marker.setClickable(false);
            marker.setVisible(true);

            marker.setMetaDouble("overlay_north", northWest.getLatitude());
            marker.setMetaDouble("overlay_south", southEast.getLatitude());
            marker.setMetaDouble("overlay_west",  northWest.getLongitude());
            marker.setMetaDouble("overlay_east",  southEast.getLongitude());
            marker.setMetaDouble("overlay_alpha", alpha / 255.0);

            String uri = bitmapToDataUri(bitmap, alpha);
            if (uri != null) marker.setMetaString("iconUri", uri);

        } catch (Exception e) {
            Log.e(TAG, "show() failed", e);
        }
    }

    /** Remove from map. Must be called on the main thread. */
    public void remove() {
        try {
            if (marker != null) { marker.removeFromGroup(); marker = null; }
        } catch (Exception e) {
            Log.w(TAG, "remove(): " + e.getMessage());
        }
    }

    /** Update opacity without recreating the marker. */
    public void setAlpha(int newAlpha) {
        this.alpha = newAlpha;
        if (marker == null) return;
        marker.setMetaDouble("overlay_alpha", newAlpha / 255.0);
        String uri = bitmapToDataUri(bitmap, newAlpha);
        if (uri != null) marker.setMetaString("iconUri", uri);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private MapGroup getOrCreateGroup() {
        MapGroup root = mapView.getRootGroup();
        if (root == null) return null;
        MapGroup g = root.findMapGroup(GROUP_NAME);
        return g != null ? g : root.addGroup(GROUP_NAME);
    }

    private static String bitmapToDataUri(Bitmap src, int alpha) {
        try {
            Bitmap out = src;
            if (alpha < 255) {
                out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(out);
                Paint  p = new Paint();
                p.setAlpha(alpha);
                c.drawBitmap(src, 0, 0, p);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            out.compress(Bitmap.CompressFormat.PNG, 90, baos);
            String b64 = android.util.Base64.encodeToString(
                    baos.toByteArray(), android.util.Base64.NO_WRAP);
            if (out != src) out.recycle();
            return "base64://" + b64;
        } catch (Exception e) {
            Log.e(TAG, "bitmapToDataUri: " + e.getMessage());
            return null;
        }
    }
}
