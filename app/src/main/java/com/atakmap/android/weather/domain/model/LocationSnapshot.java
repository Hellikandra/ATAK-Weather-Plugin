package com.atakmap.android.weather.domain.model;

/**
 * Immutable snapshot of a resolved geographic position.
 *
 * Created by WeatherViewModel when a weather load is initiated and
 * propagated through the LiveData pipeline so every tab always knows:
 *
 *  - which coordinate the data belongs to (lat / lon, full precision)
 *  - which source produced it (SELF_MARKER or MAP_CENTRE)
 *  - a human-readable name from Nominatim (or a lat/lon fallback string)
 *
 * Displayed in:
 *  - Tab 1 header  : name + "lat, lon" line under the location TextView
 *  - Tab 6 cards   : each card shows its own LocationSnapshot
 *
 * Sprint 3: this will also be used as the cache key in the Room database
 * (lat/lon rounded to 4 dp + source → snapshot row).
 */
public class LocationSnapshot {

    private final double         latitude;
    private final double         longitude;
    private final String         displayName;   // from Nominatim, or coords fallback
    private final LocationSource source;

    public LocationSnapshot(double latitude, double longitude,
                            String displayName, LocationSource source) {
        this.latitude    = latitude;
        this.longitude   = longitude;
        this.displayName = displayName;
        this.source      = source;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public double         getLatitude()    { return latitude; }
    public double         getLongitude()   { return longitude; }
    public String         getDisplayName() { return displayName; }
    public LocationSource getSource()      { return source; }

    /**
     * Returns a formatted coordinate string: "50.6971° N, 5.2583° E"
     * Used as a secondary line under the location name in the UI.
     */
    public String getCoordsLabel() {
        String latDir = latitude  >= 0 ? "N" : "S";
        String lonDir = longitude >= 0 ? "E" : "W";
        return String.format("%.4f° %s,  %.4f° %s",
                Math.abs(latitude),  latDir,
                Math.abs(longitude), lonDir);
    }

    /**
     * Returns a compact coords string used when Nominatim fails:
     * "50.6971, 5.2583"
     */
    public static String coordsFallback(double lat, double lon) {
        return String.format("%.4f, %.4f", lat, lon);
    }
}
