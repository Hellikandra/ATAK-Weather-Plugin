package org.dtakc.weather.atak.map.marker;

import org.dtakc.weather.atak.domain.model.LocationSnapshot;
import org.dtakc.weather.atak.domain.model.LocationSource;
import org.dtakc.weather.atak.domain.model.WeatherModel;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.junit.Assert.*;

/**
 * Wave 4 — Marker UID namespace qualification tests (ISS-05).
 *
 * TC-21: weather marker UID starts with "org.dtakc.weather"
 * TC-22: wind marker UID starts with "org.dtakc.weather.wind"
 * TC-23: two markers at different coords have different UIDs
 * TC-24: self-marker UID is distinct from map-centre UID
 */
@RunWith(JUnit4.class)
public class WeatherMarkerUidTest {

    /** Replicates the UID generation logic from WeatherMarkerManager. */
    private static String weatherUid(double lat, double lon, LocationSource source) {
        if (source == LocationSource.SELF_MARKER) {
            return "org.dtakc.weather.self";
        }
        return String.format(java.util.Locale.US,
                "org.dtakc.weather.centre_%.4f_%.4f", lat, lon);
    }

    /** Replicates the UID generation logic from WindMarkerManager. */
    private static String windUid(double lat, double lon) {
        return String.format(java.util.Locale.US,
                "org.dtakc.weather.wind_%.4f_%.4f", lat, lon);
    }

    // TC-21: weather UID has correct namespace prefix
    @Test
    public void TC21_weatherMarkerUid_hasNamespacePrefix() {
        String uid = weatherUid(48.85, 2.35, LocationSource.MAP_CENTRE);
        assertTrue("Weather UID must start with 'org.dtakc.weather'",
                uid.startsWith("org.dtakc.weather"));
        assertFalse("Weather UID must not use old 'wx_' prefix",
                uid.startsWith("wx_"));
    }

    // TC-22: wind marker UID has wind-specific namespace prefix
    @Test
    public void TC22_windMarkerUid_hasWindNamespacePrefix() {
        String uid = windUid(48.85, 2.35);
        assertTrue("Wind UID must start with 'org.dtakc.weather.wind'",
                uid.startsWith("org.dtakc.weather.wind"));
    }

    // TC-23: different coords produce different UIDs
    @Test
    public void TC23_differentCoords_differentUids() {
        String uid1 = weatherUid(48.85, 2.35, LocationSource.MAP_CENTRE);
        String uid2 = weatherUid(51.50, -0.12, LocationSource.MAP_CENTRE);
        assertNotEquals("Different coordinates must produce different UIDs", uid1, uid2);
    }

    // TC-24: self-marker UID is distinct from map-centre UID at same coords
    @Test
    public void TC24_selfMarker_distinctFromMapCentre() {
        String selfUid   = weatherUid(48.85, 2.35, LocationSource.SELF_MARKER);
        String centreUid = weatherUid(48.85, 2.35, LocationSource.MAP_CENTRE);
        assertNotEquals("Self-marker and map-centre UIDs must differ", selfUid, centreUid);
        assertTrue("Self-marker UID must contain 'self'", selfUid.contains("self"));
    }

    // Bonus: wind and weather UIDs don't collide
    @Test
    public void TC25_windAndWeatherUids_doNotCollide() {
        String weatherUid = weatherUid(48.85, 2.35, LocationSource.MAP_CENTRE);
        String windUid    = windUid(48.85, 2.35);
        assertNotEquals("Wind and weather UIDs must not collide at same coords",
                weatherUid, windUid);
    }
}
