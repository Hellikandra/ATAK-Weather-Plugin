package com.atakmap.android.weather.overlay.cbrn;

import android.graphics.Color;

import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.maps.Polyline;
import com.atakmap.android.maps.Shape;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.model.WindProfileModel;
import com.atakmap.android.weather.domain.service.AtmosphericStabilityService;
import com.atakmap.android.weather.domain.service.GaussianPlumeModel;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CbrnOverlayManager -- Calculates and displays Gaussian plume dispersion
 * overlays on the ATAK map for CBRN hazard estimation.
 *
 * <h3>Pipeline</h3>
 * <ol>
 *   <li>Get atmospheric stability from {@link AtmosphericStabilityService}</li>
 *   <li>Get effective wind at release height from weather/wind data</li>
 *   <li>Run {@link GaussianPlumeModel#calculatePlume} for dispersion cone</li>
 *   <li>Draw inner (high concentration) and outer (low concentration) polygons</li>
 * </ol>
 *
 * <h3>Map integration</h3>
 * Plume polygons are drawn in the "Weather CBRN" MapGroup:
 * <ul>
 *   <li>Inner cone (1-sigma, higher concentration): red, semi-transparent</li>
 *   <li>Outer cone (2-sigma, lower concentration): yellow, semi-transparent</li>
 *   <li>Wind direction arrow along the centerline</li>
 *   <li>Label with stability class, wind info, and disclaimer</li>
 * </ul>
 *
 * <h3>Disclaimer</h3>
 * <b>ESTIMATE ONLY -- Not a replacement for ATP-45/HPAC.</b>
 *
 * Sprint 14 -- S14.3
 */
public class CbrnOverlayManager {

    private static final String TAG = "CbrnOverlayMgr";
    private static final String GROUP_NAME = "Weather CBRN";

    // ── ATP-45 Zone Colours ──────────────────────────────────────────────────
    // IWA (Initial Warning Area): purple circle at release point
    private static final int IWA_FILL   = Color.argb(35, 180, 0, 220);
    private static final int IWA_STROKE = Color.argb(180, 140, 0, 180);
    // SHA (Simplified Hazard Area = 2-sigma outer cone): yellow downwind sector
    private static final int OUTER_FILL   = Color.argb(50, 255, 200, 0);
    private static final int OUTER_STROKE = Color.argb(180, 220, 180, 0);
    // DHA (Detailed Hazard Area = 1-sigma inner cone): red high-concentration
    private static final int INNER_FILL   = Color.argb(60, 220, 30, 30);
    private static final int INNER_STROKE = Color.argb(180, 200, 0, 0);
    private static final int CENTER_COLOR = Color.argb(200, 255, 255, 255);

    /** Whether to show NATO NBC-1 report text on map. */
    private boolean showNbc1Report = true;

    private final MapView mapView;
    private final List<MapItem> plumeItems = new ArrayList<>();
    private boolean active = false;

    // Last calculated state (for UI display)
    private char lastStabilityClass = '?';
    private double lastWindSpeed = 0;
    private double lastWindDir = 0;

    // Time-evolution state
    private double releaseLat = Double.NaN;
    private double releaseLon = Double.NaN;
    private double maxDownwindKm = 5.0;
    private int    currentHourIndex = 0;
    private WeatherModel cachedWeather;
    private List<WindProfileModel> cachedWindProfiles;
    private List<com.atakmap.android.weather.domain.model.HourlyEntryModel> cachedHourly;
    /** True when the last placement used curved mode. */
    private boolean lastWasCurved = false;

    /** Listener for status updates to the CONF tab UI. */
    public interface StatusListener {
        void onPlumeCalculated(char stabilityClass, String stabilityDesc,
                               double windSpeed, double windDir, double maxDownwindKm);
        void onPlumeCleared();
    }

    private StatusListener statusListener;

    public CbrnOverlayManager(MapView mapView) {
        this.mapView = mapView;
    }

    public void setStatusListener(StatusListener l) { this.statusListener = l; }

    public boolean isActive() { return active; }

    public char getLastStabilityClass() { return lastStabilityClass; }
    public double getLastWindSpeed() { return lastWindSpeed; }
    public double getLastWindDir() { return lastWindDir; }

    /**
     * Toggle NBC-1 report visibility.
     * When enabled, the report text is shown in marker remarks (tap to see).
     * When disabled, remarks are cleared from the marker.
     */
    public void setShowNbc1Report(boolean show) {
        this.showNbc1Report = show;
        mapView.post(() -> {
            for (MapItem item : plumeItems) {
                if (item instanceof Marker && "CBRN Release".equals(
                        item.getMetaString("callsign", ""))) {
                    if (!show) {
                        ((Marker) item).setMetaString("remarks", "");
                    } else {
                        // Re-generate the report into remarks
                        String nbc1 = buildNbc1Report(releaseLat, releaseLon,
                                lastStabilityClass,
                                AtmosphericStabilityService.getStabilityDescription(lastStabilityClass),
                                lastWindSpeed, lastWindDir, maxDownwindKm,
                                "ESTIMATE ONLY \u2014 not a replacement for ATP-45/HPAC");
                        ((Marker) item).setMetaString("remarks", nbc1);
                    }
                }
            }
        });
    }
    public boolean isShowNbc1Report() { return showNbc1Report; }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Calculate and display a dispersion plume from given release point.
     *
     * @param releaseLat  release point latitude
     * @param releaseLon  release point longitude
     * @param weather     current weather data (for cloud cover, wind)
     * @param windProfile wind profile data (for multi-altitude wind; may be null)
     * @param maxDownwindKm maximum downwind distance to model (km)
     */
    public void calculateAndDisplay(double releaseLat, double releaseLon,
                                     WeatherModel weather,
                                     List<WindProfileModel> windProfile,
                                     double maxDownwindKm) {
        // 1. Get effective wind at surface
        double windSpeed;
        double windDir;
        double cloudCover;

        if (weather != null) {
            windSpeed = weather.getWindSpeed();
            windDir = weather.getWindDirection();
            cloudCover = 50.0; // Default cloud cover estimate when not directly available
            // Use humidity as rough cloud cover proxy if needed
            if (weather.getHumidity() > 80) cloudCover = 80.0;
            else if (weather.getHumidity() > 60) cloudCover = 60.0;
            else if (weather.getHumidity() < 30) cloudCover = 20.0;
        } else {
            // No weather data -- use defaults
            windSpeed = 3.0;
            windDir = 0.0;
            cloudCover = 50.0;
        }

        // Override with wind profile surface data if available
        if (windProfile != null && !windProfile.isEmpty()) {
            WindProfileModel.AltitudeEntry surface = windProfile.get(0).getSurface();
            if (surface != null) {
                windSpeed = surface.windSpeed;
                windDir = surface.windDirection;
            }
        }

        // 2. Calculate atmospheric stability
        double solarElevation = AtmosphericStabilityService.calculateSolarElevation(
                releaseLat, releaseLon, System.currentTimeMillis());
        char stabilityClass = AtmosphericStabilityService.calculateStabilityClass(
                windSpeed, cloudCover, solarElevation);
        String stabilityDesc = AtmosphericStabilityService.getStabilityDescription(
                stabilityClass);

        // 3. Run Gaussian plume model
        GaussianPlumeModel.PlumeResult plume = GaussianPlumeModel.calculatePlume(
                releaseLat, releaseLon,
                windSpeed, windDir,
                stabilityClass, 1.0,  // sourceStrength (arbitrary for visualization)
                maxDownwindKm);

        // 4. Cache release point for time evolution
        this.releaseLat = releaseLat;
        this.releaseLon = releaseLon;
        this.maxDownwindKm = maxDownwindKm;
        this.cachedWeather = weather;
        this.cachedWindProfiles = windProfile;
        this.currentHourIndex = 0;

        // 5. Draw on map
        lastStabilityClass = stabilityClass;
        lastWindSpeed = windSpeed;
        lastWindDir = windDir;

        drawPlume(releaseLat, releaseLon, plume, stabilityClass, stabilityDesc,
                windSpeed, windDir);

        // Notify listener
        final char fClass = stabilityClass;
        final String fDesc = stabilityDesc;
        final double fWs = windSpeed, fWd = windDir, fDist = maxDownwindKm;
        mapView.post(() -> {
            if (statusListener != null) {
                statusListener.onPlumeCalculated(fClass, fDesc, fWs, fWd, fDist);
            }
        });

        active = true;
        lastWasCurved = false;
        Log.d(TAG, String.format(Locale.US,
                "Plume drawn: stability=%c (%s), wind=%.1fm/s@%.0f, dist=%.1fkm",
                stabilityClass, stabilityDesc, windSpeed, windDir, maxDownwindKm));
    }

    /**
     * Calculate and display a curved plume that follows hourly wind shifts.
     * The plume path bends according to the per-hour wind direction from the forecast.
     */
    public void calculateAndDisplayCurved(double releaseLat, double releaseLon,
                                           WeatherModel weather,
                                           List<com.atakmap.android.weather.domain.model.HourlyEntryModel> hourly,
                                           double maxDownwindKm) {
        if (hourly == null || hourly.isEmpty()) {
            // Fall back to straight plume
            calculateAndDisplay(releaseLat, releaseLon, weather, null, maxDownwindKm);
            return;
        }

        // Estimate hours needed: maxKm / (avg wind speed * 3.6)
        double avgWs = 0;
        for (com.atakmap.android.weather.domain.model.HourlyEntryModel h : hourly) avgWs += h.getWindSpeed();
        avgWs /= hourly.size();
        int maxHours = Math.max(2, (int) Math.ceil(maxDownwindKm * 1000.0 / (Math.max(avgWs, 1) * 3600.0)));
        maxHours = Math.min(maxHours, Math.min(hourly.size(), 48));

        double[] speeds = new double[maxHours];
        double[] dirs   = new double[maxHours];
        for (int i = 0; i < maxHours; i++) {
            speeds[i] = hourly.get(i).getWindSpeed();
            dirs[i]   = hourly.get(i).getWindDirection();
        }

        // Cloud cover estimate for stability
        double cloudCover = 50.0;
        if (weather != null) {
            if (weather.getHumidity() > 80) cloudCover = 80.0;
            else if (weather.getHumidity() > 60) cloudCover = 60.0;
            else if (weather.getHumidity() < 30) cloudCover = 20.0;
        }
        double solarElev = AtmosphericStabilityService.calculateSolarElevation(
                releaseLat, releaseLon, System.currentTimeMillis());
        char stability = AtmosphericStabilityService.calculateStabilityClass(
                speeds[0], cloudCover, solarElev);
        String desc = AtmosphericStabilityService.getStabilityDescription(stability);

        GaussianPlumeModel.PlumeResult plume = GaussianPlumeModel.calculateCurvedPlume(
                releaseLat, releaseLon, speeds, dirs, stability, maxHours, maxDownwindKm);

        // Cache state for time evolution — crucial for curved re-calculations
        this.releaseLat = releaseLat;
        this.releaseLon = releaseLon;
        this.maxDownwindKm = maxDownwindKm;
        this.cachedWeather = weather;
        this.cachedHourly = new ArrayList<>(hourly);  // cache the hourly data!
        this.currentHourIndex = 0;
        this.lastWasCurved = true;

        lastStabilityClass = stability;
        lastWindSpeed = speeds[0];
        lastWindDir = dirs[0];

        drawPlume(releaseLat, releaseLon, plume, stability, desc, speeds[0], dirs[0]);

        if (statusListener != null) {
            final char fC = stability; final String fD = desc + " (curved)";
            final double fW = speeds[0], fD2 = dirs[0], fK = plume.maxDownwindKm;
            mapView.post(() -> statusListener.onPlumeCalculated(fC, fD, fW, fD2, fK));
        }

        active = true;
        Log.d(TAG, String.format(Locale.US,
                "Curved plume drawn: stability=%c, %d hours, dist=%.1fkm",
                stability, maxHours, plume.maxDownwindKm));
    }

    /** Remove plume overlay from map. */
    public void clear() {
        mapView.post(() -> {
            MapGroup group = getOrCreateGroup();
            for (MapItem item : new ArrayList<>(plumeItems)) {
                item.removeFromGroup();
            }
            plumeItems.clear();
            active = false;
            lastStabilityClass = '?';

            if (statusListener != null) {
                statusListener.onPlumeCleared();
            }
        });
    }

    /** Update plume with new weather data (e.g., after a wind shift). */
    public void update(double releaseLat, double releaseLon,
                       WeatherModel weather,
                       List<WindProfileModel> windProfile,
                       double maxDownwindKm) {
        clear();
        calculateAndDisplay(releaseLat, releaseLon, weather, windProfile, maxDownwindKm);
    }

    // ── Time-evolving dispersion ─────────────────────────────────────────────

    /**
     * Cache hourly forecast data for time-based plume scrubbing.
     * Called from OverlayTabCoordinator when hourly data becomes available.
     */
    public void setHourlyData(
            List<com.atakmap.android.weather.domain.model.HourlyEntryModel> hourly) {
        this.cachedHourly = hourly;
    }

    /**
     * Recalculate plume for a specific forecast hour using cached hourly data.
     * Respects the mode used at placement time:
     * - Straight: single-direction Gaussian cone at that hour's wind
     * - Curved: wind-following trajectory using hourly data starting at that hour
     *
     * @param hourIndex forecast hour index (0 = now, 1 = +1h, etc.)
     */
    public void setTimeHour(int hourIndex) {
        if (Double.isNaN(releaseLat) || !active) return;
        if (cachedHourly == null || hourIndex < 0 || hourIndex >= cachedHourly.size()) return;
        this.currentHourIndex = hourIndex;

        com.atakmap.android.weather.domain.model.HourlyEntryModel entry =
                cachedHourly.get(hourIndex);
        double ws = entry.getWindSpeed();
        double wd = entry.getWindDirection();
        if (ws <= 0) return;

        double cloudCover = 50.0;
        double solarElev = AtmosphericStabilityService.calculateSolarElevation(
                releaseLat, releaseLon,
                System.currentTimeMillis() + (long) hourIndex * 3_600_000L);
        char stability = AtmosphericStabilityService.calculateStabilityClass(
                ws, cloudCover, solarElev);
        String desc = AtmosphericStabilityService.getStabilityDescription(stability);

        GaussianPlumeModel.PlumeResult plume;
        String modeLabel;

        if (lastWasCurved) {
            // Curved mode: use hourly data starting from hourIndex
            int remaining = cachedHourly.size() - hourIndex;
            int maxH = Math.max(2, Math.min(remaining, 48));
            double[] speeds = new double[maxH];
            double[] dirs   = new double[maxH];
            for (int i = 0; i < maxH; i++) {
                speeds[i] = cachedHourly.get(hourIndex + i).getWindSpeed();
                dirs[i]   = cachedHourly.get(hourIndex + i).getWindDirection();
            }
            plume = GaussianPlumeModel.calculateCurvedPlume(
                    releaseLat, releaseLon, speeds, dirs, stability, maxH, maxDownwindKm);
            modeLabel = " (curved +"+hourIndex+"h)";
        } else {
            // Straight mode: single Gaussian cone at this hour's wind
            plume = GaussianPlumeModel.calculatePlume(
                    releaseLat, releaseLon, ws, wd, stability, 1.0, maxDownwindKm);
            modeLabel = " (+"+hourIndex+"h)";
        }

        lastStabilityClass = stability;
        lastWindSpeed = ws;
        lastWindDir = wd;

        drawPlume(releaseLat, releaseLon, plume, stability, desc, ws, wd);

        if (statusListener != null) {
            final char fC = stability; final String fD = desc + modeLabel;
            final double fW = ws, fD2 = wd, fK = plume.maxDownwindKm;
            mapView.post(() -> statusListener.onPlumeCalculated(fC, fD, fW, fD2, fK));
        }
    }

    /** Return the current time hour index. */
    public int getCurrentHourIndex() { return currentHourIndex; }

    /** Return whether a release point has been placed. */
    public boolean hasReleasePoint() { return !Double.isNaN(releaseLat); }

    /** Clean up resources. Call from WeatherMapComponent.onDestroyImpl(). */
    public void dispose() {
        clear();
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    private void drawPlume(double releaseLat, double releaseLon,
                           GaussianPlumeModel.PlumeResult plume,
                           char stabilityClass, String stabilityDesc,
                           double windSpeed, double windDir) {
        mapView.post(() -> {
            MapGroup group = getOrCreateGroup();

            // Clear existing plume items
            for (MapItem item : new ArrayList<>(plumeItems)) {
                item.removeFromGroup();
            }
            plumeItems.clear();

            // Draw outer cone (2-sigma boundary) -- yellow
            List<double[]> outerPoly = plume.toPolygon();
            if (outerPoly.size() >= 3) {
                Polyline outerShape = createPolygon(
                        "wx_cbrn_outer_" + System.currentTimeMillis(),
                        outerPoly,
                        OUTER_FILL, OUTER_STROKE, 2.0f);
                outerShape.setTitle(String.format(Locale.US,
                        "CBRN Hazard Zone (2\u03c3)\nStability: %c (%s)\n"
                                + "Wind: %.1f m/s @ %.0f\u00b0\n"
                                + "Max downwind: %.1f km\n"
                                + "\u26a0 %s",
                        stabilityClass, stabilityDesc,
                        windSpeed, windDir,
                        plume.maxDownwindKm,
                        plume.disclaimer));
                outerShape.setMetaString("callsign",
                        "CBRN Zone " + stabilityClass + " (outer)");
                group.addItem(outerShape);
                outerShape.persist(mapView.getMapEventDispatcher(), null, getClass());
                plumeItems.add(outerShape);
            }

            // Draw inner cone (1-sigma boundary) -- red
            List<double[]> innerPoly = plume.toInnerPolygon();
            if (innerPoly.size() >= 3) {
                Polyline innerShape = createPolygon(
                        "wx_cbrn_inner_" + System.currentTimeMillis(),
                        innerPoly,
                        INNER_FILL, INNER_STROKE, 1.5f);
                innerShape.setTitle(String.format(Locale.US,
                        "CBRN High Concentration Zone (1\u03c3)\n\u26a0 %s",
                        plume.disclaimer));
                innerShape.setMetaString("callsign",
                        "CBRN Zone " + stabilityClass + " (inner)");
                group.addItem(innerShape);
                innerShape.persist(mapView.getMapEventDispatcher(), null, getClass());
                plumeItems.add(innerShape);
            }

            // Draw centerline arrow
            if (plume.centerline.size() >= 2) {
                Polyline centerline = createCenterline(
                        "wx_cbrn_center_" + System.currentTimeMillis(),
                        plume.centerline);
                group.addItem(centerline);
                centerline.persist(mapView.getMapEventDispatcher(), null, getClass());
                plumeItems.add(centerline);
            }

            // ── ATP-45 IWA: Initial Warning Area (circle at release point) ──
            // Radius = 2km for close-range immediate hazard
            double iwaRadiusM = 2000.0;
            List<double[]> iwaCircle = generateCircle(releaseLat, releaseLon, iwaRadiusM, 32);
            if (iwaCircle.size() >= 3) {
                Polyline iwaShape = createPolygon(
                        "wx_cbrn_iwa_" + System.currentTimeMillis(),
                        iwaCircle, IWA_FILL, IWA_STROKE, 2.0f);
                iwaShape.setTitle("ATP-45 Initial Warning Area (IWA)\n"
                        + "Radius: " + String.format(Locale.US, "%.0f m", iwaRadiusM)
                        + "\nImmediate hazard zone around release point");
                iwaShape.setMetaString("callsign", "IWA (ATP-45)");
                group.addItem(iwaShape);
                iwaShape.persist(mapView.getMapEventDispatcher(), null, getClass());
                plumeItems.add(iwaShape);
            }

            // ── Release point marker with NBC-1 report ─────────────────
            String releaseUid = "wx_cbrn_release_" + System.currentTimeMillis();
            Marker releaseMarker = new Marker(
                    new GeoPoint(releaseLat, releaseLon), releaseUid);
            releaseMarker.setType("a-C-G");
            releaseMarker.setMetaString("how", "m-g");
            releaseMarker.setMetaString("wx_cbrn", "true");

            // Short callsign for on-map label (not the full report)
            String shortLabel = String.format(Locale.US,
                    "CBRN %c  %.0f\u00b0 %.1fm/s  %.1fkm",
                    stabilityClass, windDir, windSpeed, plume.maxDownwindKm);
            releaseMarker.setMetaString("callsign", "CBRN Release");
            releaseMarker.setTitle(shortLabel);

            // Build NATO NBC-1 report — store in remarks (visible on tap, not on map)
            String nbc1 = buildNbc1Report(releaseLat, releaseLon,
                    stabilityClass, stabilityDesc, windSpeed, windDir,
                    plume.maxDownwindKm, plume.disclaimer);
            releaseMarker.setMetaString("remarks", nbc1);
            releaseMarker.setColor(Color.RED);
            releaseMarker.setClickable(true);
            releaseMarker.setVisible(true);
            group.addItem(releaseMarker);
            plumeItems.add(releaseMarker);

            Log.d(TAG, "Drew plume with " + plumeItems.size() + " items");
        });
    }

    private Polyline createPolygon(String uid, List<double[]> coords,
                                    int fillColor, int strokeColor, float strokeW) {
        GeoPointMetaData[] points = new GeoPointMetaData[coords.size()];
        for (int i = 0; i < coords.size(); i++) {
            points[i] = GeoPointMetaData.wrap(
                    new GeoPoint(coords.get(i)[0], coords.get(i)[1]));
        }

        Polyline poly = new Polyline(uid);
        poly.setPoints(points);
        poly.setStyle(Polyline.STYLE_CLOSED_MASK
                | Shape.STYLE_STROKE_MASK
                | Shape.STYLE_FILLED_MASK);
        poly.setFillColor(fillColor);
        poly.setStrokeColor(strokeColor);
        poly.setStrokeWeight(strokeW);
        poly.setType("u-d-f");
        poly.setMetaString("how", "m-g");
        poly.setMetaString("wx_cbrn", "true");
        poly.setClickable(true);
        poly.setVisible(true);
        return poly;
    }

    private Polyline createCenterline(String uid, List<double[]> coords) {
        GeoPointMetaData[] points = new GeoPointMetaData[coords.size()];
        for (int i = 0; i < coords.size(); i++) {
            points[i] = GeoPointMetaData.wrap(
                    new GeoPoint(coords.get(i)[0], coords.get(i)[1]));
        }

        Polyline line = new Polyline(uid);
        line.setPoints(points);
        line.setStyle(Shape.STYLE_STROKE_MASK);
        line.setStrokeColor(CENTER_COLOR);
        line.setStrokeWeight(1.5f);
        line.setType("u-d-f");
        line.setMetaString("how", "m-g");
        line.setMetaString("wx_cbrn", "true");
        line.setTitle("Plume Centerline (downwind)");
        line.setMetaString("callsign", "CBRN Centerline");
        line.setClickable(false);
        line.setVisible(true);
        return line;
    }

    // ── ATP-45 NBC-1 Report ──────────────────────────────────────────────────

    /**
     * Build a simplified NATO NBC-1 (Observer's) Report format.
     * Based on STANAG 2103 (ATP-45) unclassified structure.
     */
    private static String buildNbc1Report(double lat, double lon,
                                           char stability, String stabilityDesc,
                                           double windSpeed, double windDir,
                                           double maxDownwindKm, String disclaimer) {
        // NATO MGRS would be ideal; using decimal degrees as fallback
        String dtg = new java.text.SimpleDateFormat("ddHHmm'Z' MMM yy",
                java.util.Locale.US).format(new java.util.Date());

        return "═══ NBC-1 OBSERVER REPORT ═══\n"
                + "ALFA: " + dtg + "\n"
                + "BRAVO: " + String.format(Locale.US, "%.4f\u00b0N %.4f\u00b0E", lat, lon) + "\n"
                + "CHARLIE: ATTACK / RELEASE\n"
                + "DELTA: CHEMICAL (estimated)\n"
                + "ECHO: --\n"
                + "FOXTROT: --\n"
                + "═══ HAZARD PREDICTION ═══\n"
                + "Stability: " + stability + " (" + stabilityDesc + ")\n"
                + "Wind: " + String.format(Locale.US, "%.0f\u00b0 @ %.1f m/s", windDir, windSpeed) + "\n"
                + "Downwind: " + String.format(Locale.US, "%.1f km", maxDownwindKm) + "\n"
                + "IWA: 2 km radius (purple)\n"
                + "SHA: Outer cone (yellow)\n"
                + "DHA: Inner cone (red)\n"
                + "\u26a0 " + disclaimer;
    }

    /**
     * Generate a circle as a list of [lat,lon] points.
     */
    private static List<double[]> generateCircle(double centerLat, double centerLon,
                                                   double radiusM, int numPoints) {
        List<double[]> circle = new ArrayList<>(numPoints + 1);
        for (int i = 0; i <= numPoints; i++) {
            double bearing = (360.0 * i) / numPoints;
            double rad = Math.toRadians(bearing);
            double dLat = Math.toDegrees(radiusM / 6_371_000.0 * Math.cos(rad));
            double dLon = Math.toDegrees(radiusM / 6_371_000.0 * Math.sin(rad)
                    / Math.cos(Math.toRadians(centerLat)));
            circle.add(new double[]{centerLat + dLat, centerLon + dLon});
        }
        return circle;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private MapGroup getOrCreateGroup() {
        MapGroup root = mapView.getRootGroup();
        MapGroup group = root.findMapGroup(GROUP_NAME);
        if (group == null) {
            group = root.addGroup(GROUP_NAME);
        }
        return group;
    }
}
