package org.dtakc.weather.atak.domain.model;

import java.util.Locale;

/** Resolved position with display name and source tag. Pure Java. */
public final class LocationSnapshot {
    private final double lat, lon;
    private final String displayName;
    private final LocationSource source;

    public LocationSnapshot(double lat, double lon, String displayName, LocationSource source) {
        this.lat = lat; this.lon = lon;
        this.displayName = displayName != null ? displayName : coordsLabel(lat, lon);
        this.source = source;
    }

    public double       getLatitude()    { return lat; }
    public double       getLongitude()   { return lon; }
    public String       getDisplayName() { return displayName; }
    public LocationSource getSource()   { return source; }
    public String       getCoordsLabel() { return coordsLabel(lat, lon); }

    private static String coordsLabel(double lat, double lon) {
        return String.format(Locale.US, "%.4f°N  %.4f°E", lat, lon);
    }
}
