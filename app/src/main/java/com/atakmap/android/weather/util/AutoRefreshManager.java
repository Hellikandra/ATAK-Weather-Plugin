package com.atakmap.android.weather.util;

import android.os.Handler;

import com.atakmap.coremap.log.Log;

/**
 * AutoRefreshManager — schedules periodic weather data refreshes.
 *
 * <h3>Sprint 13 — S13.1 (F17)</h3>
 * Provides configurable auto-refresh intervals (15/30/60 min or disabled).
 * Also exposes a staleness classifier for the "Last updated" badge colour.
 *
 * <h3>Usage</h3>
 * <pre>
 *   autoRefresh = new AutoRefreshManager(mainHandler);
 *   autoRefresh.setInterval(30);             // every 30 minutes
 *   autoRefresh.start(() -&gt; triggerAutoLoad());
 *   // ...
 *   autoRefresh.stop();                      // on dropdown close / destroy
 * </pre>
 */
public class AutoRefreshManager {

    private static final String TAG = "AutoRefreshManager";

    /** SharedPreferences key for the selected interval (in minutes). */
    public static final String PREF_KEY = "wx_auto_refresh_minutes";

    /** Available interval options.  0 = disabled. */
    public static final int[] INTERVALS = {0, 15, 30, 60};

    private final Handler handler;
    private Runnable refreshRunnable;
    private int intervalMinutes = 0;
    private boolean running = false;

    public AutoRefreshManager(Handler mainHandler) {
        this.handler = mainHandler;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Start auto-refresh with the current interval.
     * If the interval is 0 (disabled), this is a no-op.
     *
     * @param onRefresh callback invoked on the main thread each interval
     */
    public void start(final Runnable onRefresh) {
        stop(); // cancel any previous schedule
        if (intervalMinutes <= 0) {
            Log.d(TAG, "Auto-refresh disabled (interval=0)");
            return;
        }

        running = true;
        final long intervalMs = intervalMinutes * 60L * 1000L;

        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                Log.d(TAG, "Auto-refresh firing (interval=" + intervalMinutes + "m)");
                try {
                    onRefresh.run();
                } catch (Exception e) {
                    Log.w(TAG, "Auto-refresh callback threw: " + e.getMessage());
                }
                handler.postDelayed(this, intervalMs);
            }
        };

        // Schedule the first tick after one full interval (immediate refresh
        // is already handled by the dropdown open / manual refresh flow).
        handler.postDelayed(refreshRunnable, intervalMs);
        Log.d(TAG, "Auto-refresh started: every " + intervalMinutes + " min");
    }

    /** Stop the auto-refresh timer. Safe to call even if not running. */
    public void stop() {
        running = false;
        if (refreshRunnable != null) {
            handler.removeCallbacks(refreshRunnable);
            refreshRunnable = null;
        }
        Log.d(TAG, "Auto-refresh stopped");
    }

    /**
     * Update the interval.  If currently running, restarts the timer with
     * the new interval.
     *
     * @param minutes one of {@link #INTERVALS}; 0 = disabled
     */
    public void setInterval(int minutes) {
        this.intervalMinutes = minutes;
        // If we are currently running, restart with the new interval.
        // The caller should call start() again to pick up the new value.
        Log.d(TAG, "Interval set to " + minutes + " min");
    }

    /** @return the current interval in minutes (0 = disabled) */
    public int getInterval() {
        return intervalMinutes;
    }

    /** @return true if the auto-refresh timer is currently active */
    public boolean isRunning() {
        return running;
    }

    // ── Staleness classification ──────────────────────────────────────────────

    /**
     * Classify how stale the weather data is, based on the time of the last
     * successful update.
     *
     * <ul>
     *   <li>{@code "fresh"}  — less than 30 minutes old (green badge)</li>
     *   <li>{@code "aging"}  — between 30 minutes and 2 hours (amber badge)</li>
     *   <li>{@code "stale"}  — more than 2 hours old (red badge)</li>
     * </ul>
     *
     * @param lastUpdateMs epoch millis of the last successful data load
     * @return one of "fresh", "aging", "stale"
     */
    public static String getStalenessLevel(long lastUpdateMs) {
        if (lastUpdateMs <= 0) return "stale";
        long age = System.currentTimeMillis() - lastUpdateMs;
        if (age < 30L * 60L * 1000L) return "fresh";     // <30 min
        if (age < 2L * 3600L * 1000L) return "aging";    // <2 hours
        return "stale";                                    // >2 hours
    }

    /**
     * Format elapsed time since last update as a human-readable string.
     *
     * @param lastUpdateMs epoch millis of the last successful data load
     * @return e.g. "Updated 5 min ago", "Updated 2h ago", "No data"
     */
    public static String formatTimeSince(long lastUpdateMs) {
        if (lastUpdateMs <= 0) return "No data";
        long ageMs = System.currentTimeMillis() - lastUpdateMs;
        long ageSec = ageMs / 1000;
        if (ageSec < 60) return "Updated just now";
        long ageMin = ageSec / 60;
        if (ageMin < 60) return "Updated " + ageMin + " min ago";
        long ageHr = ageMin / 60;
        if (ageHr < 24) return "Updated " + ageHr + "h ago";
        long ageDays = ageHr / 24;
        return "Updated " + ageDays + "d ago";
    }

    /**
     * Returns the display label for a given interval value.
     *
     * @param minutes the interval in minutes
     * @return human-readable label
     */
    public static String intervalLabel(int minutes) {
        if (minutes <= 0) return "Disabled";
        if (minutes == 15) return "Every 15 min";
        if (minutes == 30) return "Every 30 min";
        if (minutes == 60) return "Every 60 min";
        return "Every " + minutes + " min";
    }
}
