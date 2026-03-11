package com.atakmap.android.weather.overlay.wind;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;

import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.weather.domain.model.LocationSnapshot;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.overlay.WindMapOverlay;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;


import java.util.Locale;

/**
 * WindMarkerManager — places a dedicated wind barb marker on the ATAK map.
 *
 * ── Design ────────────────────────────────────────────────────────────────────
 *
 * The wind marker is distinct from the general weather marker:
 *
 *   • It is placed in the same "Weather" MapGroup (shared overlay)
 *   • UID scheme: "wx_wind_<lat4dp>_<lon4dp>" or "wx_wind_self"
 *   • CoT type:   "a-n-G-E-V-w"  (friendly environmental wind observation)
 *   • Icon:       Canvas-drawn wind barb — a meteorological wind barb symbol
 *                 showing speed by half/full barbs, rotated to wind direction
 *   • Callsign:   "WIND · <speed> m/s <cardinalDir>"
 *   • Radial:     Handled by WeatherMenuFactory (uid starts with "wx_wind")
 *                 → "Wind Details" opens the plugin on the Wind tab
 *                 → "Remove" removes this marker
 *
 * ── Wind barb icon ────────────────────────────────────────────────────────────
 *
 * Drawn entirely on Canvas — no asset file needed.
 *
 *   Wind speed → barb count (meteorological convention):
 *     0–2 m/s   : calm circle
 *     3–7 m/s   : 1 half barb   (~5 kt)
 *     8–12 m/s  : 1 full barb   (~10 kt)
 *     13–17 m/s : 1 full + 1 half
 *     18–22 m/s : 2 full barbs  (~20 kt)
 *     23–27 m/s : 2 full + 1 half
 *     28–32 m/s : 3 full barbs
 *     ≥33 m/s   : pennant (50 kt flag)
 *
 * The entire icon is rotated so the barb points FROM the direction the wind
 * is coming from (meteorological convention: a barb pointing to the NE means
 * wind is blowing FROM the NE, i.e. wind direction = 45°).
 *
 * ── Colour coding ─────────────────────────────────────────────────────────────
 *
 *   0–5 m/s   : gray    (calm)
 *   5–10 m/s  : cyan    (light)
 *   10–20 m/s : orange  (moderate)
 *   >20 m/s   : red     (strong)
 */
public class WindMarkerManager {

    private static final String TAG          = "WindMarkerManager";
    public  static final String MARKER_TYPE  = "a-n-G-E-V-w";
    private static final String UID_PREFIX   = "wx_wind";

    private static final int ICON_SIZE = 96;  // px — large enough for clear barbs

    private final MapView           mapView;
    private final Context           pluginContext;
    private final WindMapOverlay     overlay;

    public WindMarkerManager(MapView mapView,
                             Context pluginContext,
                             WindMapOverlay overlay) {
        this.mapView       = mapView;
        this.pluginContext = pluginContext;
        this.overlay       = overlay;
    }

    // ── Overlay accessor (used by WindEffectShape) ───────────────────────────

    public WindMapOverlay getOverlay() { return overlay; }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Place or update a wind marker at the snapshot's position.
     * Thread-safe — posts to the main thread.
     */
    public void placeMarker(LocationSnapshot snapshot, WeatherModel weather) {
        if (snapshot == null || weather == null) return;
        mapView.post(() -> {
            try {
                doPlaceMarker(snapshot, weather);
            } catch (Exception e) {
                Log.e(TAG, "placeMarker failed", e);
            }
        });
    }

    /** Remove the wind marker for this snapshot position. */
    public void removeMarker(String uid) {
        mapView.post(() -> {
            MapGroup group = overlay.getWindGroup();
            if (group == null) return;
            MapItem item = group.deepFindUID(uid);
            if (item != null) item.removeFromGroup();
        });
    }

    /** Remove ALL wind markers (uid starts with "wx_wind"). */
    public void removeAllWindMarkers() {
        mapView.post(() -> {
            MapGroup group = overlay.getWindGroup();
            if (group == null) return;
            // deepFindItems returns a snapshot list — safe to remove while iterating
            // Wind markers store type="a-n-G-E-V-w" — use that to find them all
            java.util.List<com.atakmap.android.maps.MapItem> windItems =
                    group.deepFindItems("type", MARKER_TYPE);
            for (com.atakmap.android.maps.MapItem mi : windItems) {
                mi.removeFromGroup();
            }
        });
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void doPlaceMarker(LocationSnapshot snapshot, WeatherModel weather) {
        MapGroup group = overlay.getWindGroup();
        if (group == null) {
            Log.e(TAG, "Weather group is null");
            return;
        }

        String uid = buildUid(snapshot);

        // Remove existing marker with same UID
        MapItem existing = group.deepFindUID(uid);
        if (existing != null) existing.removeFromGroup();

        GeoPoint point = new GeoPoint(snapshot.getLatitude(), snapshot.getLongitude());
        Marker marker = group.createMarker(point, uid);
        if (marker == null) { Log.e(TAG, "createMarker returned null"); return; }

        float speedMs  = (float) weather.getWindSpeed();
        float dirDeg   = (float) weather.getWindDirection(); // meteorological FROM direction

        // ── Configure marker ─────────────────────────────────────────────────
        marker.setType(MARKER_TYPE);
        marker.setTitle(buildCallsign(speedMs, dirDeg));
        marker.setMetaString("callsign",     buildCallsign(speedMs, dirDeg));
        marker.setMetaString("how",          "m-g");
        marker.setMetaString("wx_source",    snapshot.getSource().name());
        marker.setMetaString("wx_wind_speed", String.valueOf(speedMs));
        marker.setMetaString("wx_wind_dir",   String.valueOf(dirDeg));
        marker.setMetaString("wx_timestamp",  weather.getRequestTimestamp());
        marker.setClickable(true);
        marker.setVisible(true);

        // ── Wind barb icon ────────────────────────────────────────────────────
        // Write the canvas-drawn Bitmap to a temp PNG file, then use
        // IconUtilities.setIcon(Context, Marker, filePath) — the ATAK-confirmed
        // working path for custom bitmap icons in plugins.
        // Base64 / iconUri meta-string is NOT guaranteed across ATAK builds.
        Bitmap barb = drawWindBarb(speedMs, dirDeg);
        setWindBarbIcon(barb, marker);
        barb.recycle();

        marker.persist(mapView.getMapEventDispatcher(), null, getClass());
        Log.d(TAG, "Wind marker placed: uid=" + uid + " speed=" + speedMs + " dir=" + dirDeg);
    }

    // ── Icon drawing ──────────────────────────────────────────────────────────

    /**
     * Write bitmap to a temp PNG file and set it as the marker icon.
     *
     * IconUtilities.setIcon(Context, Marker, drawableResId, adapt) is the
     * resource-ID overload.  For arbitrary bitmaps, write to
     * getCacheDir()/wind_barb_<uid>.png and call the string-path overload:
     *   IconUtilities.setIcon(context, marker, absolutePath, false)
     *
     * If that overload is not available (older ATAK SDK), we fall back to
     * encoding as a Base64 data URI in the "iconUri" meta-string which is
     * checked by ATAK's PointMapItem renderer as a secondary path.
     */
    /**
     * Encode bitmap as a Base64 PNG data-URI and store it in the marker's
     * "iconUri" meta-string.  ATAK's PointMapItem / GLMarker renderer checks
     * this field and uses it as the icon source when no @DrawableRes is set.
     *
     * The ATAK SDK only exposes setIcon(Context, Marker, @DrawableRes int, boolean)
     * — there is no String-path overload — so we bypass IconUtilities entirely
     * and write the encoded bitmap straight to the meta-string.
     */
    private void setWindBarbIcon(Bitmap bmp, Marker marker) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos);
            String b64 = android.util.Base64.encodeToString(
                    baos.toByteArray(), android.util.Base64.NO_WRAP);
            marker.setMetaString("iconUri", "base64://" + b64);
        } catch (Exception e) {
            Log.e(TAG, "setWindBarbIcon failed", e);
        }
    }


    /**
     * Draw a meteorological wind barb icon.
     *
     * Convention: the barb shaft points FROM the wind source direction.
     * A north wind (FROM north, blowing south) points UP (0° rotation).
     * The whole icon is rotated by windDirDeg.
     */
    private Bitmap drawWindBarb(float speedMs, float dirDeg) {
        Bitmap bmp = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        int color = speedColor(speedMs);

        // Calm: just a double circle
        if (speedMs < 2.5f) {
            Paint p = barbPaint(color);
            float cx = ICON_SIZE / 2f, cy = ICON_SIZE / 2f;
            canvas.drawCircle(cx, cy, 12f, p);
            canvas.drawCircle(cx, cy, 6f, p);
            return bmp;
        }

        // Rotate canvas so that shaft points FROM wind direction
        // meteorological: 0° = from North → shaft points upward = -90° canvas rotation
        canvas.save();
        canvas.rotate(dirDeg - 90f, ICON_SIZE / 2f, ICON_SIZE / 2f);

        Paint p = barbPaint(color);
        float cx   = ICON_SIZE / 2f;
        float cy   = ICON_SIZE / 2f;
        float len  = ICON_SIZE * 0.38f;   // shaft half-length
        float barbW = ICON_SIZE * 0.22f;   // barb lateral reach
        float barbStep = ICON_SIZE * 0.09f; // spacing between barbs

        // Draw shaft (upward from centre)
        canvas.drawLine(cx, cy + len * 0.2f, cx, cy - len, p);

        // Draw barbs at the top of the shaft
        int kt = (int) (speedMs * 1.944f);  // m/s → knots (approximate for barb count)
        float y = cy - len;
        int pennants = kt / 50;
        kt %= 50;
        int fullBarbs = kt / 10;
        kt %= 10;
        boolean halfBarb = kt >= 5;

        // Pennants (filled triangle = 50 kt)
        for (int i = 0; i < pennants; i++) {
            Path tri = new Path();
            tri.moveTo(cx, y);
            tri.lineTo(cx + barbW, y + barbStep);
            tri.lineTo(cx, y + barbStep * 2);
            tri.close();
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(color);
            fill.setStyle(Paint.Style.FILL);
            canvas.drawPath(tri, fill);
            y += barbStep * 2;
        }

        // Full barbs
        for (int i = 0; i < fullBarbs; i++) {
            canvas.drawLine(cx, y, cx + barbW, y - barbStep * 0.6f, p);
            y += barbStep;
        }

        // Half barb
        if (halfBarb) {
            canvas.drawLine(cx, y, cx + barbW * 0.5f, y - barbStep * 0.3f, p);
        }

        // Circle at tip
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(color);
        dot.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy + len * 0.2f, 5f, dot);

        canvas.restore();

        // Draw speed label below icon
        Paint txtPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        txtPaint.setColor(Color.WHITE);
        txtPaint.setTextSize(14f);
        txtPaint.setTextAlign(Paint.Align.CENTER);
        txtPaint.setTypeface(Typeface.DEFAULT_BOLD);
        txtPaint.setShadowLayer(2f, 0, 0, Color.BLACK);
        canvas.drawText(String.format(Locale.US, "%.0fm/s", speedMs),
                ICON_SIZE / 2f, ICON_SIZE - 4f, txtPaint);

        return bmp;
    }

    private Paint barbPaint(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3f);
        p.setStrokeCap(Paint.Cap.ROUND);
        return p;
    }

    private int speedColor(float speedMs) {
        if (speedMs < 5f)  return Color.argb(255, 150, 150, 150); // gray  — calm
        if (speedMs < 10f) return Color.argb(255,   0, 220, 220); // cyan  — light
        if (speedMs < 20f) return Color.argb(255, 255, 140,   0); // orange — moderate
        return               Color.argb(255, 255,  60,  60);       // red   — strong
    }

    // ── UID / callsign helpers ─────────────────────────────────────────────────

    public String buildUid(LocationSnapshot snapshot) {
        if (snapshot.getSource() == com.atakmap.android.weather.domain.model.LocationSource.SELF_MARKER)
            return "wx_wind_self";
        return String.format(Locale.US, "wx_wind_%.4f_%.4f",
                snapshot.getLatitude(), snapshot.getLongitude());
    }

    private String buildCallsign(float speedMs, float dirDeg) {
        return String.format(Locale.US, "WIND \u00b7 %.1f m/s %s",
                speedMs, degreesToCardinal(dirDeg));
    }

    private String degreesToCardinal(float deg) {
        String[] dirs = {"N","NNE","NE","ENE","E","ESE","SE","SSE",
                "S","SSW","SW","WSW","W","WNW","NW","NNW"};
        return dirs[Math.round(deg / 22.5f) % 16];
    }
}
