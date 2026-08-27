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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LightningOverlayManager -- Displays lightning strike markers on the ATAK map.
 *
 * <h3>Data source — read this before trusting anything on screen</h3>
 * <b>There is currently no live lightning feed.</b> The Blitzortung WebSocket
 * protocol was never implemented; the connection attempt below only proves the
 * host is reachable and then gives up.
 *
 * <p>Until a real feed exists (issue #23), enabling this overlay displays
 * nothing at all. A developer simulation mode can be switched on from
 * ATAK → Settings → Tool Preferences → WeatherTool, but it is <b>off by
 * default</b> and everything it produces is labelled SIMULATED — in the marker
 * callsign, in the marker title, in the overlay status line and in the
 * proximity alert. It renders magenta rather than the amber of a real strike.
 *
 * <p>This matters more than it looks. Before finding F6 was raised, this class
 * fabricated a strike every three seconds within ~50 km of map centre and added
 * it as an ordinary clickable marker, indistinguishable from an observation once
 * the status text scrolled away. On a tactical map that is worse than showing
 * no lightning layer at all.
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

    private static final String WS_URL = "wss://ws1.blitzortung.org/";
    private static final int MAX_STRIKES = 500;
    private static final long STRIKE_TTL_MS = 30 * 60 * 1000L;  // 30 min display
    private static final long DISPLAY_REFRESH_MS = 10_000L;       // refresh every 10s
    private static final long SIM_STRIKE_INTERVAL_MS = 3_000L;    // new simulated strike every 3s

    /**
     * Host preference gating the developer simulation. Absent or false means no
     * synthetic strike is ever generated. See res/xml/preferences.xml.
     */
    public static final String PREF_SIMULATION = "weather_lightning_simulation";
    private static final long PROXIMITY_WINDOW_MS = 5 * 60 * 1000L; // 5 min window

    private static final String GROUP_NAME = "Weather Lightning";

    private final MapView mapView;
    private final List<LightningStrike> strikes = new ArrayList<>();
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Thread wsThread;

    /**
     * True only while the developer simulation is producing strikes. Never set
     * unless {@link #PREF_SIMULATION} was explicitly enabled.
     */
    private boolean simulating = false;

    /** Read from preferences on each start(); defaults to off. */
    private boolean simulationAllowed = false;

    private final Random random = new Random();

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

    /** True when the strikes on screen are fabricated rather than observed. */
    public boolean isSimulating() { return simulating; }

    /**
     * Override the preference, for tests. Production reads
     * {@link #PREF_SIMULATION} from the host preferences in {@link #start()}.
     */
    public void setSimulationAllowed(boolean allowed) { this.simulationAllowed = allowed; }

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
        simulating = false;
        simulationAllowed = readSimulationPreference();
        notifyStatus("Connecting...");
        connectWebSocket();
        startDisplayRefresh();
    }

    /**
     * Read the simulation gate from the host's default preferences.
     *
     * <p>Uses {@code mapView.getContext()} — the host activity context. The
     * plugin context has no data directory and its getSharedPreferences fails
     * with mkdir ENOENT; see the "ATAK Plugin Context" rule in CLAUDE.md.
     */
    private boolean readSimulationPreference() {
        try {
            return android.preference.PreferenceManager
                    .getDefaultSharedPreferences(mapView.getContext())
                    .getBoolean(PREF_SIMULATION, false);
        } catch (Exception e) {
            // Fail closed: if the preference cannot be read, do not fabricate data.
            Log.w(TAG, "Could not read " + PREF_SIMULATION + "; simulation stays off", e);
            return false;
        }
    }

    /** Stop listening and remove all markers. */
    public void stop() {
        if (!active.compareAndSet(true, false)) return;
        simulating = false;
        disconnectWebSocket();
        mainHandler.removeCallbacksAndMessages(null);
        clearMarkers();
        notifyStatus("Lightning: Off");
        notifyStrikeCount(0);
    }

    /** Clean up resources. Call from WeatherMapComponent.onDestroyImpl(). */
    public void dispose() {
        stop();
    }

    // ── WebSocket connection ──────────────────────────────────────────────────

    /**
     * Attempt to connect to the Blitzortung WebSocket.
     * If connection fails, fall back to demo mode.
     */
    private void connectWebSocket() {
        wsThread = new Thread(() -> {
            try {
                // Attempt a simple socket connection to verify reachability
                // Blitzortung may not be available or may have usage restrictions
                java.net.Socket testSocket = new java.net.Socket();
                testSocket.connect(
                        new java.net.InetSocketAddress("ws1.blitzortung.org", 443),
                        5000);
                testSocket.close();

                // The host is reachable, but that is all this proves. The
                // Blitzortung subscription protocol is not implemented, so there
                // is no live data either way.
                Log.d(TAG, "Blitzortung host reachable, but the WS protocol is "
                        + "not implemented -- no live lightning data available");
                onNoLiveSource();

            } catch (Exception e) {
                Log.w(TAG, "Lightning source unreachable: " + e.getMessage());
                onNoLiveSource();
            }
        }, "Lightning-WS");
        wsThread.setDaemon(true);
        wsThread.start();
    }

    private void disconnectWebSocket() {
        if (wsThread != null) {
            wsThread.interrupt();
            wsThread = null;
        }
    }

    // ── No live source ────────────────────────────────────────────────────────

    /**
     * Called when no real lightning data can be obtained — which, until issue
     * #23 is done, is always.
     *
     * <p>Shows an empty overlay and says so. Only starts the simulation if a
     * developer explicitly enabled {@link #PREF_SIMULATION}.
     */
    private void onNoLiveSource() {
        if (!active.get()) return;
        if (simulationAllowed) {
            startSimulation();
        } else {
            Log.i(TAG, "No live lightning source and simulation is disabled — "
                    + "the overlay will stay empty.");
            notifyStatus("Lightning: no live source");
            notifyStrikeCount(0);
        }
    }

    // ── Developer simulation (off by default) ─────────────────────────────────

    /**
     * Fabricates strikes near map centre so the overlay's rendering, filtering
     * and proximity logic can be exercised without a feed.
     *
     * <p>Every strike it produces is flagged {@link LightningStrike#simulated},
     * which drives a magenta marker, a SIMULATED callsign and title, a SIMULATED
     * status line and a SIMULATED proximity toast. Nothing here may ever be
     * mistaken for an observation.
     */
    private void startSimulation() {
        if (!active.get()) return;
        simulating = true;
        Log.w(TAG, "Lightning SIMULATION enabled — every strike shown is fabricated.");
        mainHandler.post(() -> notifyStatus("Lightning: SIMULATED DATA — not real"));

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!active.get() || !simulating) return;
                generateSimulatedStrike();
                mainHandler.postDelayed(this, SIM_STRIKE_INTERVAL_MS);
            }
        });
    }

    private void generateSimulatedStrike() {
        GeoPoint center = mapView.getCenterPoint().get();
        if (center == null) return;

        double lat = center.getLatitude();
        double lon = center.getLongitude();

        // Random offset within ~50km of center
        double offsetLat = (random.nextDouble() - 0.5) * 0.9;  // ~50km
        double offsetLon = (random.nextDouble() - 0.5) * 0.9;

        LightningStrike strike = new LightningStrike();
        strike.lat = lat + offsetLat;
        strike.lon = lon + offsetLon;
        strike.timestamp = System.currentTimeMillis();
        strike.altitudeM = random.nextInt(12000) + 1000;
        strike.simulated = true;

        addStrike(strike);
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

                // Real strikes fade bright yellow -> dim orange. Simulated ones
                // are magenta, so they never read as an observation at a glance.
                int color;
                if (s.simulated) {
                    color = Color.argb(alpha, 220, 60, 220);
                } else {
                    int red = 255;
                    int green = (int) (255 - ageFraction * 110);  // 255 -> 145
                    int blue = (int) (50 - ageFraction * 50);     // 50 -> 0
                    color = Color.argb(alpha, red, green, blue);
                }

                String uid = "wx_lightning_" + i;
                Marker marker = new Marker(new GeoPoint(s.lat, s.lon), uid);
                marker.setType("a-X-G");
                marker.setMetaString("how", "m-g");
                marker.setMetaString("wx_type", "wx_lightning");
                marker.setMetaBoolean("wx_simulated", s.simulated);

                // The callsign is what shows on the map and in Overlay Manager,
                // and it is often all the operator sees. Lead with SIMULATED.
                marker.setMetaString("callsign", s.simulated
                        ? String.format(Locale.US, "SIMULATED strike %.4f,%.4f", s.lat, s.lon)
                        : String.format(Locale.US, "Strike %.4f,%.4f", s.lat, s.lon));

                long ageSeconds = (now - s.timestamp) / 1000;
                String ageStr = ageSeconds < 60
                        ? ageSeconds + "s ago"
                        : (ageSeconds / 60) + "m ago";
                marker.setTitle((s.simulated
                                ? "\u26A0 SIMULATED \u2014 NOT REAL DATA\nLightning  "
                                : "Lightning  ") + ageStr
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

        /**
         * True when this strike was fabricated by the developer simulation
         * rather than observed. Anything that surfaces a strike to the user —
         * marker, title, status line, proximity alert — must check this and say
         * so. Finding F6.
         */
        public boolean simulated;
    }
}
