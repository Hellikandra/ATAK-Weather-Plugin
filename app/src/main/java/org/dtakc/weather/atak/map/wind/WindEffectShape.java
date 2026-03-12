package org.dtakc.weather.atak.map.wind;

import android.graphics.Color;

import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Polyline;
import com.atakmap.android.maps.SensorFOV;
import com.atakmap.android.maps.Shape;
import org.dtakc.weather.atak.domain.model.WindProfileModel;
import org.dtakc.weather.atak.map.overlay.WindMarkerOverlay;
import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.feature.Feature;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * WindEffectShape — per-altitude wind visualisation.
 *
 * ── Two items per altitude tier ──────────────────────────────────────────────
 *
 * ATAK has two relevant map items and each does exactly one thing well:
 *
 *  ┌─────────────────┬─────────────────────────────────────────────────────┐
 *  │   Item          │  What the GL renderer does                          │
 *  ├─────────────────┼─────────────────────────────────────────────────────┤
 *  │ Polyline        │ 3D extrusion — setHeight() + setHeightStyle()       │
 *  │ (CONE)          │ Extruded triangular prism visible in tilt/3D mode   │
 *  │                 │ GLPolyline implements OnHeightStyleChangedListener   │
 *  ├─────────────────┼─────────────────────────────────────────────────────┤
 *  │ SensorFOV       │ 2D ground footprint — range-line arcs + edge labels │
 *  │ (FOV OVERLAY)   │ GLSensorFOV implements ClampToGroundControl only    │
 *  │                 │ Flat wedge with concentric distance rings            │
 *  └─────────────────┴─────────────────────────────────────────────────────┘
 *
 * Both items share the same azimuth, fov angle, extent, and colour for each
 * tier. Together they give:
 *
 *   In 2D top-down view:
 *     SensorFOV shows the filled wedge + range arcs + bearing labels
 *     Polyline cone is visible as a flat outline on top
 *
 *   In 3D tilt mode:
 *     Polyline cone rises as a solid extruded prism to tier altitude
 *     SensorFOV stays on the ground as the footprint / base
 *     Result: a 3D cone standing above its ground shadow with range rings
 *
 * ── Why SensorFOV can't do 3D ────────────────────────────────────────────────
 *
 * GLSensorFOV only implements ClampToGroundControl — it is always rendered
 * flat. It does NOT implement Polyline.OnHeightStyleChangedListener and
 * ignores setHeight(). Tested: the sensor FOV used alone shows only the
 * 2D arrow/wedge footprint, never a 3D prism.
 *
 * GLPolyline DOES implement MapItem.OnHeightChangedListener and
 * Polyline.OnHeightStyleChangedListener → real 3D extrusion.
 *
 * ── SensorFOV ground overlay ─────────────────────────────────────────────────
 *
 * setMetrics(azimuth°, fov°, extent_m, showLabels, rangeLineSpacing_m)
 *
 *   azimuth       centre direction (downwind = windDir + 180°)
 *   fov           total angular spread = halfAngleForAlt(altM) × 2
 *   extent        rangeM (Range SeekBar)
 *   showLabels    true — prints bearing on each edge arm
 *   rangeLines    rangeM / RANGE_LINE_COUNT — concentric arcs
 *
 * The SensorFOV fill alpha is very low (15) so it doesn't compete with the
 * 3D cone above it. Its stroke and range-line colour match the cone's tier
 * colour so the two items read as one visual unit.
 *
 * ── Colour scale  (matches WindChartView) ────────────────────────────────────
 *
 *   0– 2 m/s  grey    #969696   Calm
 *   2– 5 m/s  sky     #64C8FF   Light
 *   5–10 m/s  green   #00DC00   Moderate
 *  10–15 m/s  amber   #FFD700   Fresh
 *  15–20 m/s  orange  #FF6400   Strong
 *  20–28 m/s  red     #FF0000   Near-gale
 *    >28 m/s  magenta #B400B4   Storm
 *
 * ── FOV half-angle per altitude tier ─────────────────────────────────────────
 *
 *   10 m  → 35°  (wide — turbulent surface boundary layer)
 *   80 m  → 28°
 *  120 m  → 22°
 *  180 m  → 17°
 *  >180 m → 15°  (narrow — laminar upper flow)
 *
 * ── UID scheme ────────────────────────────────────────────────────────────────
 *
 *   3D cone:        wx_wind_cone_<suffix>_<altM>
 *   2D fov overlay: wx_wind_fov_<suffix>_<altM>
 *
 * ── Update paths ─────────────────────────────────────────────────────────────
 *
 *   place()               full redraw (button / hour-seekbar release)
 *   updateHeightCeiling() live setVisible() on both items per tier
 *   updateRange()         live setPoints() on cone + setMetrics() on fov
 */
public class WindEffectShape {

    private static final String TAG = "WindEffectShape";

    private static final double AIR_DENSITY_KG_M3 = 1.225;

    // ── Cone (Polyline 3D prism) ──────────────────────────────────────────────
    private static final int   CONE_FILL_ALPHA   = 50;
    private static final int   CONE_STROKE_ALPHA = 220;
    private static final float CONE_STROKE_W     = 1.8f;

    // ── SensorFOV ground overlay ──────────────────────────────────────────────
    // Very low fill alpha — just a ghost tint on the ground under the 3D cone
    private static final int   FOV_FILL_ALPHA    = 15;
    private static final int   FOV_STROKE_ALPHA  = 180;
    private static final float FOV_STROKE_W      = 1.2f;
    private static final int   RANGE_LINE_ALPHA  = 150;
    private static final float RANGE_LINE_W      = 0.8f;
    private static final int   RANGE_LINE_COUNT  = 4;

    private final MapView        mapView;
    private final WindMarkerOverlay overlay;
    private boolean              active = false;

    public WindEffectShape(MapView mapView, WindMarkerOverlay overlay) {
        this.mapView = mapView;
        this.overlay = overlay;
    }

    public boolean isActive() { return active; }

    // ══════════════════════════════════════════════════════════════════════════
    // Public API — full redraw
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Full redraw: remove all existing items for this suffix and recreate.
     * Each altitude tier gets one 3D Polyline cone + one 2D SensorFOV overlay.
     *
     * @param lat        marker latitude
     * @param lon        marker longitude
     * @param surfaceMs  fallback speed  (used when profiles == null)
     * @param surfaceDir fallback direction
     * @param rangeM     wedge extent in metres         (Range SeekBar)
     * @param heightM    ceiling in metres AGL          (Height SeekBar)
     * @param suffix     UID suffix (from uidSuffix())
     * @param profiles   singletonList of selected hour frame; null = fallback
     */
    public void place(final double lat,       final double lon,
                      final double surfaceMs,  final double surfaceDir,
                      final double rangeM,     final double heightM,
                      final String suffix,
                      final List<WindProfileModel> profiles) {
        mapView.post(() -> {
            try {
                doPlace(lat, lon, surfaceMs, surfaceDir, rangeM, heightM, suffix, profiles);
                active = true;
            } catch (Exception e) {
                Log.e(TAG, "place() failed", e);
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Public API — live mutation (seekbar drag, no remove/re-add)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Live height-ceiling update — called on every Height SeekBar tick.
     * Calls setVisible() on both the cone AND the fov overlay per tier.
     */
    public void updateHeightCeiling(final String suffix,
                                    final double heightM,
                                    final List<WindProfileModel> profiles) {
        if (!active) return;
        mapView.post(() -> {
            MapGroup g = overlay.getWindGroup();
            if (g == null) return;
            List<Integer> keys = altKeysFromProfiles(profiles);
            int N = keys.size();
            double extruH = heightM / (double) Math.max(N, 1);

            for (int i = 0; i < N; i++) {
                int altKey = keys.get(i);
                double baseAlt = (N == 1) ? 0.0
                        : ((double) i / (N - 1)) * heightM;

                MapItem coneItem = g.deepFindUID(coneUid(suffix, altKey));
                if (coneItem instanceof Polyline) {
                    Polyline cone = (Polyline) coneItem;
                    // Reposition all three points at the new baseAlt
                    // (direction/range unchanged — only vertical spacing changes)
                    GeoPoint[] pts = cone.getPoints();
                    if (pts != null && pts.length == 3) {
                        cone.setPoints(new GeoPointMetaData[]{
                                GeoPointMetaData.wrap(new GeoPoint(pts[0].getLatitude(), pts[0].getLongitude(), baseAlt)),
                                GeoPointMetaData.wrap(new GeoPoint(pts[1].getLatitude(), pts[1].getLongitude(), baseAlt)),
                                GeoPointMetaData.wrap(new GeoPoint(pts[2].getLatitude(), pts[2].getLongitude(), baseAlt))
                        });
                        cone.setHeight(extruH);
                    }
                    cone.setVisible(true);
                }
            }
            if (profiles != null && !profiles.isEmpty()) {
                WindProfileModel frame = profiles.get(0);
                refreshTopLabel(g, suffix, frame.getAltitudes(), heightM, frame.getIsoTime());
            }
        });
    }

    /**
     * Live range update — called on every Range SeekBar tick.
     * Updates setPoints() on each cone and setMetrics() on each fov overlay.
     */
    public void updateRange(final String suffix,
                            final double lat, final double lon,
                            final double rangeM,
                            final List<WindProfileModel> profiles) {
        if (!active) return;
        mapView.post(() -> {
            MapGroup g = overlay.getWindGroup();
            if (g == null) return;
            if (profiles == null || profiles.isEmpty()) return;

            WindProfileModel frame = profiles.get(0);
            List<WindProfileModel.AltitudeEntry> tiers = frame.getAltitudes();
            int N = tiers.size();
            // Preserve current height spacing (read from first cone's baseAlt)
            // We approximate by using the existing base altitude of each cone.
            for (int i = 0; i < N; i++) {
                WindProfileModel.AltitudeEntry tier = tiers.get(i);
                double halfAngle = halfAngleForAlt(tier.altitudeMeters);
                double downwind  = (tier.windDirection + 180.0) % 360.0;

                MapItem coneItem = g.deepFindUID(coneUid(suffix, tier.altitudeMeters));
                if (coneItem instanceof Polyline) {
                    Polyline cone = (Polyline) coneItem;
                    // Read current baseAlt from existing apex point
                    GeoPoint[] pts = cone.getPoints();
                    double baseAlt = (pts != null && pts.length > 0)
                            ? pts[0].getAltitude() : 0.0;
                    cone.setPoints(new GeoPointMetaData[]{
                            GeoPointMetaData.wrap(new GeoPoint(lat, lon, baseAlt)),
                            GeoPointMetaData.wrap(offsetBearing(lat, lon, downwind - halfAngle, rangeM, baseAlt)),
                            GeoPointMetaData.wrap(offsetBearing(lat, lon, downwind + halfAngle, rangeM, baseAlt))
                    });
                }

                // Update the one ground FOV (surface tier only)
                if (i == 0) {
                    MapItem fovItem = g.deepFindUID(fovUid(suffix, tier.altitudeMeters));
                    if (fovItem instanceof SensorFOV) {
                        float az  = (float) downwind;
                        float fov = (float)(halfAngle * 2.0);
                        float rl  = (float)(rangeM / RANGE_LINE_COUNT);
                        ((SensorFOV) fovItem).setMetrics(az, fov, (float) rangeM, true, rl);
                    }
                }
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Remove
    // ══════════════════════════════════════════════════════════════════════════

    public void remove(final String suffix) {
        mapView.post(() -> {
            active = false;
            MapGroup g = overlay.getWindGroup();
            if (g != null) removeSuffix(g, suffix);
        });
    }

    public void removeAll() {
        mapView.post(() -> {
            active = false;
            MapGroup g = overlay.getWindGroup();
            if (g == null) return;
            for (MapItem mi : new ArrayList<>(g.deepFindItems("wx_wind_shape", "true")))
                mi.removeFromGroup();
            for (MapItem mi : new ArrayList<>(g.deepFindItems("type", "u-d-f")))
                mi.removeFromGroup();
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Internal — full redraw
    // ══════════════════════════════════════════════════════════════════════════

    private void doPlace(double lat, double lon,
                         double surfaceMs, double surfaceDir,
                         double rangeM, double heightM,
                         String suffix, List<WindProfileModel> profiles) {

        MapGroup g = overlay.getWindGroup();
        if (g == null) { Log.e(TAG, "wind group null"); return; }
        removeSuffix(g, suffix);

        List<WindProfileModel.AltitudeEntry> tiers =
                (profiles != null && !profiles.isEmpty())
                        ? profiles.get(0).getAltitudes()
                        : null;

        if (tiers == null || tiers.isEmpty()) {
            // Fallback: single cone at ground level
            placeConeStacked(g, lat, lon, surfaceMs, surfaceDir,
                    rangeM, 0.0, heightM, suffix, 10);
            return;
        }

        // ── Staggered-altitude cones ──────────────────────────────────────────
        // Each cone gets its own base altitude so that raising the Height
        // SeekBar spreads them apart vertically. The lowest data altitude
        // (e.g. 10 m) is always at the bottom; the highest (e.g. 180 m) at
        // the top. Each slab is the same height = heightM / N.
        //
        //   baseAlt[i] = (i / (N-1)) * heightM   (0 for bottom, heightM for top)
        //   extruH     = heightM / N
        //
        // This means:
        //   • Moving the Height SeekBar up → cones spread further apart
        //   • Moving it down → cones compress together (still stacked, not merged)
        //   • Width + position together identify which altitude tier at a glance

        int N = tiers.size();
        double extruH = heightM / (double) N;

        String isoTime = profiles.get(0).getIsoTime();

        for (int i = 0; i < N; i++) {
            WindProfileModel.AltitudeEntry tier = tiers.get(i);
            double baseAlt = (N == 1) ? 0.0
                    : ((double) i / (N - 1)) * heightM;
            placeConeStacked(g, lat, lon,
                    tier.windSpeed, tier.windDirection,
                    rangeM, baseAlt, extruH, suffix, tier.altitudeMeters);
        }

        placeFovGround(g, lat, lon, tiers.get(0), rangeM, suffix);
        refreshTopLabel(g, suffix, tiers, heightM, isoTime);

        Log.d(TAG, String.format(Locale.US,
                "placed %d stacked cone(s), extruH=%.0fm, suffix=%s",
                N, extruH, suffix));
    }

    // ── Stacked cone: one 3D cone per altitude tier ──────────────────────────
    //
    //   baseAlt   = where this cone's apex and arms sit in 3D (HAE metres)
    //   extruH    = how tall this cone extrudes above baseAlt (= heightM / N)
    //   altKey    = the meteorological altitude this tier represents (10/80/120/180 m)
    //               → drives half-angle and colour only, NOT the 3D position

    private void placeConeStacked(MapGroup g,
                                  double lat, double lon,
                                  double speedMs, double dirDeg,
                                  double rangeM,
                                  double baseAlt, double extruH,
                                  String suffix, int altKey) {

        int    rgb = speedColor(speedMs);
        int    cr  = Color.red(rgb), cg = Color.green(rgb), cb = Color.blue(rgb);

        placeCone(g, lat, lon, speedMs, dirDeg, rangeM, baseAlt, extruH,
                suffix, altKey, cr, cg, cb, true);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3D Polyline cone
    // ══════════════════════════════════════════════════════════════════════════

    private void placeCone(MapGroup g,
                           double lat, double lon,
                           double speedMs, double dirDeg,
                           double rangeM,
                           double baseAlt, double extruH,
                           String suffix, int altKey,
                           int cr, int cg, int cb,
                           boolean visible) {

        double halfAngle = halfAngleForAlt(altKey);
        double downwind  = (dirDeg + 180.0) % 360.0;

        // All three points sit at baseAlt HAE so the cone floats at the
        // correct staggered altitude. extruH extrudes upward from baseAlt.
        GeoPoint apex  = new GeoPoint(lat, lon, baseAlt);
        GeoPoint left  = offsetBearing(lat, lon, downwind - halfAngle, rangeM, baseAlt);
        GeoPoint right = offsetBearing(lat, lon, downwind + halfAngle, rangeM, baseAlt);

        Polyline cone = new Polyline(coneUid(suffix, altKey));
        cone.setPoints(new GeoPointMetaData[]{
                GeoPointMetaData.wrap(apex),
                GeoPointMetaData.wrap(left),
                GeoPointMetaData.wrap(right)
        });
        cone.setStyle(Polyline.STYLE_CLOSED_MASK
                | Shape.STYLE_STROKE_MASK
                | Shape.STYLE_FILLED_MASK);
        cone.setFillColor  (Color.argb(CONE_FILL_ALPHA,   cr, cg, cb));
        cone.setStrokeColor(Color.argb(CONE_STROKE_ALPHA, cr, cg, cb));
        cone.setStrokeWeight(CONE_STROKE_W);

        // Cone floats at baseAlt and extrudes upward by extruH.
        // HEIGHT_EXTRUDE_MIN_ALT takes the minimum altitude among the points
        // (all equal = baseAlt) as the extrusion base → wall rises to baseAlt+extruH.
        cone.setHeight(extruH);
        cone.setHeightStyle(Polyline.HEIGHT_STYLE_POLYGON | Polyline.HEIGHT_STYLE_OUTLINE);
        cone.setHeightExtrudeMode(Polyline.HEIGHT_EXTRUDE_MIN_ALT);
        cone.setAltitudeMode(Feature.AltitudeMode.Absolute);

        cone.setType("u-d-f");
        cone.setMetaString("how",           "m-g");
        cone.setMetaString("wx_wind_shape",  "true");
        cone.setMetaString("wx_alt_m",      String.valueOf(altKey));
        cone.setTitle(String.format(Locale.US,
                "%dm — %.1f m/s  %.0f°  [%s]",
                altKey, speedMs, dirDeg, speedLabel(speedMs)));
        cone.setMetaString("callsign",
                String.format(Locale.US, "%dm %.1fm/s", altKey, speedMs));
        cone.setClickable(true);
        cone.setVisible(visible);

        g.addItem(cone);
        cone.persist(mapView.getMapEventDispatcher(), null, getClass());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2D SensorFOV — single ground ring for the lowest (surface) tier only
    // Shows range arcs and bearing label for orientation reference.
    // ══════════════════════════════════════════════════════════════════════════

    private void placeFovGround(MapGroup g,
                                double lat, double lon,
                                WindProfileModel.AltitudeEntry surfaceTier,
                                double rangeM, String suffix) {

        float azimuth   = (float)((surfaceTier.windDirection + 180.0) % 360.0);
        float fovDeg    = (float)(halfAngleForAlt(surfaceTier.altitudeMeters) * 2.0);
        float rlSpacing = (float)(rangeM / RANGE_LINE_COUNT);

        int  rgb = speedColor(surfaceTier.windSpeed);
        int  cr = Color.red(rgb), cg = Color.green(rgb), cb = Color.blue(rgb);

        SensorFOV fov = new SensorFOV(fovUid(suffix, surfaceTier.altitudeMeters));
        fov.setPoint(GeoPointMetaData.wrap(new GeoPoint(lat, lon, 0.0)));
        fov.setMetrics(azimuth, fovDeg, (float) rangeM, true, rlSpacing);
        fov.setFillColor(Color.argb(FOV_FILL_ALPHA, cr, cg, cb));
        fov.setStrokeColor(Color.argb(FOV_STROKE_ALPHA, cr, cg, cb));
        fov.setStrokeWeight(FOV_STROKE_W);
        fov.setRangeLineStrokeColor(Color.argb(RANGE_LINE_ALPHA, cr, cg, cb));
        fov.setRangeLineStrokeWeight(RANGE_LINE_W);

        fov.setType("u-d-f");
        fov.setMetaString("how",          "m-g");
        fov.setMetaString("wx_wind_shape", "true");
        fov.setTitle("Surface wind  " + surfaceTier.altitudeMeters + "m");
        fov.setClickable(false);
        fov.setVisible(true);

        g.addItem(fov);
        fov.persist(mapView.getMapEventDispatcher(), null, getClass());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Summary label on topmost visible cone
    // ══════════════════════════════════════════════════════════════════════════

    private void refreshTopLabel(MapGroup g, String suffix,
                                 List<WindProfileModel.AltitudeEntry> tiers,
                                 double heightM, String isoTime) {
        if (tiers == null || tiers.isEmpty()) return;

        int    topAlt = 0;
        double peakMs = 0;
        int    peakAlt = 10;
        StringBuilder lines = new StringBuilder();

        List<WindProfileModel.AltitudeEntry> visible = new ArrayList<>();
        for (WindProfileModel.AltitudeEntry t : tiers)
            if (t.altitudeMeters <= heightM) visible.add(t);

        for (int i = visible.size() - 1; i >= 0; i--) {
            WindProfileModel.AltitudeEntry t = visible.get(i);
            lines.append(String.format(Locale.US,
                    "\u2191%3dm  %4.1fm/s  %.0f\u00b0  %s\n",
                    t.altitudeMeters, t.windSpeed, t.windDirection,
                    speedLabel(t.windSpeed)));
            if (t.altitudeMeters > topAlt)   topAlt = t.altitudeMeters;
            if (t.windSpeed      > peakMs) { peakMs = t.windSpeed; peakAlt = t.altitudeMeters; }
        }

        // Clear labels on all cones first
        for (WindProfileModel.AltitudeEntry t : tiers) {
            MapItem mi = g.deepFindUID(coneUid(suffix, t.altitudeMeters));
            if (mi instanceof Polyline) ((Polyline) mi).setLineLabel("");
        }
        if (topAlt == 0) return;

        MapItem item = g.deepFindUID(coneUid(suffix, topAlt));
        if (!(item instanceof Polyline)) return;

        double wpd = 0.5 * AIR_DENSITY_KG_M3 * peakMs * peakMs * peakMs;
        double q   = 0.5 * AIR_DENSITY_KG_M3 * peakMs * peakMs;

        ((Polyline) item).setLineLabel(
                "WIND PROFILE  " + isoTime + "\n"
                        + lines
                        + String.format(Locale.US,
                        "Peak \u2191%dm %.1fm/s  WPD %.0fW/m\u00b2  q %.0fPa",
                        peakAlt, peakMs, wpd, q));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Geometry
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Flat-earth bearing offset. Returns GeoPoint at the given altHAE.
     * The altitude must be explicitly provided — GeoPoint(lat,lon) alone
     * gives INVALID altitude (-10000) which breaks GL extrusion.
     * Pass baseAlt so all three cone points share the same staggered altitude.
     */
    private static GeoPoint offsetBearing(double lat, double lon,
                                          double bearingDeg, double distM,
                                          double altHAE) {
        double R   = 6_371_000.0;
        double rad = Math.toRadians(bearingDeg);
        return new GeoPoint(
                lat + Math.toDegrees(distM / R * Math.cos(rad)),
                lon + Math.toDegrees(distM / R * Math.sin(rad)
                        / Math.cos(Math.toRadians(lat))),
                altHAE);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Half-angle table
    // ══════════════════════════════════════════════════════════════════════════

    private static double halfAngleForAlt(int altM) {
        if (altM <=  10) return 35.0;
        if (altM <=  80) return 28.0;
        if (altM <= 120) return 22.0;
        if (altM <= 180) return 17.0;
        return 15.0;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Colour
    // ══════════════════════════════════════════════════════════════════════════

    static int speedColor(double ms) {
        if (ms <  2) return Color.parseColor("#969696");
        if (ms <  5) return Color.parseColor("#64C8FF");
        if (ms < 10) return Color.parseColor("#00DC00");
        if (ms < 15) return Color.parseColor("#FFD700");
        if (ms < 20) return Color.parseColor("#FF6400");
        if (ms < 28) return Color.parseColor("#FF0000");
        return               Color.parseColor("#B400B4");
    }

    private static String speedLabel(double ms) {
        if (ms <  2) return "Calm";
        if (ms <  5) return "Light";
        if (ms < 10) return "Moderate";
        if (ms < 15) return "Fresh";
        if (ms < 20) return "Strong";
        if (ms < 28) return "Near-gale";
        return "Storm";
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UID / cleanup
    // ══════════════════════════════════════════════════════════════════════════

    private static List<Integer> altKeysFromProfiles(List<WindProfileModel> profiles) {
        List<Integer> keys = new ArrayList<>();
        if (profiles == null || profiles.isEmpty()) { keys.add(10); return keys; }
        for (WindProfileModel.AltitudeEntry e : profiles.get(0).getAltitudes())
            keys.add(e.altitudeMeters);
        return keys;
    }

    private static String coneUid(String s, int a) { return "wx_wind_cone_" + s + "_" + a; }
    private static String fovUid (String s, int a) { return "wx_wind_fov_"  + s + "_" + a; }

    private static void removeSuffix(MapGroup g, String suffix) {
        int[] knownAlts = {10, 20, 50, 80, 100, 120, 150, 180, 200, 250, 300, 500};
        for (int a : knownAlts) {
            removeByUid(g, coneUid(suffix, a));
            removeByUid(g, fovUid (suffix, a));
        }
        // Legacy UIDs from earlier sprints
        for (int a : knownAlts) removeByUid(g, "wx_wind_pillar_" + suffix + "_" + a);
        for (String ln : new String[]{"near", "mid", "far"})
            removeByUid(g, "wx_wind_prism_" + suffix + "_" + ln);
        removeByUid(g, "wx_wind_prism_" + suffix);
        removeByUid(g, "wx_wind_box_"   + suffix);
        // Tag sweep for any arbitrary-altitude tiers
        for (MapItem mi : new ArrayList<>(g.deepFindItems("wx_wind_shape", "true"))) {
            String uid = mi.getUID();
            if (uid != null && uid.contains("_" + suffix + "_"))
                mi.removeFromGroup();
        }
    }

    private static void removeByUid(MapGroup g, String uid) {
        MapItem mi = g.deepFindUID(uid);
        if (mi != null) mi.removeFromGroup();
    }

    public static String uidSuffix(double lat, double lon, boolean isSelf) {
        return isSelf ? "self"
                : String.format(Locale.US, "%.4f_%.4f", lat, lon);
    }
}
