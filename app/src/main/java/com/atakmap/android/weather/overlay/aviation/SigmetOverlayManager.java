package com.atakmap.android.weather.overlay.aviation;

import android.graphics.Color;

import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Polyline;
import com.atakmap.android.maps.Shape;
import com.atakmap.android.weather.data.remote.HttpClient;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * SigmetOverlayManager -- Fetches and displays SIGMET/AIRMET polygons
 * from the FAA Aviation Weather Center API on the ATAK map.
 *
 * <h3>Data source</h3>
 * FAA AWC AirSigmet API: {@code https://aviationweather.gov/api/data/airsigmet?format=json}
 *
 * <h3>Map integration</h3>
 * SIGMETs are rendered as coloured {@link Polyline} polygons added to a
 * dedicated MapGroup ("Weather SIGMETs") under the ATAK root group.
 * Colours indicate hazard type:
 * <ul>
 *   <li>Turbulence: orange</li>
 *   <li>Icing: blue</li>
 *   <li>Convective: red</li>
 *   <li>Volcanic ash: purple</li>
 *   <li>IFR / MTN_OBSCN: gray</li>
 * </ul>
 *
 * <h3>Lifecycle</h3>
 * Follows the same start/stop/clear pattern as {@code RadarOverlayManager}.
 *
 * Sprint 14 -- S14.1
 */
public class SigmetOverlayManager {

    private static final String TAG = "SigmetOverlayMgr";

    private static final String AWC_SIGMET_URL =
            "https://aviationweather.gov/api/data/airsigmet?format=json";

    private static final String GROUP_NAME = "Weather SIGMETs";

    private final MapView mapView;
    private final List<MapItem> overlayItems = new ArrayList<>();
    private boolean active = false;

    private int sigmetCount = 0;

    /** Listener for status updates to the CONF tab UI. */
    public interface StatusListener {
        void onStatusChanged(String status);
    }

    private StatusListener statusListener;

    public SigmetOverlayManager(MapView mapView) {
        this.mapView = mapView;
    }

    public void setStatusListener(StatusListener l) { this.statusListener = l; }

    public boolean isActive() { return active; }

    public int getSigmetCount() { return sigmetCount; }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Start the overlay and fetch SIGMETs. */
    public void start() {
        active = true;
        notifyStatus("Fetching SIGMETs...");
        refresh();
    }

    /** Stop the overlay and remove all SIGMET polygons from the map. */
    public void stop() {
        active = false;
        clearOverlays();
        notifyStatus("SIGMETs: Off");
    }

    /** Fetch and display SIGMETs on the map. */
    public void refresh() {
        if (!active) return;
        notifyStatus("Fetching SIGMETs...");
        HttpClient.get(AWC_SIGMET_URL, new HttpClient.Callback() {
            @Override
            public void onSuccess(String body) {
                if (!active) return;
                try {
                    List<SigmetData> sigmets = parseSigmets(body);
                    displayOnMap(sigmets);
                    sigmetCount = sigmets.size();
                    notifyStatus("SIGMETs: " + sigmets.size() + " active");
                } catch (Exception e) {
                    Log.e(TAG, "SIGMET parse failed", e);
                    notifyStatus("SIGMET parse error");
                }
            }

            @Override
            public void onFailure(String error) {
                Log.w(TAG, "SIGMET fetch failed: " + error);
                if (active) notifyStatus("SIGMET fetch failed");
            }
        });
    }

    /** Remove all SIGMET items from map. */
    public void clearOverlays() {
        mapView.post(() -> {
            MapGroup group = getOrCreateGroup();
            for (MapItem item : new ArrayList<>(overlayItems)) {
                item.removeFromGroup();
            }
            overlayItems.clear();
            sigmetCount = 0;
        });
    }

    /** Clean up resources. Call from WeatherMapComponent.onDestroyImpl(). */
    public void dispose() {
        stop();
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    /**
     * Parse AWC JSON response into SIGMET data objects.
     * AWC returns an array of objects, each containing hazard info and coordinates.
     */
    private List<SigmetData> parseSigmets(String json) throws JSONException {
        List<SigmetData> result = new ArrayList<>();
        JSONArray arr = new JSONArray(json);

        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject obj = arr.getJSONObject(i);
                SigmetData data = new SigmetData();

                data.hazardType = obj.optString("hazard", "UNKNOWN");
                data.severity = obj.optString("severity", "");
                data.altitudeLowFt = obj.optInt("altitudeLow1", 0);
                data.altitudeHighFt = obj.optInt("altitudeHi1", 45000);
                data.rawText = obj.optString("rawAirSigmet", "");

                // Parse validity times
                String validFrom = obj.optString("validTimeFrom", "");
                String validTo = obj.optString("validTimeTo", "");
                data.validFrom = parseIsoTime(validFrom);
                data.validTo = parseIsoTime(validTo);

                // Parse coordinates — AWC API uses "coords" array of {lat,lon} objects
                if (obj.has("coords")) {
                    JSONArray coordsArr = obj.optJSONArray("coords");
                    if (coordsArr != null) {
                        data.coordinates = parseCoordsArray(coordsArr);
                    }
                }

                // Fallback: try "coord" string format "lat1 lon1 lat2 lon2 ..."
                if (data.coordinates.isEmpty()) {
                    String coordStr = obj.optString("coord", "");
                    if (!coordStr.isEmpty()) {
                        data.coordinates = parseCoordString(coordStr);
                    }
                }

                // Fallback: GeoJSON-like "geom" field
                if (data.coordinates.isEmpty() && obj.has("geom")) {
                    JSONObject geom = obj.optJSONObject("geom");
                    if (geom != null) {
                        data.coordinates = parseGeomObject(geom);
                    }
                }

                // Only add if we have valid polygon coordinates (3+ points)
                if (data.coordinates.size() >= 3) {
                    result.add(data);
                }
            } catch (Exception e) {
                Log.w(TAG, "Skipping malformed SIGMET entry " + i + ": " + e.getMessage());
            }
        }

        Log.d(TAG, "Parsed " + result.size() + " SIGMETs from " + arr.length() + " entries");
        return result;
    }

    /**
     * Parse AWC "coords" JSON array of {lat, lon} objects.
     * Example: [{"lat":31.044,"lon":-79.149}, ...]
     */
    private List<double[]> parseCoordsArray(JSONArray arr) {
        List<double[]> coords = new ArrayList<>();
        for (int j = 0; j < arr.length(); j++) {
            try {
                JSONObject pt = arr.getJSONObject(j);
                double lat = pt.getDouble("lat");
                double lon = pt.getDouble("lon");
                coords.add(new double[]{lat, lon});
            } catch (Exception ignored) {}
        }
        return coords;
    }

    /**
     * Parse coordinate string "lat1 lon1 lat2 lon2 ..." into list of [lat,lon] pairs.
     */
    private List<double[]> parseCoordString(String coordStr) {
        List<double[]> coords = new ArrayList<>();
        String[] parts = coordStr.trim().split("\\s+");
        for (int j = 0; j + 1 < parts.length; j += 2) {
            try {
                double lat = Double.parseDouble(parts[j]);
                double lon = Double.parseDouble(parts[j + 1]);
                coords.add(new double[]{lat, lon});
            } catch (NumberFormatException ignored) {
            }
        }
        return coords;
    }

    /**
     * Parse GeoJSON-style geometry object into [lat,lon] pairs.
     */
    private List<double[]> parseGeomObject(JSONObject geom) {
        List<double[]> coords = new ArrayList<>();
        try {
            JSONArray coordsArr = geom.optJSONArray("coordinates");
            if (coordsArr == null) return coords;

            String type = geom.optString("type", "");
            if ("Polygon".equals(type) && coordsArr.length() > 0) {
                JSONArray ring = coordsArr.getJSONArray(0);
                for (int k = 0; k < ring.length(); k++) {
                    JSONArray pt = ring.getJSONArray(k);
                    // GeoJSON is [lon, lat]
                    double lon = pt.getDouble(0);
                    double lat = pt.getDouble(1);
                    coords.add(new double[]{lat, lon});
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "geom parse error: " + e.getMessage());
        }
        return coords;
    }

    private long parseIsoTime(String iso) {
        if (iso == null || iso.isEmpty()) return 0;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = sdf.parse(iso);
            return d != null ? d.getTime() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ── Display ───────────────────────────────────────────────────────────────

    /**
     * Display SIGMETs as coloured polygons on ATAK map.
     * Each SIGMET becomes a filled/stroked Polyline in the "Weather SIGMETs" group.
     */
    private void displayOnMap(List<SigmetData> sigmets) {
        mapView.post(() -> {
            // Clear existing overlays first
            MapGroup group = getOrCreateGroup();
            for (MapItem item : new ArrayList<>(overlayItems)) {
                item.removeFromGroup();
            }
            overlayItems.clear();

            for (int i = 0; i < sigmets.size(); i++) {
                SigmetData sigmet = sigmets.get(i);
                try {
                    createSigmetPolygon(group, sigmet, i);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to create SIGMET polygon " + i, e);
                }
            }
            Log.d(TAG, "Displayed " + overlayItems.size() + " SIGMET polygons");
        });
    }

    private void createSigmetPolygon(MapGroup group, SigmetData sigmet, int index) {
        String uid = "wx_sigmet_" + index + "_" + System.currentTimeMillis();

        // Build polygon points
        GeoPointMetaData[] points = new GeoPointMetaData[sigmet.coordinates.size()];
        for (int j = 0; j < sigmet.coordinates.size(); j++) {
            double[] coord = sigmet.coordinates.get(j);
            points[j] = GeoPointMetaData.wrap(new GeoPoint(coord[0], coord[1]));
        }

        Polyline polygon = new Polyline(uid);
        polygon.setPoints(points);
        polygon.setStyle(Polyline.STYLE_CLOSED_MASK
                | Shape.STYLE_STROKE_MASK
                | Shape.STYLE_FILLED_MASK);

        // Colour based on hazard type
        int fillColor = getFillColor(sigmet.hazardType);
        int strokeColor = getStrokeColor(sigmet.hazardType);
        polygon.setFillColor(fillColor);
        polygon.setStrokeColor(strokeColor);
        polygon.setStrokeWeight(2.0f);

        // Metadata for tap popup
        polygon.setType("u-d-f");
        polygon.setMetaString("how", "m-g");
        polygon.setMetaString("wx_sigmet", "true");
        polygon.setMetaString("wx_hazard", sigmet.hazardType);
        polygon.setMetaString("wx_severity", sigmet.severity);

        String altRange = sigmet.altitudeLowFt + " - " + sigmet.altitudeHighFt + " ft";
        String validRange = formatValidTime(sigmet.validFrom, sigmet.validTo);
        String title = sigmet.hazardType
                + (sigmet.severity.isEmpty() ? "" : " (" + sigmet.severity + ")")
                + "\n" + altRange
                + "\nValid: " + validRange;
        polygon.setTitle(title);
        polygon.setMetaString("callsign",
                "SIGMET " + sigmet.hazardType + " " + sigmet.severity);
        polygon.setClickable(true);
        polygon.setVisible(true);

        group.addItem(polygon);
        polygon.persist(mapView.getMapEventDispatcher(), null, getClass());
        overlayItems.add(polygon);
    }

    // ── Colour mapping ────────────────────────────────────────────────────────

    private int getFillColor(String hazardType) {
        switch (normalizeHazard(hazardType)) {
            case "TURB":
            case "TURB-HI":
            case "TURB-LO":
                return Color.argb(60, 255, 165, 0);       // Orange semi-transparent
            case "ICE":
            case "ICING":
                return Color.argb(60, 30, 144, 255);      // Blue
            case "CONVECTIVE":
            case "CONV":
                return Color.argb(60, 220, 20, 20);       // Red
            case "VA":
            case "ASH":
                return Color.argb(60, 148, 0, 211);       // Purple
            case "IFR":
            case "LIFR":
                return Color.argb(50, 160, 160, 160);     // Gray
            case "MTN_OBSCN":
            case "MTN OBSCN":
                return Color.argb(50, 140, 140, 140);     // Gray
            default:
                return Color.argb(50, 200, 200, 0);       // Yellow fallback
        }
    }

    private int getStrokeColor(String hazardType) {
        switch (normalizeHazard(hazardType)) {
            case "TURB":
            case "TURB-HI":
            case "TURB-LO":
                return Color.argb(200, 255, 140, 0);
            case "ICE":
            case "ICING":
                return Color.argb(200, 20, 100, 220);
            case "CONVECTIVE":
            case "CONV":
                return Color.argb(200, 200, 0, 0);
            case "VA":
            case "ASH":
                return Color.argb(200, 128, 0, 200);
            case "IFR":
            case "LIFR":
                return Color.argb(180, 120, 120, 120);
            case "MTN_OBSCN":
            case "MTN OBSCN":
                return Color.argb(180, 110, 110, 110);
            default:
                return Color.argb(180, 180, 180, 0);
        }
    }

    private String normalizeHazard(String hazard) {
        return hazard != null ? hazard.toUpperCase(Locale.US).trim() : "";
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

    private String formatValidTime(long from, long to) {
        if (from == 0 && to == 0) return "unknown";
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        String f = from > 0 ? sdf.format(new Date(from)) : "?";
        String t = to > 0 ? sdf.format(new Date(to)) : "?";
        return f + " - " + t;
    }

    private void notifyStatus(String status) {
        mapView.post(() -> {
            if (statusListener != null) statusListener.onStatusChanged(status);
        });
    }

    // ── Data class ────────────────────────────────────────────────────────────

    /**
     * Parsed SIGMET data object.
     */
    public static class SigmetData {
        public String hazardType = "";      // TURB, ICE, CONVECTIVE, VA, IFR, MTN_OBSCN
        public String severity = "";        // LIGHT, MODERATE, SEVERE
        public int altitudeLowFt;
        public int altitudeHighFt;
        public long validFrom;
        public long validTo;
        public String rawText = "";
        public List<double[]> coordinates = new ArrayList<>();  // [lat,lon] polygon vertices
    }
}
