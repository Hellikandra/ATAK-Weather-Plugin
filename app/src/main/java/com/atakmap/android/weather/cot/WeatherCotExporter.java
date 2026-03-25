package com.atakmap.android.weather.cot;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.model.WindProfileModel;
import com.atakmap.android.weather.util.WeatherConstants;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.time.CoordinatedTime;

import java.util.List;
import java.util.Locale;

/**
 * WeatherCotExporter — converts WeatherModel and WindProfileModel to CotEvent
 * and broadcasts them over the TAK network.
 *
 * <h3>Sprint 3 — S3.2</h3>
 *
 * <h4>Usage</h4>
 * <pre>{@code
 *   // Export a weather observation
 *   WeatherCotExporter exporter = new WeatherCotExporter();
 *   CotEvent event = exporter.buildWeatherEvent(weather, "wx_self", "WX · Brussels");
 *   exporter.broadcast(event);
 *
 *   // Export a wind profile
 *   CotEvent windEvt = exporter.buildWindProfileEvent(windProfile, lat, lon,
 *           "wx_wind_self", "Wind: Brussels", "open-meteo");
 *   exporter.broadcast(windEvt);
 * }</pre>
 *
 * <h4>Export triggers</h4>
 * <ul>
 *   <li><b>Manual:</b> "Share WX" button in the radial menu → fires SHARE_MARKER intent →
 *       DDR calls {@link #buildWeatherEventFromItem(MapItem)} + {@link #broadcast(CotEvent)}.</li>
 *   <li><b>Auto:</b> (Optional, configurable) On each new weather fetch, if auto-share is enabled
 *       in preferences, the ViewModel triggers export automatically.</li>
 * </ul>
 *
 * <h4>CoT detail enrichment</h4>
 * Unlike the Sprint 1 approach ({@code CotEventFactory.createCotEvent(item)} which only
 * serialises ATAK's built-in marker fields), this exporter adds a custom {@code <__weather>}
 * detail element containing all weather-specific data (temperature, humidity, pressure, wind,
 * etc.). This allows receiving ATAK instances to reconstruct a full {@link WeatherModel}
 * even without the WeatherTool plugin installed (the marker still appears, just without
 * rich weather detail).
 *
 * <h4>Thread safety</h4>
 * All methods are stateless and safe to call from any thread.
 * {@link #broadcast(CotEvent)} dispatches via ATAK's CoT dispatcher which handles
 * its own threading.
 */
public class WeatherCotExporter {

    private static final String TAG = "WeatherCotExporter";

    // ── Weather observation export ─────────────────────────────────────────────

    /**
     * Build a CoT event from a WeatherModel with custom {@code <__weather>} detail.
     *
     * @param weather   the weather observation to export
     * @param uid       marker UID (e.g. "wx_self")
     * @param callsign  display name (e.g. "WX · Brussels")
     * @return valid CotEvent, or null on error
     */
    public CotEvent buildWeatherEvent(WeatherModel weather, String uid, String callsign) {
        if (weather == null) return null;

        try {
            CotEvent event = new CotEvent();
            event.setVersion("2.0");
            event.setUID(uid);
            event.setType(WeatherCotSchema.TYPE_WEATHER);
            event.setHow(WeatherCotSchema.HOW_MACHINE);

            // Time fields
            CoordinatedTime now = new CoordinatedTime();
            event.setTime(now);
            event.setStart(now);
            event.setStale(new CoordinatedTime(now.getMilliseconds() + WeatherCotSchema.STALE_WEATHER_MS));

            // Point
            event.setPoint(new CotPoint(
                    weather.getLatitude(), weather.getLongitude(),
                    CotPoint.UNKNOWN, CotPoint.UNKNOWN, CotPoint.UNKNOWN));

            // Detail
            CotDetail detail = new CotDetail();

            // Contact sub-element
            CotDetail contact = new CotDetail("contact");
            contact.setAttribute("callsign", callsign != null ? callsign : "WX Observation");
            detail.addChild(contact);

            // Weather data sub-element
            CotDetail wxDetail = new CotDetail(WeatherCotSchema.ELEM_WEATHER);
            wxDetail.setAttribute(WeatherCotSchema.ATTR_TEMP_MAX,      fmt(weather.getTemperatureMax()));
            wxDetail.setAttribute(WeatherCotSchema.ATTR_TEMP_MIN,      fmt(weather.getTemperatureMin()));
            wxDetail.setAttribute(WeatherCotSchema.ATTR_APPARENT_TEMP, fmt(weather.getApparentTemperature()));
            wxDetail.setAttribute(WeatherCotSchema.ATTR_HUMIDITY,      fmt(weather.getHumidity()));
            wxDetail.setAttribute(WeatherCotSchema.ATTR_PRESSURE,      fmt(weather.getPressure()));
            wxDetail.setAttribute(WeatherCotSchema.ATTR_VISIBILITY,    fmt(weather.getVisibility()));
            wxDetail.setAttribute(WeatherCotSchema.ATTR_WIND_SPEED,    fmt(weather.getWindSpeed()));
            wxDetail.setAttribute(WeatherCotSchema.ATTR_WIND_DIR,      fmt(weather.getWindDirection()));
            wxDetail.setAttribute(WeatherCotSchema.ATTR_PRECIP_SUM,    fmt(weather.getPrecipitationSum()));
            wxDetail.setAttribute(WeatherCotSchema.ATTR_PRECIP_HOURS,  fmt(weather.getPrecipitationHours()));
            wxDetail.setAttribute(WeatherCotSchema.ATTR_WEATHER_CODE,  String.valueOf(weather.getWeatherCode()));
            wxDetail.setAttribute(WeatherCotSchema.ATTR_SOURCE,        weather.isMetarSource() ? "aviation-weather" : "open-meteo");
            wxDetail.setAttribute(WeatherCotSchema.ATTR_TIMESTAMP,     weather.getRequestTimestamp());
            wxDetail.setAttribute(WeatherCotSchema.ATTR_ICAO_ID,       weather.getIcaoId());
            wxDetail.setAttribute(WeatherCotSchema.ATTR_FLIGHT_CAT,    weather.getFlightCategory());
            wxDetail.setAttribute(WeatherCotSchema.ATTR_RAW_METAR,     weather.getRawMetar());
            detail.addChild(wxDetail);

            // Remarks
            CotDetail remarks = new CotDetail("remarks");
            remarks.setInnerText(WeatherCotSchema.REMARKS_WEATHER);
            detail.addChild(remarks);

            event.setDetail(detail);

            if (!event.isValid()) {
                Log.w(TAG, "Built weather CotEvent is invalid: uid=" + uid);
                return null;
            }
            return event;

        } catch (Exception e) {
            Log.e(TAG, "buildWeatherEvent failed", e);
            return null;
        }
    }

    // ── Wind profile export ────────────────────────────────────────────────────

    /**
     * Build a CoT event from a WindProfileModel with custom {@code <__windprofile>} detail.
     *
     * @param profile   the wind profile to export
     * @param lat       latitude of the observation
     * @param lon       longitude of the observation
     * @param uid       marker UID (e.g. "wx_wind_self")
     * @param callsign  display name (e.g. "Wind: Brussels")
     * @param sourceId  weather source ID (e.g. "open-meteo")
     * @return valid CotEvent, or null on error
     */
    public CotEvent buildWindProfileEvent(WindProfileModel profile,
                                           double lat, double lon,
                                           String uid, String callsign,
                                           String sourceId) {
        if (profile == null) return null;

        try {
            CotEvent event = new CotEvent();
            event.setVersion("2.0");
            event.setUID(uid);
            event.setType(WeatherCotSchema.TYPE_WIND);
            event.setHow(WeatherCotSchema.HOW_MACHINE);

            CoordinatedTime now = new CoordinatedTime();
            event.setTime(now);
            event.setStart(now);
            event.setStale(new CoordinatedTime(now.getMilliseconds() + WeatherCotSchema.STALE_WIND_MS));

            event.setPoint(new CotPoint(lat, lon,
                    CotPoint.UNKNOWN, CotPoint.UNKNOWN, CotPoint.UNKNOWN));

            CotDetail detail = new CotDetail();

            // Contact
            CotDetail contact = new CotDetail("contact");
            contact.setAttribute("callsign", callsign != null ? callsign : "Wind Profile");
            detail.addChild(contact);

            // Wind profile sub-element
            CotDetail wpDetail = new CotDetail(WeatherCotSchema.ELEM_WIND_PROFILE);
            wpDetail.setAttribute(WeatherCotSchema.ATTR_ISO_TIME, profile.getIsoTime());
            wpDetail.setAttribute(WeatherCotSchema.ATTR_SOURCE,   sourceId != null ? sourceId : "unknown");

            // Altitude entries
            List<WindProfileModel.AltitudeEntry> alts = profile.getAltitudes();
            if (alts != null) {
                for (WindProfileModel.AltitudeEntry alt : alts) {
                    CotDetail altElem = new CotDetail(WeatherCotSchema.ELEM_ALT);
                    altElem.setAttribute(WeatherCotSchema.ATTR_ALT_M,     String.valueOf(alt.altitudeMeters));
                    altElem.setAttribute(WeatherCotSchema.ATTR_ALT_SPEED, fmt(alt.windSpeed));
                    altElem.setAttribute(WeatherCotSchema.ATTR_ALT_DIR,   fmt(alt.windDirection));
                    altElem.setAttribute(WeatherCotSchema.ATTR_ALT_TEMP,  fmt(alt.temperature));
                    altElem.setAttribute(WeatherCotSchema.ATTR_ALT_GUSTS, fmt(alt.windGusts));
                    wpDetail.addChild(altElem);
                }
            }
            detail.addChild(wpDetail);

            // Remarks
            CotDetail remarks = new CotDetail("remarks");
            remarks.setInnerText(WeatherCotSchema.REMARKS_WIND);
            detail.addChild(remarks);

            event.setDetail(detail);

            if (!event.isValid()) {
                Log.w(TAG, "Built wind CotEvent is invalid: uid=" + uid);
                return null;
            }
            return event;

        } catch (Exception e) {
            Log.e(TAG, "buildWindProfileEvent failed", e);
            return null;
        }
    }

    // ── Build from existing MapItem (enriched export) ──────────────────────────

    /**
     * Build a weather CotEvent from an existing MapItem's metadata.
     * This extracts weather data from the marker's metaStrings and builds
     * a proper {@code <__weather>} detail element, rather than using the
     * basic {@code CotEventFactory.createCotEvent()} which only includes
     * ATAK's standard marker fields.
     *
     * @param item the MapItem (weather marker)
     * @return enriched CotEvent with weather detail, or null
     */
    public CotEvent buildWeatherEventFromItem(MapItem item) {
        if (item == null) return null;

        try {
            String uid      = item.getUID();
            String callsign = item.getMetaString(WeatherConstants.META_CALLSIGN, uid);

            // Reconstruct a minimal WeatherModel from marker metadata
            double lat = item.getMetaDouble("latitude",
                    item.hasMetaValue("latitude") ? item.getMetaDouble("latitude", 0) : 0);
            double lon = item.getMetaDouble("longitude",
                    item.hasMetaValue("longitude") ? item.getMetaDouble("longitude", 0) : 0);

            // If lat/lon are 0, try getting from the marker's point
            if (lat == 0 && lon == 0) {
                if (item instanceof com.atakmap.android.maps.PointMapItem) {
                    com.atakmap.coremap.maps.coords.GeoPoint gp =
                            ((com.atakmap.android.maps.PointMapItem) item).getPoint();
                    if (gp != null) {
                        lat = gp.getLatitude();
                        lon = gp.getLongitude();
                    }
                }
            }

            WeatherModel weather = new WeatherModel.Builder(lat, lon)
                    .temperatureMax(parseDouble(item.getMetaString(WeatherConstants.META_WX_TEMP_MAX, "0")))
                    .temperatureMin(parseDouble(item.getMetaString(WeatherConstants.META_WX_TEMP_MIN, "0")))
                    .humidity(parseDouble(item.getMetaString(WeatherConstants.META_WX_HUMIDITY, "0")))
                    .windSpeed(parseDouble(item.getMetaString(WeatherConstants.META_WX_WIND_SPEED, "0")))
                    .windDirection(parseDouble(item.getMetaString(WeatherConstants.META_WX_WIND_DIR, "0")))
                    .pressure(parseDouble(item.getMetaString(WeatherConstants.META_WX_PRESSURE, "0")))
                    .requestTimestamp(item.getMetaString(WeatherConstants.META_WX_TIMESTAMP, ""))
                    .icaoId(item.getMetaString(WeatherConstants.META_WX_ICAO_ID, ""))
                    .flightCategory(item.getMetaString(WeatherConstants.META_WX_FLT_CAT, ""))
                    .rawMetar(item.getMetaString(WeatherConstants.META_WX_RAW_METAR, ""))
                    .build();

            return buildWeatherEvent(weather, uid, callsign);

        } catch (Exception e) {
            Log.e(TAG, "buildWeatherEventFromItem failed", e);
            return null;
        }
    }

    // ── Broadcasting ───────────────────────────────────────────────────────────

    /**
     * Broadcast a CotEvent over the TAK network.
     *
     * @param event the event to broadcast (must be valid)
     * @return true if dispatched successfully
     */
    public boolean broadcast(CotEvent event) {
        if (event == null || !event.isValid()) {
            Log.w(TAG, "Cannot broadcast null or invalid CotEvent");
            return false;
        }
        try {
            CotMapComponent.getExternalDispatcher().dispatchToBroadcast(event);
            Log.d(TAG, "Broadcast CoT: type=" + event.getType() + " uid=" + event.getUID());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "broadcast failed", e);
            return false;
        }
    }

    // ── Utilities ──────────────────────────────────────────────────────────────

    private static String fmt(double value) {
        return String.format(Locale.US, "%.4f", value);
    }

    private static double parseDouble(String s) {
        try { return Double.parseDouble(s); }
        catch (Exception e) { return 0; }
    }
}
