package com.atakmap.android.weather.cot;

/**
 * Weather CoT schema constants — defines the XML element names, attribute names,
 * and CoT type strings used for weather observation sharing over TAK networks.
 *
 * <h3>Sprint 3 — S3.1: CoT Schema Design</h3>
 *
 * <h4>Design rationale</h4>
 * <ul>
 *   <li>No official MIL-STD or TAK community weather CoT schema exists as of 2026-03.</li>
 *   <li>We follow ATAK's convention of using {@code <detail>} sub-elements with a
 *       plugin-specific namespace prefix ({@code __weather}) to avoid collisions with
 *       other plugins or ATAK core detail elements.</li>
 *   <li>Attribute names match the internal domain model field names for traceability.</li>
 *   <li>All numeric values are serialised in internal units (°C, m/s, hPa, metres)
 *       to eliminate ambiguity — display-side conversion is the receiver's responsibility.</li>
 * </ul>
 *
 * <h4>Weather observation CoT event</h4>
 * <pre>{@code
 * <event version="2.0" type="a-n-G-E-V-c" uid="wx_self"
 *        how="m-g" time="..." start="..." stale="...">
 *   <point lat="50.6971" lon="5.2583" hae="9999999" ce="9999999" le="9999999"/>
 *   <detail>
 *     <__weather
 *         tempMax="22.5" tempMin="14.2" apparentTemp="21.0"
 *         humidity="65" pressure="1013.25" visibility="10000"
 *         windSpeed="5.2" windDir="225" precipSum="0.0" precipHours="0"
 *         weatherCode="2" source="open-meteo" timestamp="2026-03-21T14:00:00Z"
 *         icaoId="" flightCategory="" rawMetar=""
 *     />
 *     <contact callsign="WX · Brussels"/>
 *     <remarks>WeatherTool observation</remarks>
 *   </detail>
 * </event>
 * }</pre>
 *
 * <h4>Wind profile CoT event</h4>
 * <pre>{@code
 * <event version="2.0" type="b-m-p-s-m" uid="wx_wind_self"
 *        how="m-g" time="..." start="..." stale="...">
 *   <point lat="50.6971" lon="5.2583" hae="9999999" ce="9999999" le="9999999"/>
 *   <detail>
 *     <__windprofile isoTime="2026-03-21T14:00:00Z" source="open-meteo">
 *       <alt m="10"  speed="5.2"  dir="225" temp="22.5" gusts="7.1"/>
 *       <alt m="80"  speed="8.4"  dir="230" temp="20.1" gusts="0"/>
 *       <alt m="120" speed="10.2" dir="235" temp="18.3" gusts="0"/>
 *       <alt m="180" speed="12.0" dir="240" temp="16.5" gusts="0"/>
 *     </__windprofile>
 *     <contact callsign="Wind: Brussels"/>
 *   </detail>
 * </event>
 * }</pre>
 *
 * <h4>Stale-out policy</h4>
 * Weather observations stale after {@link #STALE_WEATHER_MS} (2 hours).
 * Wind profiles stale after {@link #STALE_WIND_MS} (1 hour) because wind data
 * changes more rapidly than temperature/pressure.
 */
public final class WeatherCotSchema {

    private WeatherCotSchema() { /* non-instantiable */ }

    // ── CoT event types ────────────────────────────────────────────────────────

    /** CoT type for weather observation markers. Matches existing marker type. */
    public static final String TYPE_WEATHER = "a-n-G-E-V-c";

    /** CoT type for wind/met spot markers. Matches existing marker type. */
    public static final String TYPE_WIND    = "b-m-p-s-m";

    // ── Detail element names ───────────────────────────────────────────────────

    /**
     * Weather observation detail element.
     * Prefixed with {@code __} following ATAK plugin convention for custom detail elements.
     */
    public static final String ELEM_WEATHER      = "__weather";

    /**
     * Wind profile detail element.
     * Contains child {@code <alt>} elements for each altitude level.
     */
    public static final String ELEM_WIND_PROFILE = "__windprofile";

    /** Altitude entry within a wind profile. */
    public static final String ELEM_ALT          = "alt";

    // ── Weather detail attributes ──────────────────────────────────────────────

    public static final String ATTR_TEMP_MAX       = "tempMax";
    public static final String ATTR_TEMP_MIN       = "tempMin";
    public static final String ATTR_APPARENT_TEMP  = "apparentTemp";
    public static final String ATTR_HUMIDITY       = "humidity";
    public static final String ATTR_PRESSURE       = "pressure";
    public static final String ATTR_VISIBILITY     = "visibility";
    public static final String ATTR_WIND_SPEED     = "windSpeed";
    public static final String ATTR_WIND_DIR       = "windDir";
    public static final String ATTR_PRECIP_SUM     = "precipSum";
    public static final String ATTR_PRECIP_HOURS   = "precipHours";
    public static final String ATTR_WEATHER_CODE   = "weatherCode";
    public static final String ATTR_SOURCE         = "source";
    public static final String ATTR_TIMESTAMP      = "timestamp";
    public static final String ATTR_ICAO_ID        = "icaoId";
    public static final String ATTR_FLIGHT_CAT     = "flightCategory";
    public static final String ATTR_RAW_METAR      = "rawMetar";

    // ── Wind profile attributes ────────────────────────────────────────────────

    public static final String ATTR_ISO_TIME  = "isoTime";

    // ── Altitude entry attributes ──────────────────────────────────────────────

    /** Altitude in metres MSL. */
    public static final String ATTR_ALT_M     = "m";
    /** Wind speed in m/s. */
    public static final String ATTR_ALT_SPEED = "speed";
    /** Wind direction in degrees true. */
    public static final String ATTR_ALT_DIR   = "dir";
    /** Temperature in °C. */
    public static final String ATTR_ALT_TEMP  = "temp";
    /** Wind gusts in m/s. */
    public static final String ATTR_ALT_GUSTS = "gusts";

    // ── Stale-out durations ────────────────────────────────────────────────────

    /** Weather observations stale after 2 hours. */
    public static final long STALE_WEATHER_MS = 2 * 60 * 60 * 1000L;

    /** Wind profiles stale after 1 hour. */
    public static final long STALE_WIND_MS    = 1 * 60 * 60 * 1000L;

    // ── How field ──────────────────────────────────────────────────────────────

    /** Machine-generated observation (API data, not human-entered). */
    public static final String HOW_MACHINE = "m-g";

    // ── Remarks ────────────────────────────────────────────────────────────────

    /** Default remarks string for weather CoT events. */
    public static final String REMARKS_WEATHER = "WeatherTool observation";

    /** Default remarks string for wind profile CoT events. */
    public static final String REMARKS_WIND    = "WeatherTool wind profile";
}
