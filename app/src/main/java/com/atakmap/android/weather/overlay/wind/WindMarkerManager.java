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
    public static final String UID_PREFIX   = "wx_wind";

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

        // ── Wind barb icon ─────────────────────────────────────────────────────
        // Write the composed bitmap (tinted swirl + direction arrow + speed label)
        // to a per-UID cache file and set iconUri to its file:// path.
        //
        // Do NOT call IconUtilities.setIcon() before setting iconUri — ATAK caches
        // the icon on the first set and subsequent iconUri writes are ignored.
        // iconUri alone is the reliable path for per-marker canvas bitmaps.
        Bitmap barb = drawWindBarb(speedMs, dirDeg);
        try {
            java.io.File dir  = pluginContext.getCacheDir();
            java.io.File file = new java.io.File(dir,
                    "wind_barb_" + uid.replace(":", "_") + ".png");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
                barb.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
            }
            marker.setMetaString("iconUri", android.net.Uri.fromFile(file).toString());
        } catch (Exception e) {
            Log.e(TAG, "wind barb icon write failed — falling back to resource icon", e);
            com.atakmap.android.util.IconUtilities.setIcon(
                    pluginContext, marker,
                    com.atakmap.android.weather.plugin.R.drawable.ic_wind_marker,
                    false);
        } finally {
            barb.recycle();
        }

        marker.persist(mapView.getMapEventDispatcher(), null, getClass());
        Log.d(TAG, "Wind marker placed: uid=" + uid + " speed=" + speedMs + " dir=" + dirDeg);
    }

    // ── Icon drawing ──────────────────────────────────────────────────────────

    /**
     * Draw a meteorological wind barb icon as a Bitmap.
     *
     * Design follows the ic_wind_barb.xml SVG reference:
     *   • Shaft: from the centre dot pointing FROM the wind source direction
     *   • Full barb = 10 kt, half barb = 5 kt, pennant = 50 kt
     *   • Station model circle at the shaft foot
     *   • Colour-coded by speed tier (same palette as WindEffectShape cones)
     *   • Shadow layer for map contrast
     *   • Speed label ("x.x m/s") below the icon
     *
     * The whole icon is rotated by {@code dirDeg} so the shaft always points
     * FROM the wind source.  Convention: 0° = from North → shaft upward.
     */
    /**
     * Render the wind marker icon by:
     *  1. Loading {@code ic_wind_marker.png} from the plugin's drawable resources —
     *     this is the user-supplied wind-swirl icon (white silhouette on transparent BG).
     *  2. Colorising every non-transparent pixel with the speed-tier colour via a
     *     PorterDuff ColorFilter.
     *  3. Drawing the speed label below the icon.
     *  4. For wind direction: we do NOT rotate the swirl icon itself (it's a stylised
     *     symbol, not a directional arrow).  Direction is encoded in the title/meta
     *     and displayed in the wind chart rows.  The wind barb approach is kept for
     *     cases where ic_wind_marker.png cannot be loaded.
     */
    private Bitmap drawWindBarb(float speedMs, float dirDeg) {
        final int SZ    = ICON_SIZE;
        int       color = speedColor(speedMs);

        // ── Try to render from the wind-swirl drawable ──────────────────────
        try {
            // Load ic_wind_marker.png via BitmapFactory (raster PNG, no VectorDrawable needed)
            android.content.res.Resources res = pluginContext.getResources();
            int resId = com.atakmap.android.weather.plugin.R.drawable.ic_wind_marker;
            Bitmap src = android.graphics.BitmapFactory.decodeResource(res, resId);
            if (src != null) {
                // Scale to ICON_SIZE if needed
                if (src.getWidth() != SZ || src.getHeight() != SZ) {
                    Bitmap scaled = Bitmap.createScaledBitmap(src, SZ, SZ, true);
                    src.recycle();
                    src = scaled;
                }

                // Tint: create a colour-multiplied version of the icon
                Bitmap tinted = Bitmap.createBitmap(SZ, SZ, Bitmap.Config.ARGB_8888);
                Canvas tc = new Canvas(tinted);
                Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                tintPaint.setColorFilter(new android.graphics.PorterDuffColorFilter(
                        color, android.graphics.PorterDuff.Mode.SRC_IN));
                tc.drawBitmap(src, 0, 0, tintPaint);
                src.recycle();

                // Composite onto final canvas with shadow + arrow + label
                Bitmap bmp = Bitmap.createBitmap(SZ, SZ + 20, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bmp);

                // Shadow pass
                Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                shadowPaint.setAlpha(80);
                canvas.drawBitmap(tinted, 2, 2, shadowPaint);

                // Main icon
                canvas.drawBitmap(tinted, 0, 0, null);
                tinted.recycle();

                // Direction arrow below the swirl
                drawDirectionArrow(canvas, dirDeg, SZ, color);

                drawSpeedLabel(canvas, speedMs, SZ + 20, color);
                return bmp;
            }
        } catch (Exception e) {
            Log.w(TAG, "ic_wind_marker load failed, falling back to canvas barb: " + e.getMessage());
        }

        // ── Fallback: canvas-drawn meteorological barb ───────────────────────
        return drawFallbackBarb(speedMs, dirDeg, color);
    }

    /**
     * Draw a small arrow below the swirl icon pointing FROM the wind source direction.
     * Provides the directional information that the swirl symbol itself doesn't convey.
     */
    private void drawDirectionArrow(Canvas canvas, float dirDeg, int iconSz, int color) {
        final float CX   = iconSz / 2f;
        final float TOP  = iconSz - 18f;
        final float LEN  = 14f;

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.5f);
        p.setStrokeCap(Paint.Cap.ROUND);

        canvas.save();
        canvas.rotate(dirDeg, CX, TOP);
        // Shaft pointing up (FROM North = 0°, rotated by dirDeg)
        canvas.drawLine(CX, TOP + LEN * 0.4f, CX, TOP - LEN * 0.5f, p);
        // Arrowhead
        p.setStyle(Paint.Style.STROKE);
        canvas.drawLine(CX, TOP - LEN * 0.5f, CX - 4f, TOP - LEN * 0.1f, p);
        canvas.drawLine(CX, TOP - LEN * 0.5f, CX + 4f, TOP - LEN * 0.1f, p);
        canvas.restore();
    }

    /**
     * Fallback canvas-drawn meteorological barb (used if ic_wind_marker.png is unavailable).
     */
    private Bitmap drawFallbackBarb(float speedMs, float dirDeg, int color) {
        final int   SZ    = ICON_SIZE;
        final float CX    = SZ / 2f;
        final float CY    = SZ / 2f;
        final float SHAFT = SZ * 0.40f;
        final float BARB  = SZ * 0.24f;
        final float STEP  = SZ * 0.10f;

        Bitmap bmp = Bitmap.createBitmap(SZ, SZ, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(0x55000000);
        shadow.setStyle(Paint.Style.STROKE);
        shadow.setStrokeWidth(4.5f);
        shadow.setStrokeCap(Paint.Cap.ROUND);

        if (speedMs < 2.5f) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3f);
            canvas.drawCircle(CX + 1, CY + 1, 12f, shadow);
            canvas.drawCircle(CX + 1, CY + 1,  6f, shadow);
            canvas.drawCircle(CX, CY, 12f, p);
            canvas.drawCircle(CX, CY,  6f, p);
            drawSpeedLabel(canvas, speedMs, SZ, color);
            return bmp;
        }

        canvas.save();
        canvas.rotate(dirDeg, CX, CY);
        Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(color); linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(3.5f); linePaint.setStrokeCap(Paint.Cap.ROUND);

        float footY = CY + SHAFT * 0.15f;
        float tipY  = CY - SHAFT;
        canvas.drawLine(CX + 1, footY + 1, CX + 1, tipY + 1, shadow);
        canvas.drawLine(CX, footY, CX, tipY, linePaint);

        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(color); dot.setStyle(Paint.Style.FILL);
        canvas.drawCircle(CX + 1, footY + 1, 5.5f, shadow);
        canvas.drawCircle(CX, footY, 5.5f, dot);

        int kt = Math.max(0, Math.round(speedMs * 1.944f));
        float y = tipY;
        int pennants = kt / 50; kt %= 50;
        for (int i = 0; i < pennants; i++) {
            Path tri = new Path();
            tri.moveTo(CX, y); tri.lineTo(CX + BARB, y + STEP * 0.9f);
            tri.lineTo(CX, y + STEP * 1.8f); tri.close();
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(color); fill.setStyle(Paint.Style.FILL);
            canvas.drawPath(tri, fill);
            y += STEP * 2f;
        }
        int fullBarbs = kt / 10; kt %= 10;
        for (int i = 0; i < fullBarbs; i++) {
            canvas.drawLine(CX + 1, y + 1, CX + BARB + 1, y - STEP * 0.55f + 1, shadow);
            canvas.drawLine(CX, y, CX + BARB, y - STEP * 0.55f, linePaint);
            y += STEP;
        }
        if (kt >= 5) {
            canvas.drawLine(CX + 1, y + 1, CX + BARB * 0.5f + 1, y - STEP * 0.28f + 1, shadow);
            canvas.drawLine(CX, y, CX + BARB * 0.5f, y - STEP * 0.28f, linePaint);
        }
        canvas.restore();
        drawSpeedLabel(canvas, speedMs, SZ, color);
        return bmp;
    }

    /** Draw speed label below the icon, white with shadow for map contrast. */
    private void drawSpeedLabel(Canvas canvas, float speedMs, int sz, int color) {
        Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setColor(Color.WHITE);
        txt.setTextSize(15f);
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setTypeface(Typeface.DEFAULT_BOLD);
        txt.setShadowLayer(3f, 1f, 1f, Color.BLACK);
        canvas.drawText(String.format(Locale.US, "%.0fm/s", speedMs),
                sz / 2f, sz - 3f, txt);
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
