package com.atakmap.android.weather.data.remote;

import android.os.Handler;
import android.os.Looper;

import com.atakmap.coremap.log.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/**
 * Minimal async HTTP GET client with deduplication and retry.
 *
 * <h3>Behaviour</h3>
 * <ul>
 *   <li><b>Request deduplication:</b> If a GET is in-flight for a URL,
 *       subsequent calls for the same URL attach their callbacks to the existing
 *       request instead of firing a new one.</li>
 *   <li><b>Retry with exponential backoff:</b> Transient failures
 *       (timeout, 5xx, IOException) retry up to 3 times with 1s/2s/4s delays.
 *       Permanent failures (4xx except 429) fail immediately.</li>
 * </ul>
 *
 * <h3>Two invariants worth stating (findings F4, F5)</h3>
 * <ol>
 *   <li><b>Every callback is answered exactly once.</b> Registration into
 *       {@link #IN_FLIGHT} uses {@code putIfAbsent} so two threads racing on the
 *       same URL cannot each install a list — the loser attaches to the winner's.
 *       An earlier check-then-put let the second call replace the first list, and
 *       the displaced callback was never invoked at all: no success, no failure,
 *       and a UI left on its loading state forever.</li>
 *   <li><b>Backoff does not occupy a thread or a socket.</b> Retries are
 *       rescheduled on a {@link ScheduledExecutorService} rather than slept
 *       through in place, and the connection is closed before the wait. With a
 *       4-thread pool and 1+2+4s of sleeping, four rate-limited URLs used to
 *       block every other weather request behind them — which is precisely the
 *       situation Open-Meteo's ~10 req/min limit creates.</li>
 * </ol>
 */
public final class HttpClient {

    private static final String TAG = "WeatherHttpClient";
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_RETRIES = 3;
    private static final long[] BACKOFF_MS = {1_000, 2_000, 4_000};

    public interface Callback {
        void onSuccess(String body);
        void onFailure(String error);
    }

    /**
     * Scheduled rather than fixed so a backoff can be queued instead of slept
     * through — see invariant 2 in the class javadoc.
     */
    private static final ScheduledExecutorService EXECUTOR =
            Executors.newScheduledThreadPool(4);
    private static final Handler MAIN_HANDLER =
            new Handler(Looper.getMainLooper());

    /** In-flight request dedup map: URL → list of pending callbacks. */
    private static final ConcurrentHashMap<String, List<Callback>> IN_FLIGHT =
            new ConcurrentHashMap<>();

    private HttpClient() {}

    /**
     * Perform an HTTPS GET on a background thread; deliver result on the
     * main (UI) thread via {@code callback}.
     *
     * <p>If an identical URL is already in-flight, the callback is attached
     * to the existing request (deduplication).</p>
     */
    public static void get(final String urlString, final Callback callback) {
        if (callback == null) return;

        // Register atomically. A check-then-put here is a lost-callback bug:
        // two threads racing on the same URL both see no entry, both install a
        // list, and the second put discards the first — whose callback is then
        // never invoked at all. Finding F4.
        final List<Callback> mine = new ArrayList<>();
        mine.add(callback);

        final List<Callback> winner = IN_FLIGHT.putIfAbsent(urlString, mine);
        if (winner != null) {
            // Someone else owns this URL. Attach to their list, unless they
            // finished between putIfAbsent and the lock — deliverX() removes the
            // entry before notifying, so an absent key means the result is
            // already on its way and this callback would otherwise be stranded.
            synchronized (winner) {
                if (IN_FLIGHT.get(urlString) == winner) {
                    winner.add(callback);
                    Log.d(TAG, "Dedup: attached callback to in-flight request for " + urlString);
                    return;
                }
            }
            // The in-flight request completed underneath us. Start a fresh one
            // rather than dropping the caller.
            EXECUTOR.execute(() -> get(urlString, callback));
            return;
        }

        EXECUTOR.execute(() -> executeWithRetry(urlString, 0));
    }

    /**
     * Execute GET with retry logic. Transient failures retry with backoff;
     * permanent failures (4xx except 429) fail immediately.
     */
    private static void executeWithRetry(String urlString, int attempt) {
        HttpsURLConnection connection = null;
        BufferedReader reader = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpsURLConnection) url.openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.connect();

            int status = connection.getResponseCode();

            // 204 No Content — valid response (e.g., AWC SIGMET when none active)
            if (status == HttpsURLConnection.HTTP_NO_CONTENT) {
                deliverSuccess(urlString, "[]");
                return;
            }

            // 200 OK — read body
            if (status == HttpsURLConnection.HTTP_OK) {
                InputStream stream = connection.getInputStream();
                reader = new BufferedReader(new InputStreamReader(stream));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                deliverSuccess(urlString, sb.toString());
                return;
            }

            // 429 Too Many Requests or 5xx — transient, retry
            if (status == 429 || status >= 500) {
                if (attempt < MAX_RETRIES) {
                    scheduleRetry(urlString, attempt, "HTTP " + status);
                    return;
                }
                deliverFailure(urlString, "HTTP " + status + " after " + MAX_RETRIES + " retries");
                return;
            }

            // 4xx (except 429) — permanent failure, no retry
            deliverFailure(urlString, "HTTP " + status);

        } catch (IOException e) {
            Log.e(TAG, "GET failed (attempt " + attempt + "): " + urlString, e);
            if (attempt < MAX_RETRIES) {
                scheduleRetry(urlString, attempt, String.valueOf(e.getMessage()));
            } else {
                deliverFailure(urlString, e.getMessage());
            }
        } finally {
            if (connection != null) connection.disconnect();
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Queue the next attempt after the backoff delay.
     *
     * <p>Returning rather than sleeping matters twice over: the pool thread is
     * released for other requests, and — because this runs before the caller's
     * {@code finally} — the socket for the failed attempt is closed while we
     * wait rather than being held open across it. Finding F5.
     */
    private static void scheduleRetry(String urlString, int attempt, String reason) {
        long delay = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
        Log.w(TAG, reason + " — retry " + (attempt + 1) + "/" + MAX_RETRIES
                + " in " + delay + "ms: " + urlString);
        try {
            EXECUTOR.schedule(() -> executeWithRetry(urlString, attempt + 1),
                    delay, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Pool shut down mid-flight (plugin teardown). Answer the caller
            // rather than leaving it waiting on a retry that will never run.
            deliverFailure(urlString, "cancelled: " + reason);
        }
    }

    // ── Delivery helpers (dedup-aware) ────────────────────────────────────

    private static void deliverSuccess(String urlString, String body) {
        List<Callback> callbacks = IN_FLIGHT.remove(urlString);
        if (callbacks == null) return;
        synchronized (callbacks) {
            for (Callback cb : callbacks) {
                MAIN_HANDLER.post(() -> cb.onSuccess(body));
            }
        }
    }

    private static void deliverFailure(String urlString, String msg) {
        List<Callback> callbacks = IN_FLIGHT.remove(urlString);
        if (callbacks == null) return;
        String safeMsg = msg != null ? msg : "Unknown error";
        synchronized (callbacks) {
            for (Callback cb : callbacks) {
                MAIN_HANDLER.post(() -> cb.onFailure(safeMsg));
            }
        }
    }

    /** Returns the number of currently in-flight requests (for diagnostics). */
    public static int getInFlightCount() {
        return IN_FLIGHT.size();
    }

    /**
     * Stop the pool and fail anything still waiting. Call from
     * {@code WeatherMapComponent.onDestroyImpl}.
     *
     * <p>Without this the four pool threads outlive plugin teardown, and an
     * in-flight callback can fire into a torn-down UI. Every pending caller is
     * answered here rather than left hanging — the same invariant the dedup map
     * upholds during normal operation. Finding F13.
     *
     * <p>Idempotent, and safe to call when nothing is in flight.
     */
    public static void shutdown() {
        EXECUTOR.shutdownNow();
        for (String url : new ArrayList<>(IN_FLIGHT.keySet())) {
            deliverFailure(url, "cancelled: plugin shutting down");
        }
        Log.d(TAG, "HttpClient shut down");
    }
}
