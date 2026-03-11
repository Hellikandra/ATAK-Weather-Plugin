package com.atakmap.android.weather.overlay.wind;

import android.graphics.Color;

import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Polyline;
import com.atakmap.android.maps.SensorFOV;
import com.atakmap.android.maps.Shape;
import com.atakmap.android.weather.domain.model.WindProfileModel;
import com.atakmap.android.weather.overlay.WindMapOverlay;
import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.feature.Feature;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * WindColumnShape — single continuous 3D wind column.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * CONCEPT
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * One visual object that spans from minAltitude (lowest tier, e.g. 10 m) to
 * maxAltitude (highest tier, e.g. 180 m).  At each identified altitude the
 * wind direction and speed can be different — wind shear is directly visible
 * as the column twists between levels.
 *
 * The column is built as N−1 wall quad panels, one per adjacent tier pair:
 *
 *   Tiers:  10 m → 80 m → 120 m → 180 m
 *   Panels:    P0      P1       P2
 *
 * Each panel is a closed quadrilateral Polyline with four corner GeoPoints:
 *
 *   bottom-left  = GeoPoint(lat + offset_left(tier_bottom),  alt = bottomAlt)
 *   bottom-right = GeoPoint(lat + offset_right(tier_bottom), alt = bottomAlt)
 *   top-right    = GeoPoint(lat + offset_right(tier_top),    alt = topAlt)
 *   top-left     = GeoPoint(lat + offset_left(tier_top),     alt = topAlt)
 *
 * Because top and bottom tiers can point in different compass directions, the
 * panel TWISTS in 3D space — that twist encodes the wind shear.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * ATAK 3D RENDERING — HEIGHT_EXTRUDE_PER_POINT
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * The key Polyline constant is:
 *
 *   HEIGHT_EXTRUDE_PER_POINT — "Extrude off each point's elevation"
 *
 * With setHeightExtrudeMode(HEIGHT_EXTRUDE_PER_POINT) and GeoPoint(lat, lon, altHAE):
 *   • Each point's altitude value IS its 3D position in space
 *   • setHeight(wallHeight) adds a wall of that thickness ABOVE each point
 *   • setAltitudeMode(AltitudeMode.Absolute) disables terrain-clamping
 *
 * For our quad panels:
 *   • Bottom points at tier_bottom altitude (e.g. 10 m HAE)
 *   • Top points    at tier_top    altitude (e.g. 80 m HAE)
 *   • setHeight(tier_top − tier_bottom) so the wall exactly bridges the gap
 *
 * This produces a solid filled quad face at the correct real-world altitude,
 * tilted and twisted according to wind direction at each tier.
 *
 * GLPolyline implements:
 *   MapItem.OnHeightChangedListener           → responds to setHeight()
 *   Polyline.OnHeightStyleChangedListener     → responds to setHeightStyle()
 *   MapItem.OnAltitudeModeChangedListener     → responds to AltitudeMode
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * VISUAL RESULT IN ATAK 3D TILT MODE
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *              ╔════════════╗  ← 180 m  narrow, e.g. NE (17° half-angle)
 *              ║ magenta    ║
 *            ╔══════════════╗  ← 120 m
 *            ║  red/amber   ║      (panel twists if direction changes)
 *          ╔══════════════════╗  ← 80 m
 *          ║    green/sky     ║
 *        ╔══════════════════════╗  ← 10 m  wide, e.g. SE (35° half-angle)
 *        ╚══════════════════════╝  ← ground (SensorFOV range rings here)
 *
 * In 2D top-down view: the stacked wedges show the column footprint and
 * the SensorFOV ground overlay draws range arcs + bearing labels.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * HEIGHT SEEKBAR — vertical scale multiplier
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Real data altitudes are 10–180 m.  The Height SeekBar (50–2000 m) acts as
 * a VERTICAL SCALE — it multiplies all tier altitudes so the column becomes
 * taller and easier to see in 3D tilt:
 *
 *   scaledAlt = tierAlt * (heightSeekM / maxTierAlt)
 *
 * e.g. with maxTierAlt=180 m and heightSeekM=900 m → ×5 scale:
 *   10 m → 50 m,  80 m → 400 m,  120 m → 600 m,  180 m → 900 m
 *
 * This preserves the relative proportions of the real data while making the
 * column visible without needing extreme map tilt.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * FOV HALF-ANGLE PER ALTITUDE TIER
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *   10 m  → 35°   turbulent surface boundary layer
 *   80 m  → 28°
 *  120 m  → 22°
 *  180 m  → 17°
 *  >180 m → 15°   narrow laminar upper flow
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * COLOUR SCALE  (matches WindChartView)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *   0– 2 m/s  grey    #969696   Calm
 *   2– 5 m/s  sky     #64C8FF   Light
 *   5–10 m/s  green   #00DC00   Moderate
 *  10–15 m/s  amber   #FFD700   Fresh
 *  15–20 m/s  orange  #FF6400   Strong
 *  20–28 m/s  red     #FF0000   Near-gale
 *    >28 m/s  magenta #B400B4   Storm
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * UID SCHEME
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *   Wall panel:     wx_col_wall_<suffix>_<bottomAlt>_<topAlt>
 *   FOV ground:     wx_col_fov_<suffix>_<altM>
 *   Summary label:  wx_col_label_<suffix>
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * UPDATE PATHS
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *   place()        full redraw — button press, hour seekbar change
 *   updateRange()  live setPoints() on all walls (no remove/re-add)
 *   updateScale()  live setPoints() on all walls with new vertical scale
 *
 * The legacy WindEffectShape public interface (place/updateHeightCeiling/
 * updateRange/remove/removeAll/uidSuffix/isActive) is preserved so the
 * WeatherDropDownReceiver needs no changes beyond the class name swap.
 */
public class WindColumnShape {

    private static final String TAG = "WindColumnShape";

    private static final double AIR_DENSITY_KG_M3 = 1.225;

    // ── Wall panel rendering ──────────────────────────────────────────────────
    private static final int   WALL_FILL_ALPHA   = 80;   // translucent solid face
    private static final int   WALL_STROKE_ALPHA = 230;
    private static final float WALL_STROKE_W     = 1.5f;

    // ── SensorFOV ground overlay ──────────────────────────────────────────────
    private static final int   FOV_FILL_ALPHA   = 12;
    private static final int   FOV_STROKE_ALPHA = 160;
    private static final float FOV_STROKE_W     = 1.0f;
    private static final int   RANGE_LINE_ALPHA = 130;
    private static final float RANGE_LINE_W     = 0.7f;
    private static final int   RANGE_LINE_COUNT = 4;

    private final MapView        mapView;
    private final WindMapOverlay overlay;
    private boolean              active = false;

    // ── Last-placed state for live updates ───────────────────────────────────
    private double lastLat, lastLon, lastRangeM, lastHeightM;
    private List<WindProfileModel> lastProfiles;
    private String lastSuffix;

    public WindColumnShape(MapView mapView, WindMapOverlay overlay) {
        this.mapView = mapView;
        this.overlay = overlay;
    }

    public boolean isActive() { return active; }

    // ══════════════════════════════════════════════════════════════════════════
    // Public API — full redraw
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Full redraw: remove all existing items and recreate the column.
     *
     * @param lat        marker latitude
     * @param lon        marker longitude
     * @param surfaceMs  fallback speed (used when profiles == null)
     * @param surfaceDir fallback direction
     * @param rangeM     wedge horizontal extent in metres
     * @param heightM    Height SeekBar value — vertical scale ceiling
     * @param suffix     UID suffix from {@link #uidSuffix}
     * @param profiles   singletonList of the selected hour frame; null = fallback
     */
    public void place(final double lat, final double lon,
                      final double surfaceMs, final double surfaceDir,
                      final double rangeM, final double heightM,
                      final String suffix,
                      final List<WindProfileModel> profiles) {
        // Cache for live updates
        lastLat = lat; lastLon = lon;
        lastRangeM = rangeM; lastHeightM = heightM;
        lastProfiles = profiles;
        lastSuffix = suffix;

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
    // Public API — live mutations (seekbar drag, no remove/re-add)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Legacy name kept so WeatherDropDownReceiver compiles unchanged.
     * Equivalent to updateScale() — the height seekbar now controls
     * vertical scale, not a visibility ceiling.
     */
    public void updateHeightCeiling(final String suffix,
                                    final double heightM,
                                    final List<WindProfileModel> profiles) {
        if (!active) return;
        lastHeightM = heightM;
        lastProfiles = profiles;
        mapView.post(() -> mutateWallPoints(suffix, lastLat, lastLon, lastRangeM, heightM, profiles));
    }

    /**
     * Live range update — called on every Range SeekBar tick.
     */
    public void updateRange(final String suffix,
                            final double lat, final double lon,
                            final double rangeM,
                            final List<WindProfileModel> profiles) {
        if (!active) return;
        lastRangeM = rangeM;
        mapView.post(() -> mutateWallPoints(suffix, lat, lon, rangeM, lastHeightM, profiles));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Public API — remove
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
            // Remove by tag
            for (MapItem mi : new ArrayList<>(g.deepFindItems("wx_wind_col", "true")))
                mi.removeFromGroup();
            // Legacy cone/fov UIDs from WindEffectShape
            for (MapItem mi : new ArrayList<>(g.deepFindItems("wx_wind_shape", "true")))
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

        List<WindProfileModel.AltitudeEntry> tiers = resolveTiers(
                profiles, surfaceMs, surfaceDir);
        if (tiers.isEmpty()) return;

        double maxTierAlt = tiers.get(tiers.size() - 1).altitudeMeters;
        double scale      = (maxTierAlt > 0) ? heightM / maxTierAlt : 1.0;

        // ── Build N−1 wall panels ─────────────────────────────────────────────
        for (int i = 0; i < tiers.size() - 1; i++) {
            WindProfileModel.AltitudeEntry bottom = tiers.get(i);
            WindProfileModel.AltitudeEntry top    = tiers.get(i + 1);
            placeWall(g, lat, lon, bottom, top, rangeM, scale, suffix);
        }

        // ── SensorFOV ground overlay for each tier ────────────────────────────
        for (WindProfileModel.AltitudeEntry tier : tiers) {
            placeFov(g, lat, lon, tier, rangeM, suffix);
        }

        // ── Summary label on topmost wall ─────────────────────────────────────
        placeLabel(g, lat, lon, tiers, rangeM, scale, suffix,
                profiles != null && !profiles.isEmpty() ? profiles.get(0).getIsoTime() : "");

        Log.d(TAG, String.format(Locale.US,
                "column placed: %d wall(s), scale=×%.1f, suffix=%s",
                tiers.size() - 1, scale, suffix));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Wall quad panel
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Build one closed quad panel between two adjacent altitude tiers.
     *
     * The four corners (in order for STYLE_CLOSED_MASK):
     *   [0] bottom-left  (at bottomAlt, left arm of bottom tier)
     *   [1] bottom-right (at bottomAlt, right arm of bottom tier)
     *   [2] top-right    (at topAlt,    right arm of top tier)
     *   [3] top-left     (at topAlt,    left arm of top tier)
     *
     * Bottom and top tiers can point in different compass directions,
     * so the quad TWISTS — that twist is the wind shear visible in 3D.
     *
     * HEIGHT_EXTRUDE_PER_POINT:
     *   Each GeoPoint's altitude HAE = its real position in 3D space.
     *   setHeight(topAlt − bottomAlt) = wall thickness above each point,
     *   bridging the gap to the next tier's base.
     */
    private void placeWall(MapGroup g,
                           double lat, double lon,
                           WindProfileModel.AltitudeEntry bottom,
                           WindProfileModel.AltitudeEntry top,
                           double rangeM, double scale,
                           String suffix) {

        double bottomAlt = bottom.altitudeMeters * scale;
        double topAlt    = top.altitudeMeters    * scale;
        double wallH     = topAlt - bottomAlt;

        // Half-angles for each tier
        double halfB = halfAngleForAlt(bottom.altitudeMeters);
        double halfT = halfAngleForAlt(top.altitudeMeters);

        // Downwind direction for each tier
        double downB = (bottom.windDirection + 180.0) % 360.0;
        double downT = (top.windDirection    + 180.0) % 360.0;

        // Four corners
        GeoPoint bLeft  = offsetBearing(lat, lon, downB - halfB, rangeM, bottomAlt);
        GeoPoint bRight = offsetBearing(lat, lon, downB + halfB, rangeM, bottomAlt);
        GeoPoint tRight = offsetBearing(lat, lon, downT + halfT, rangeM, topAlt);
        GeoPoint tLeft  = offsetBearing(lat, lon, downT - halfT, rangeM, topAlt);

        // Colour = average speed of the two tiers
        double avgSpeed = (bottom.windSpeed + top.windSpeed) / 2.0;
        int    rgb      = speedColor(avgSpeed);
        int    cr = Color.red(rgb), cg = Color.green(rgb), cb = Color.blue(rgb);

        String uid = wallUid(suffix, bottom.altitudeMeters, top.altitudeMeters);
        Polyline wall = new Polyline(uid);
        wall.setPoints(new GeoPointMetaData[]{
                GeoPointMetaData.wrap(bLeft),
                GeoPointMetaData.wrap(bRight),
                GeoPointMetaData.wrap(tRight),
                GeoPointMetaData.wrap(tLeft)
        });
        wall.setStyle(Polyline.STYLE_CLOSED_MASK
                | Shape.STYLE_STROKE_MASK
                | Shape.STYLE_FILLED_MASK);
        wall.setFillColor  (Color.argb(WALL_FILL_ALPHA,   cr, cg, cb));
        wall.setStrokeColor(Color.argb(WALL_STROKE_ALPHA, cr, cg, cb));
        wall.setStrokeWeight(WALL_STROKE_W);

        // 3D per-point extrusion: each corner sits at its HAE altitude,
        // and the wall height bridges to the next tier.
        wall.setHeightExtrudeMode(Polyline.HEIGHT_EXTRUDE_PER_POINT);
        wall.setHeight(wallH);
        wall.setHeightStyle(Polyline.HEIGHT_STYLE_POLYGON | Polyline.HEIGHT_STYLE_OUTLINE);
        wall.setAltitudeMode(Feature.AltitudeMode.Absolute);

        wall.setType("u-d-f");
        wall.setMetaString("how",          "m-g");
        wall.setMetaString("wx_wind_col",  "true");
        wall.setTitle(String.format(Locale.US,
                "%dm–%dm  %.1f→%.1f m/s  %.0f°→%.0f°",
                bottom.altitudeMeters, top.altitudeMeters,
                bottom.windSpeed, top.windSpeed,
                bottom.windDirection, top.windDirection));
        wall.setMetaString("callsign", String.format(Locale.US,
                "%d–%dm %.1fm/s", bottom.altitudeMeters, top.altitudeMeters, avgSpeed));
        wall.setClickable(true);
        wall.setVisible(true);

        g.addItem(wall);
        wall.persist(mapView.getMapEventDispatcher(), null, getClass());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SensorFOV ground overlay  (2D range rings, flat on terrain)
    // ══════════════════════════════════════════════════════════════════════════

    private void placeFov(MapGroup g,
                          double lat, double lon,
                          WindProfileModel.AltitudeEntry tier,
                          double rangeM, String suffix) {

        float azimuth   = (float)((tier.windDirection + 180.0) % 360.0);
        float fovDeg    = (float)(halfAngleForAlt(tier.altitudeMeters) * 2.0);
        float rlSpacing = (float)(rangeM / RANGE_LINE_COUNT);

        int  rgb = speedColor(tier.windSpeed);
        int  cr = Color.red(rgb), cg = Color.green(rgb), cb = Color.blue(rgb);

        SensorFOV fov = new SensorFOV(fovUid(suffix, tier.altitudeMeters));
        fov.setPoint(GeoPointMetaData.wrap(new GeoPoint(lat, lon, 0.0)));
        fov.setMetrics(azimuth, fovDeg, (float) rangeM, true, rlSpacing);
        fov.setFillColor(Color.argb(FOV_FILL_ALPHA, cr, cg, cb));
        fov.setStrokeColor(Color.argb(FOV_STROKE_ALPHA, cr, cg, cb));
        fov.setStrokeWeight(FOV_STROKE_W);
        fov.setRangeLineStrokeColor(Color.argb(RANGE_LINE_ALPHA, cr, cg, cb));
        fov.setRangeLineStrokeWeight(RANGE_LINE_W);

        fov.setType("u-d-f");
        fov.setMetaString("how",         "m-g");
        fov.setMetaString("wx_wind_col", "true");
        fov.setTitle(String.format(Locale.US,
                "%dm ground  %.0f° %.1fm/s", tier.altitudeMeters,
                tier.windDirection, tier.windSpeed));
        fov.setClickable(false);
        fov.setVisible(true);

        g.addItem(fov);
        fov.persist(mapView.getMapEventDispatcher(), null, getClass());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Summary label Polyline (invisible line, just the label text)
    // ══════════════════════════════════════════════════════════════════════════

    private void placeLabel(MapGroup g,
                            double lat, double lon,
                            List<WindProfileModel.AltitudeEntry> tiers,
                            double rangeM, double scale,
                            String suffix, String isoTime) {
        if (tiers.isEmpty()) return;

        WindProfileModel.AltitudeEntry top = tiers.get(tiers.size() - 1);
        double topAltScaled = top.altitudeMeters * scale;

        // Small stub line at apex of topmost tier for the label anchor
        GeoPoint apex = offsetBearing(lat, lon,
                (top.windDirection + 180.0) % 360.0, rangeM * 0.01, topAltScaled);
        GeoPoint base = new GeoPoint(lat, lon, topAltScaled);

        // Build label text: one line per tier, bottom to top
        StringBuilder sb = new StringBuilder("WIND COLUMN  ").append(isoTime).append("\n");
        double peakMs = 0; int peakAlt = 0;
        for (WindProfileModel.AltitudeEntry t : tiers) {
            sb.append(String.format(Locale.US,
                    "\u2191%3dm  %4.1fm/s  %.0f\u00b0  %s\n",
                    t.altitudeMeters, t.windSpeed, t.windDirection,
                    speedLabel(t.windSpeed)));
            if (t.windSpeed > peakMs) { peakMs = t.windSpeed; peakAlt = t.altitudeMeters; }
        }
        double wpd = 0.5 * AIR_DENSITY_KG_M3 * peakMs * peakMs * peakMs;
        double q   = 0.5 * AIR_DENSITY_KG_M3 * peakMs * peakMs;
        sb.append(String.format(Locale.US,
                "Peak \u2191%dm %.1fm/s  WPD %.0fW/m\u00b2  q %.0fPa",
                peakAlt, peakMs, wpd, q));

        Polyline label = new Polyline(labelUid(suffix));
        label.setPoints(new GeoPointMetaData[]{
                GeoPointMetaData.wrap(base),
                GeoPointMetaData.wrap(apex)
        });
        label.setStyle(Shape.STYLE_STROKE_MASK);
        label.setStrokeColor(Color.argb(0, 0, 0, 0)); // invisible line
        label.setStrokeWeight(0f);
        label.setLineLabel(sb.toString());
        label.setAltitudeMode(Feature.AltitudeMode.Absolute);
        label.setType("u-d-f");
        label.setMetaString("how",         "m-g");
        label.setMetaString("wx_wind_col", "true");
        label.setClickable(false);
        label.setVisible(true);

        g.addItem(label);
        label.persist(mapView.getMapEventDispatcher(), null, getClass());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Live mutation — update wall points without remove/re-add
    // ══════════════════════════════════════════════════════════════════════════

    private void mutateWallPoints(String suffix,
                                  double lat, double lon,
                                  double rangeM, double heightM,
                                  List<WindProfileModel> profiles) {
        MapGroup g = overlay.getWindGroup();
        if (g == null) return;

        List<WindProfileModel.AltitudeEntry> tiers = resolveTiers(
                profiles,
                lastProfiles != null && !lastProfiles.isEmpty()
                        && !lastProfiles.get(0).getAltitudes().isEmpty()
                        ? lastProfiles.get(0).getAltitudes().get(0).windSpeed : 5.0,
                lastProfiles != null && !lastProfiles.isEmpty()
                        && !lastProfiles.get(0).getAltitudes().isEmpty()
                        ? lastProfiles.get(0).getAltitudes().get(0).windDirection : 270.0);

        if (tiers.isEmpty()) return;
        double maxTierAlt = tiers.get(tiers.size() - 1).altitudeMeters;
        double scale      = (maxTierAlt > 0) ? heightM / maxTierAlt : 1.0;

        for (int i = 0; i < tiers.size() - 1; i++) {
            WindProfileModel.AltitudeEntry bottom = tiers.get(i);
            WindProfileModel.AltitudeEntry top    = tiers.get(i + 1);

            String uid = wallUid(suffix, bottom.altitudeMeters, top.altitudeMeters);
            MapItem mi = g.deepFindUID(uid);
            if (!(mi instanceof Polyline)) continue;
            Polyline wall = (Polyline) mi;

            double bottomAlt = bottom.altitudeMeters * scale;
            double topAlt    = top.altitudeMeters    * scale;
            double wallH     = topAlt - bottomAlt;

            double halfB = halfAngleForAlt(bottom.altitudeMeters);
            double halfT = halfAngleForAlt(top.altitudeMeters);
            double downB = (bottom.windDirection + 180.0) % 360.0;
            double downT = (top.windDirection    + 180.0) % 360.0;

            wall.setPoints(new GeoPointMetaData[]{
                    GeoPointMetaData.wrap(offsetBearing(lat, lon, downB - halfB, rangeM, bottomAlt)),
                    GeoPointMetaData.wrap(offsetBearing(lat, lon, downB + halfB, rangeM, bottomAlt)),
                    GeoPointMetaData.wrap(offsetBearing(lat, lon, downT + halfT, rangeM, topAlt)),
                    GeoPointMetaData.wrap(offsetBearing(lat, lon, downT - halfT, rangeM, topAlt))
            });
            wall.setHeight(wallH);
        }

        // Update FOV range rings
        for (WindProfileModel.AltitudeEntry tier : tiers) {
            MapItem fovItem = g.deepFindUID(fovUid(suffix, tier.altitudeMeters));
            if (fovItem instanceof SensorFOV) {
                float rl = (float)(rangeM / RANGE_LINE_COUNT);
                float az = (float)((tier.windDirection + 180.0) % 360.0);
                float fovDeg = (float)(halfAngleForAlt(tier.altitudeMeters) * 2.0);
                ((SensorFOV) fovItem).setMetrics(az, fovDeg, (float) rangeM, true, rl);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Geometry helpers
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Flat-earth bearing offset.  Returns GeoPoint at the given HAE altitude.
     * altitude=0.0 for ground-level points; tier altitude for 3D wall corners.
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
    // Tier resolution (profiles → list, or fallback single tier)
    // ══════════════════════════════════════════════════════════════════════════

    private static List<WindProfileModel.AltitudeEntry> resolveTiers(
            List<WindProfileModel> profiles, double surfaceMs, double surfaceDir) {

        if (profiles != null && !profiles.isEmpty()) {
            List<WindProfileModel.AltitudeEntry> tiers = profiles.get(0).getAltitudes();
            if (tiers != null && tiers.size() >= 2) return tiers;
        }
        // Fallback: two synthetic tiers so we still get at least one wall
        List<WindProfileModel.AltitudeEntry> fb = new ArrayList<>();
        fb.add(new WindProfileModel.AltitudeEntry(10,  surfaceMs, surfaceDir, 15.0, 0));
        fb.add(new WindProfileModel.AltitudeEntry(180, surfaceMs, surfaceDir, 10.0, 0));
        return fb;
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
    // Colour + label helpers
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
    // UID helpers
    // ══════════════════════════════════════════════════════════════════════════

    private static String wallUid(String s, int bot, int top) {
        return "wx_col_wall_" + s + "_" + bot + "_" + top;
    }
    private static String fovUid(String s, int alt) {
        return "wx_col_fov_" + s + "_" + alt;
    }
    private static String labelUid(String s) {
        return "wx_col_label_" + s;
    }

    public static String uidSuffix(double lat, double lon, boolean isSelf) {
        return isSelf ? "self"
                : String.format(Locale.US, "%.4f_%.4f", lat, lon);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Cleanup
    // ══════════════════════════════════════════════════════════════════════════

    private static void removeSuffix(MapGroup g, String suffix) {
        // Remove all items tagged wx_wind_col matching this suffix
        for (MapItem mi : new ArrayList<>(g.deepFindItems("wx_wind_col", "true"))) {
            String uid = mi.getUID();
            if (uid != null && uid.contains("_" + suffix))
                mi.removeFromGroup();
        }
        // Remove legacy WindEffectShape cone/fov UIDs
        for (MapItem mi : new ArrayList<>(g.deepFindItems("wx_wind_shape", "true"))) {
            String uid = mi.getUID();
            if (uid != null && uid.contains("_" + suffix))
                mi.removeFromGroup();
        }
        // Belt-and-braces for known legacy prefixes
        removeByUid(g, "wx_col_label_" + suffix);
        int[] legacyAlts = {10, 80, 120, 180};
        for (int a : legacyAlts) {
            removeByUid(g, "wx_wind_cone_"   + suffix + "_" + a);
            removeByUid(g, "wx_wind_fov_"    + suffix + "_" + a);
            removeByUid(g, "wx_wind_pillar_" + suffix + "_" + a);
        }
        removeByUid(g, "wx_wind_prism_" + suffix);
    }

    private static void removeByUid(MapGroup g, String uid) {
        MapItem mi = g.deepFindUID(uid);
        if (mi != null) mi.removeFromGroup();
    }
}
