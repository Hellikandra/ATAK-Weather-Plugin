package com.atakmap.android.weather.overlay.lightning;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;

import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LightningOverlayManager -- Displays lightning strike markers on the ATAK map.
 *
 * <h3>Data source — there is none, and that is deliberate</h3>
 * This overlay has no lightning feed. Enabling it renders nothing and reports
 * "Lightning: no source configured". That is the honest state until a lawful
 * provider is wired
 * in (issue #23).
 *
 * <h3>Why Blitzortung is not used</h3>
 * The obvious free feed is Blitzortung's {@code wss://ws1.blitzortung.org}, and
 * it is technically trivial — send <code>{"a":111}</code> on open, LZW-decode
 * each JSON message, read {@code lat}/{@code lon}. Earlier versions of this
 * class pointed at it and an ATAK plugin exists that implements it.
 *
 * <p>It is excluded on licence, not difficulty. Blitzortung state their data is
 * "provided only for private and entertainment purposes" and explicitly
 * prohibit using it for <b>storm warning systems</b> and <b>risk analysis for
 * protective technology</b> — and say that restriction applies even when the
 * data arrives via a third party. {@link #checkProximityAlert} is a storm
 * warning system. That is the named prohibited use, so no amount of relaying
 * makes it acceptable.
 *
 * <p>Other options considered: NOAA GOES GLM is public domain but ships as bulk
 * netCDF on S3 with no per-request API, so it needs a server-side aggregator
 * rather than a client. Commercial networks (Vaisala, via Xweather's
 * {@code /lightning}) are the only feeds that are both lawful for this use and
 * reachable from a device — those need a key, which should be user-supplied
 * through the existing external source-definition mechanism rather than
 * embedded in the APK.
 *
 * <h3>What was removed</h3>
 * Until 2026-08-27 this class fabricated a strike every three seconds within
 * ~50 km of map centre and drew it as an ordinary marker. Finding F6 gated that
 * behind a developer preference; this change deletes it. A weather overlay that
 * invents its own data is worse than an empty one, and a disabled-by-default
 * simulation is still code that can be switched on by accident.
 *
 * <h3>Map integration</h3>
 * Strikes are rendered as small circle markers in a dedicated MapGroup
 * ("Weather Lightning"). Markers fade from bright yellow (new) to dim orange
 * (old) based on age, and are removed after the TTL (30 minutes).
 *
 * <h3>Proximity alert</h3>
 * {@link #checkProximityAlert(double, double, double)} checks if any strike
 * within a configurable radius has occurred in the last 5 minutes.
 *
 * <h3>Lifecycle</h3>
 * Follows start/stop/clear pattern like RadarOverlayManager.
 *
 * Sprint 14 -- S14.2
 */
public class LightningOverlayManager {

    private static final String TAG = "LightningOverlayMgr";

    private static final int MAX_STRIKES = 500;
    private static final long STRIKE_TTL_MS = 30 * 60 * 1000L;  // 30 min display
    private static final long DISPLAY_REFRESH_MS = 10_000L;       // refresh every 10s
    private static final long PROXIMITY_WINDOW_MS = 5 * 60 * 1000L; // 5 min window

    private static final String GROUP_NAME = "Weather Lightning";

    private final MapView mapView;
    private final List<LightningStrike> strikes = new ArrayList<>();
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());


    private double proximityRadiusKm = 25.0;

    /** Altitude filter (meters). Strikes outside this range are not displayed. */
    private int altitudeFilterMinM = 0;
    private int altitudeFilterMaxM = 20_000;

    /** Listener for status updates to the CONF tab UI. */
    public interface StatusListener {
        void onStatusChanged(String status);
        void onStrikeCountChanged(int count);
        void onProximityAlert(double distKm, LightningStrike strike);
    }

    private StatusListener statusListener;

    public LightningOverlayManager(MapView mapView) {
        this.mapView = mapView;
    }

    public void setStatusListener(StatusListener l) { this.statusListener = l; }

    public boolean isActive() { return active.get(); }


    public void setProximityRadiusKm(double km) { this.proximityRadiusKm = km; }

    public double getProximityRadiusKm() { return proximityRadiusKm; }

    /** Set minimum altitude filter (meters). Strikes below this are hidden. */
    public void setAltitudeFilterMin(int meters) {
        this.altitudeFilterMinM = meters;
        refreshDisplay(); // re-filter visible strikes
    }

    /** Set maximum altitude filter (meters). Strikes above this are hidden. */
    public void setAltitudeFilterMax(int meters) {
        this.altitudeFilterMaxM = meters;
        refreshDisplay();
    }

    /** Check if a strike passes the altitude filter. */
    private boolean passesAltitudeFilter(LightningStrike strike) {
        if (strike.altitudeM < 0) return true; // unknown altitude — show it
        return strike.altitudeM >= altitudeFilterMinM
                && strike.altitudeM <= altitudeFilterMaxM;
    }

    public int getStrikeCount() {
        synchronized (strikes) {
            return strikes.size();
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Start listening for lightning strikes. */
    public void start() {
        if (!active.compareAndSet(false, true)) return;
        // No feed to connect to. Rather than probe a host we have decided we
        // cannot lawfully consume, say plainly that there is nothing to show.
        Log.i(TAG, "Lightning overlay enabled, but no lightning source is configured. "
                + "See the class javadoc and issue #23.");
        notifyStatus("Lightning: no source configured");
        notifyStrikeCount(0);
        startDisplayRefresh();
    }

    /** Stop listening and remove all markers. */
    public void stop() {
        if (!active.compareAndSet(true, false)) return;
        mainHandler.removeCallbacksAndMessages(null);
        clearMarkers();
        notifyStatus("Lightning: Off");
        notifyStrikeCount(0);
    }

    /** Clean up resources. Call from WeatherMapComponent.onDestroyImpl(). */
    public void dispose() {
        stop();
    }

    // ── Strike management ─────────────────────────────────────────────────────

    private void addStrike(LightningStrike strike) {
        synchronized (strikes) {
            strikes.add(strike);
            // Trim to max size, removing oldest
            while (strikes.size() > MAX_STRIKES) {
                strikes.remove(0);
            }
        }

        // Check proximity alert on self position
        GeoPoint self = mapView.getSelfMarker() != null
                ? mapView.getSelfMarker().getPoint() : null;
        if (self != null) {
            checkProximityAlert(self.getLatitude(), self.getLongitude(), proximityRadiusKm);
        }

        notifyStrikeCount(getStrikeCount());
    }

    // ── Display refresh ───────────────────────────────────────────────────────

    private void startDisplayRefresh() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!active.get()) return;
                updateDisplay();
                mainHandler.postDelayed(this, DISPLAY_REFRESH_MS);
            }
        });
    }

    /**
     * Update displayed strike markers with age-based fading.
     * Removes expired strikes and creates/updates markers for remaining ones.
     */
    private void updateDisplay() {
        mapView.post(() -> {
            long now = System.currentTimeMillis();
            MapGroup group = getOrCreateGroup();

            // Remove old markers
            for (MapItem mi : new ArrayList<>(group.getItems())) {
                if ("wx_lightning".equals(mi.getMetaString("wx_type", ""))) {
                    mi.removeFromGroup();
                }
            }

            // Purge expired strikes
            synchronized (strikes) {
                Iterator<LightningStrike> it = strikes.iterator();
                while (it.hasNext()) {
                    if (now - it.next().timestamp > STRIKE_TTL_MS) {
                        it.remove();
                    }
                }
            }

            // Create markers for remaining strikes
            List<LightningStrike> snapshot;
            synchronized (strikes) {
                snapshot = new ArrayList<>(strikes);
            }

            int visibleCount = 0;
            for (int i = 0; i < snapshot.size(); i++) {
                LightningStrike s = snapshot.get(i);

                // Altitude filter
                if (!passesAltitudeFilter(s)) continue;
                visibleCount++;

                double ageFraction = Math.min(1.0,
                        (double) (now - s.timestamp) / STRIKE_TTL_MS);

                // Alpha: 255 (new) -> 50 (old)
                int alpha = (int) (255 - ageFraction * 205);

                // Fades bright yellow (new) -> dim orange (old).
                int red = 255;
                int green = (int) (255 - ageFraction * 110);  // 255 -> 145
                int blue = (int) (50 - ageFraction * 50);     // 50 -> 0
                int color = Color.argb(alpha, red, green, blue);

                String uid = "wx_lightning_" + i;
                Marker marker = new Marker(new GeoPoint(s.lat, s.lon), uid);
                marker.setType("a-X-G");
                marker.setMetaString("how", "m-g");
                marker.setMetaString("wx_type", "wx_lightning");
                marker.setMetaString("callsign",
                        String.format(Locale.US, "Strike %.4f,%.4f", s.lat, s.lon));

                long ageSeconds = (now - s.timestamp) / 1000;
                String ageStr = ageSeconds < 60
                        ? ageSeconds + "s ago"
                        : (ageSeconds / 60) + "m ago";
                marker.setTitle("Lightning  " + ageStr
                        + String.format(Locale.US, "\n%.4fN %.4fE", s.lat, s.lon)
                        + "\nAlt: " + s.altitudeM + " m");

                marker.setColor(color);
                marker.setClickable(true);
                marker.setVisible(true);

                group.addItem(marker);
            }

            notifyStrikeCount(visibleCount);
        });
    }

    /** Force a display refresh (e.g. after altitude filter change). */
    private void refreshDisplay() {
        if (active.get()) updateDisplay();
    }

    /** Clear all lightning markers from the map. */
    private void clearMarkers() {
        mapView.post(() -> {
            MapGroup group = getOrCreateGroup();
            for (MapItem mi : new ArrayList<>(group.getItems())) {
                if ("wx_lightning".equals(mi.getMetaString("wx_type", ""))) {
                    mi.removeFromGroup();
                }
            }
        });
        synchronized (strikes) {
            strikes.clear();
        }
    }

    // ── Proximity alert ───────────────────────────────────────────────────────

    /**
     * Check proximity alert: any strike within radius of given position
     * in the last 5 minutes triggers an alert via the StatusListener.
     */
    public void checkProximityAlert(double selfLat, double selfLon, double radiusKm) {
        long now = System.currentTimeMillis();
        long windowStart = now - PROXIMITY_WINDOW_MS;

        synchronized (strikes) {
            for (LightningStrike s : strikes) {
                if (s.timestamp < windowStart) continue;
                double distKm = haversineKm(selfLat, selfLon, s.lat, s.lon);
                if (distKm <= radiusKm) {
                    final double fDist = distKm;
                    final LightningStrike fStrike = s;
                    mainHandler.post(() -> {
                        if (statusListener != null) {
                            statusListener.onProximityAlert(fDist, fStrike);
                        }
                    });
                    return; // alert once per check
                }
            }
        }
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

    /**
     * Haversine distance in km between two lat/lon points.
     */
    private static double haversineKm(double lat1, double lon1,
                                       double lat2, double lon2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private void notifyStatus(String status) {
        mainHandler.post(() -> {
            if (statusListener != null) statusListener.onStatusChanged(status);
        });
    }

    private void notifyStrikeCount(int count) {
        mainHandler.post(() -> {
            if (statusListener != null) statusListener.onStrikeCountChanged(count);
        });
    }

    // ── Data class ────────────────────────────────────────────────────────────

    /**
     * A single lightning strike observation.
     */
    public static class LightningStrike {
        public double lat;
        public double lon;
        public long timestamp;
        public int altitudeM;
    }
}
