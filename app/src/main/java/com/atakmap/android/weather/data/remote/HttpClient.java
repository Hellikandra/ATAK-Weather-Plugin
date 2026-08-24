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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/**
 * Minimal async HTTP GET client with deduplication and retry.
 *
 * <h3>Sprint 23 improvements</h3>
 * <ul>
 *   <li><b>S23.2 — Request deduplication:</b> If a GET is in-flight for a URL,
 *       subsequent calls for the same URL attach their callbacks to the existing
 *       request instead of firing a new one.</li>
 *   <li><b>S23.3 — Retry with exponential backoff:</b> Transient failures
 *       (timeout, 5xx, IOException) retry up to 3 times with 1s/2s/4s delays.
 *       Permanent failures (4xx except 429) fail immediately.</li>
 * </ul>
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

    private static final ExecutorService EXECUTOR =
            Executors.newFixedThreadPool(4);
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
        // ── Dedup check ─────────────────────────────────────────────────
        List<Callback> existing = IN_FLIGHT.get(urlString);
        if (existing != null) {
            synchronized (existing) {
                // Double-check under lock (request may have completed between get and lock)
                if (IN_FLIGHT.containsKey(urlString)) {
                    existing.add(callback);
                    Log.d(TAG, "Dedup: attached callback to in-flight request for " + urlString);
                    return;
                }
            }
        }

        // Create new in-flight entry
        List<Callback> callbacks = new ArrayList<>();
        callbacks.add(callback);
        IN_FLIGHT.put(urlString, callbacks);

        // ── Execute with retry ──────────────────────────────────────────
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
                    long delay = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
                    Log.w(TAG, "HTTP " + status + " — retry " + (attempt + 1)
                            + "/" + MAX_RETRIES + " in " + delay + "ms: " + urlString);
                    try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
                    executeWithRetry(urlString, attempt + 1);
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
                long delay = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
                Log.w(TAG, "IOException — retry " + (attempt + 1)
                        + "/" + MAX_RETRIES + " in " + delay + "ms");
                try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
                executeWithRetry(urlString, attempt + 1);
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
}
