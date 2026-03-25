package com.atakmap.android.weather.cot;

import android.os.Bundle;

import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.model.WindProfileModel;
import com.atakmap.android.weather.overlay.WeatherMapOverlay;
import com.atakmap.android.weather.overlay.WindMapOverlay;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.comms.CotServiceRemote;
import com.atakmap.android.weather.util.WeatherConstants;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * WeatherCotImporter — receives incoming weather and wind CoT events from the
 * TAK network and places them as markers on the map.
 *
 * <h3>Sprint 3 — S3.3</h3>
 *
 * <h4>How it works</h4>
 * <ol>
 *   <li>Registered as a {@link CotServiceRemote.CotEventListener} in
 *       {@code WeatherMapComponent.onCreate()}.</li>
 *   <li>On each incoming CoT event, checks if the event type matches
 *       {@link WeatherCotSchema#TYPE_WEATHER} or {@link WeatherCotSchema#TYPE_WIND}.</li>
 *   <li>If the event has a {@code <__weather>} or {@code <__windprofile>} detail element,
 *       it is parsed into a domain model and placed as a marker.</li>
 *   <li>If the event does NOT have our custom detail element (sent by a non-WeatherTool
 *       user), it is ignored (falls through to ATAK's default marker handler).</li>
 * </ol>
 *
 * <h4>Marker differentiation — S3.4</h4>
 * <ul>
 *   <li>Received markers get a {@code "wx_received"} meta flag set to {@code "true"}.</li>
 *   <li>The source callsign of the sender is stored in {@code "wx_sender"}.</li>
 *   <li>The marker callsign is prefixed with {@code "📡 "} to visually distinguish
 *       received observations from local ones.</li>
 * </ul>
 *
 * <h4>Conflict resolution</h4>
 * If a received CoT event has the same UID as an existing local marker, the local
 * marker is preserved (local takes priority). Received events use a modified UID
 * with a {@code "_rx_"} prefix to avoid collisions.
 *
 * <h4>Thread safety</h4>
 * {@link #onCotEvent(CotEvent, Bundle)} is called on ATAK's CoT dispatch thread.
 * Marker manipulation is posted to the main thread via {@code mapView.post()}.
 */
public class WeatherCotImporter implements CotServiceRemote.CotEventListener {

    private static final String TAG = "WeatherCotImporter";

    /** Meta key: "true" if marker was received from TAK network. */
    public static final String META_RECEIVED   = "wx_received";

    /** Meta key: callsign of the TAK user who sent this observation. */
    public static final String META_SENDER     = "wx_sender";

    /** UID prefix for received weather markers (prevents collision with local). */
    private static final String RX_PREFIX = "rx_";

    private final MapView            mapView;
    private final WeatherMapOverlay  weatherOverlay;
    private final WindMapOverlay     windOverlay;

    public WeatherCotImporter(MapView mapView,
                              WeatherMapOverlay weatherOverlay,
                              WindMapOverlay windOverlay) {
        this.mapView        = mapView;
        this.weatherOverlay = weatherOverlay;
        this.windOverlay    = windOverlay;
    }

    // ── Registration ───────────────────────────────────────────────────────────

    /**
     * Register this importer with ATAK's CoT dispatch system.
     * Uses {@link CommsMapComponent#addOnCotEventListener} which is the standard
     * ATAK pattern for receiving inbound CoT events from all transports
     * (TAK Server, multicast, TCP).
     *
     * <p>Call from {@code WeatherMapComponent.onCreate()}.</p>
     */
    public void register() {
        CommsMapComponent comms = CommsMapComponent.getInstance();
        if (comms != null) {
            comms.addOnCotEventListener(this);
            Log.d(TAG, "WeatherCotImporter registered as CotEventListener");
        } else {
            Log.w(TAG, "CommsMapComponent not available — cannot register importer");
        }
    }

    /**
     * Unregister from ATAK's CoT dispatch system.
     * Call from {@code WeatherMapComponent.onDestroyImpl()}.
     */
    public void unregister() {
        CommsMapComponent comms = CommsMapComponent.getInstance();
        if (comms != null) {
            comms.removeOnCotEventListener(this);
            Log.d(TAG, "WeatherCotImporter unregistered");
        }
    }

    // ── CotEventListener ───────────────────────────────────────────────────────

    @Override
    public void onCotEvent(CotEvent event, Bundle extra) {
        if (event == null || !event.isValid()) return;

        final String type = event.getType();
        if (type == null) return;

        // Only handle our known weather/wind CoT types
        if (!WeatherCotSchema.TYPE_WEATHER.equals(type) &&
            !WeatherCotSchema.TYPE_WIND.equals(type)) {
            return;
        }

        final CotDetail detail = event.getDetail();
        if (detail == null) return;

        // Check for our custom detail elements — if absent, this is not from WeatherTool
        CotDetail wxDetail   = findChild(detail, WeatherCotSchema.ELEM_WEATHER);
        CotDetail windDetail = findChild(detail, WeatherCotSchema.ELEM_WIND_PROFILE);

        if (wxDetail == null && windDetail == null) {
            // Not a WeatherTool event — let ATAK's default handler process it
            return;
        }

        // Extract sender callsign from the CoT <contact> element
        CotDetail contactElem = findChild(detail, "contact");
        final String senderCallsign = contactElem != null
                ? contactElem.getAttribute("callsign") : "Unknown";

        // Check if this event originated from us (skip self-echo)
        final String eventUid = event.getUID();
        if (eventUid != null && !eventUid.startsWith(RX_PREFIX)) {
            // Check if we already have a local marker with this UID
            if (isLocalMarker(eventUid)) {
                Log.d(TAG, "Ignoring self-echo for uid=" + eventUid);
                return;
            }
        }

        if (wxDetail != null) {
            handleReceivedWeather(event, wxDetail, senderCallsign);
        }
        if (windDetail != null) {
            handleReceivedWind(event, windDetail, senderCallsign);
        }
    }

    // ── Weather handler ────────────────────────────────────────────────────────

    private void handleReceivedWeather(CotEvent event, CotDetail wxDetail, String sender) {
        try {
            final double lat = event.getCotPoint().getLat();
            final double lon = event.getCotPoint().getLon();

            final WeatherModel weather = new WeatherModel.Builder(lat, lon)
                    .temperatureMax(attrDouble(wxDetail, WeatherCotSchema.ATTR_TEMP_MAX))
                    .temperatureMin(attrDouble(wxDetail, WeatherCotSchema.ATTR_TEMP_MIN))
                    .apparentTemperature(attrDouble(wxDetail, WeatherCotSchema.ATTR_APPARENT_TEMP))
                    .humidity(attrDouble(wxDetail, WeatherCotSchema.ATTR_HUMIDITY))
                    .pressure(attrDouble(wxDetail, WeatherCotSchema.ATTR_PRESSURE))
                    .visibility(attrDouble(wxDetail, WeatherCotSchema.ATTR_VISIBILITY))
                    .windSpeed(attrDouble(wxDetail, WeatherCotSchema.ATTR_WIND_SPEED))
                    .windDirection(attrDouble(wxDetail, WeatherCotSchema.ATTR_WIND_DIR))
                    .precipitationSum(attrDouble(wxDetail, WeatherCotSchema.ATTR_PRECIP_SUM))
                    .precipitationHours(attrDouble(wxDetail, WeatherCotSchema.ATTR_PRECIP_HOURS))
                    .weatherCode(attrInt(wxDetail, WeatherCotSchema.ATTR_WEATHER_CODE))
                    .requestTimestamp(attrStr(wxDetail, WeatherCotSchema.ATTR_TIMESTAMP))
                    .icaoId(attrStr(wxDetail, WeatherCotSchema.ATTR_ICAO_ID))
                    .flightCategory(attrStr(wxDetail, WeatherCotSchema.ATTR_FLIGHT_CAT))
                    .rawMetar(attrStr(wxDetail, WeatherCotSchema.ATTR_RAW_METAR))
                    .locationName(sender)
                    .build();

            final String uid = RX_PREFIX + event.getUID();
            final String callsign = "\uD83D\uDCE1 " + sender; // 📡 prefix

            mapView.post(() -> placeReceivedWeatherMarker(uid, callsign, weather, sender));

            Log.d(TAG, "Received weather CoT from " + sender
                    + " at " + String.format(Locale.US, "%.4f,%.4f", lat, lon));

        } catch (Exception e) {
            Log.w(TAG, "handleReceivedWeather failed: " + e.getMessage());
        }
    }

    private void placeReceivedWeatherMarker(String uid, String callsign,
                                             WeatherModel weather, String sender) {
        MapGroup group = weatherOverlay.getWeatherGroup();
        if (group == null) return;

        // Remove existing marker with same UID (update case)
        MapItem existing = group.deepFindUID(uid);
        if (existing != null) existing.removeFromGroup();

        GeoPoint point = new GeoPoint(weather.getLatitude(), weather.getLongitude());
        Marker marker = group.createMarker(point, uid);
        if (marker == null) return;

        marker.setType(WeatherCotSchema.TYPE_WEATHER);
        marker.setTitle(callsign);
        marker.setMetaString(WeatherConstants.META_CALLSIGN,      callsign);
        marker.setMetaString(WeatherConstants.META_HOW,            WeatherCotSchema.HOW_MACHINE);
        marker.setMetaString(WeatherConstants.META_WX_SOURCE,      attrOrEmpty(weather.isMetarSource() ? "aviation-weather" : "open-meteo"));
        marker.setMetaString(WeatherConstants.META_WX_TIMESTAMP,   weather.getRequestTimestamp());
        marker.setMetaString(WeatherConstants.META_WX_TEMP_MAX,    String.valueOf(weather.getTemperatureMax()));
        marker.setMetaString(WeatherConstants.META_WX_TEMP_MIN,    String.valueOf(weather.getTemperatureMin()));
        marker.setMetaString(WeatherConstants.META_WX_HUMIDITY,    String.valueOf(weather.getHumidity()));
        marker.setMetaString(WeatherConstants.META_WX_WIND_SPEED,  String.valueOf(weather.getWindSpeed()));
        marker.setMetaString(WeatherConstants.META_WX_WIND_DIR,    String.valueOf(weather.getWindDirection()));
        marker.setMetaString(WeatherConstants.META_WX_PRESSURE,    String.valueOf(weather.getPressure()));

        // Mark as received
        marker.setMetaString(META_RECEIVED, "true");
        marker.setMetaString(META_SENDER,   sender);

        marker.setClickable(true);
        marker.setVisible(true);

        Log.d(TAG, "Placed received weather marker: uid=" + uid);
    }

    // ── Wind handler ───────────────────────────────────────────────────────────

    private void handleReceivedWind(CotEvent event, CotDetail windDetail, String sender) {
        try {
            final double lat = event.getCotPoint().getLat();
            final double lon = event.getCotPoint().getLon();

            final String isoTime = attrStr(windDetail, WeatherCotSchema.ATTR_ISO_TIME);

            // Parse altitude entries
            List<WindProfileModel.AltitudeEntry> altitudes = new ArrayList<>();
            for (int i = 0; i < windDetail.childCount(); i++) {
                CotDetail child = windDetail.getChild(i);
                if (WeatherCotSchema.ELEM_ALT.equals(child.getElementName())) {
                    altitudes.add(new WindProfileModel.AltitudeEntry(
                            attrInt(child, WeatherCotSchema.ATTR_ALT_M),
                            attrDouble(child, WeatherCotSchema.ATTR_ALT_SPEED),
                            attrDouble(child, WeatherCotSchema.ATTR_ALT_DIR),
                            attrDouble(child, WeatherCotSchema.ATTR_ALT_TEMP),
                            attrDouble(child, WeatherCotSchema.ATTR_ALT_GUSTS)
                    ));
                }
            }

            final String uid = RX_PREFIX + event.getUID();
            final String callsign = "\uD83D\uDCE1 Wind: " + sender; // 📡 prefix

            mapView.post(() -> placeReceivedWindMarker(uid, callsign, lat, lon, sender));

            Log.d(TAG, "Received wind CoT from " + sender
                    + " at " + String.format(Locale.US, "%.4f,%.4f", lat, lon)
                    + " with " + altitudes.size() + " altitude entries");

        } catch (Exception e) {
            Log.w(TAG, "handleReceivedWind failed: " + e.getMessage());
        }
    }

    private void placeReceivedWindMarker(String uid, String callsign,
                                          double lat, double lon, String sender) {
        MapGroup group = windOverlay.getWindGroup();
        if (group == null) return;

        MapItem existing = group.deepFindUID(uid);
        if (existing != null) existing.removeFromGroup();

        GeoPoint point = new GeoPoint(lat, lon);
        Marker marker = group.createMarker(point, uid);
        if (marker == null) return;

        marker.setType(WeatherCotSchema.TYPE_WIND);
        marker.setTitle(callsign);
        marker.setMetaString(WeatherConstants.META_CALLSIGN, callsign);
        marker.setMetaString(META_RECEIVED, "true");
        marker.setMetaString(META_SENDER,   sender);
        marker.setMovable(false);
        marker.setClickable(true);
        marker.setVisible(true);

        Log.d(TAG, "Placed received wind marker: uid=" + uid);
    }

    // ── Utility ────────────────────────────────────────────────────────────────

    private boolean isLocalMarker(String uid) {
        if (uid == null) return false;
        // Check in both weather and wind groups
        MapGroup wxGrp = weatherOverlay.getWeatherGroup();
        if (wxGrp != null && wxGrp.deepFindUID(uid) != null) return true;
        MapGroup windGrp = windOverlay.getWindGroup();
        return windGrp != null && windGrp.deepFindUID(uid) != null;
    }

    private static CotDetail findChild(CotDetail parent, String elementName) {
        if (parent == null || elementName == null) return null;
        for (int i = 0; i < parent.childCount(); i++) {
            CotDetail child = parent.getChild(i);
            if (elementName.equals(child.getElementName())) return child;
        }
        return null;
    }

    private static double attrDouble(CotDetail elem, String name) {
        String val = elem.getAttribute(name);
        if (val == null || val.isEmpty()) return 0;
        try { return Double.parseDouble(val); }
        catch (NumberFormatException e) { return 0; }
    }

    private static int attrInt(CotDetail elem, String name) {
        String val = elem.getAttribute(name);
        if (val == null || val.isEmpty()) return 0;
        try { return Integer.parseInt(val); }
        catch (NumberFormatException e) { return 0; }
    }

    private static String attrStr(CotDetail elem, String name) {
        String val = elem.getAttribute(name);
        return val != null ? val : "";
    }

    private static String attrOrEmpty(String value) {
        return value != null ? value : "";
    }
}
