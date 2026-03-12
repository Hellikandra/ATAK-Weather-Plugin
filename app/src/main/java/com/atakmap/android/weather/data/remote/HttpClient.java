package com.atakmap.android.weather.data.remote;

import android.os.Handler;
import android.os.Looper;

import com.atakmap.coremap.log.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/**
 * Minimal async HTTP GET client.
 *
 * Replaces the three duplicated AsyncTask inner-classes that existed in the
 * original code (GetURLData, GetURL_WindData, GetGeocodeData).
 *
 * Uses an ExecutorService + main-thread Handler instead of the deprecated
 * AsyncTask (removed in Android API 33 / Android 13).
 */
public final class HttpClient {

    private static final String TAG = "WeatherHttpClient";
    private static final int TIMEOUT_MS = 10_000;

    public interface Callback {
        void onSuccess(String body);
        void onFailure(String error);
    }

    // Fixed pool: at most 4 concurrent network requests.
    // newCachedThreadPool() is unbounded and can open hundreds of threads
    // during a network storm (many rapid weather requests on slow connections).
    private static final ExecutorService EXECUTOR =
            Executors.newFixedThreadPool(4);
    private static final Handler MAIN_HANDLER =
            new Handler(Looper.getMainLooper());

    private HttpClient() {}

    /**
     * Perform an HTTPS GET on a background thread; deliver result on the
     * main (UI) thread via {@code callback}.
     */
    public static void get(final String urlString, final Callback callback) {
        EXECUTOR.execute(() -> {
            HttpsURLConnection connection = null;
            BufferedReader reader = null;
            try {
                URL url = new URL(urlString);
                connection = (HttpsURLConnection) url.openConnection();
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                connection.connect();

                int status = connection.getResponseCode();
                if (status != HttpsURLConnection.HTTP_OK) {
                    deliverFailure(callback, "HTTP " + status);
                    return;
                }

                InputStream stream = connection.getInputStream();
                reader = new BufferedReader(new InputStreamReader(stream));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                final String body = sb.toString();
                MAIN_HANDLER.post(() -> callback.onSuccess(body));

            } catch (IOException e) {
                Log.e(TAG, "GET failed: " + urlString, e);
                deliverFailure(callback, e.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
                if (reader != null) {
                    try { reader.close(); } catch (IOException ignored) {}
                }
            }
        });
    }

    private static void deliverFailure(Callback cb, String msg) {
        MAIN_HANDLER.post(() -> cb.onFailure(msg != null ? msg : "Unknown error"));
    }
}
