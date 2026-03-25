package com.atakmap.android.weather.data.remote.schema;

import android.content.Context;
import android.content.SharedPreferences;

import com.atakmap.coremap.log.Log;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles authentication injection into HTTP requests for generic weather API sources.
 *
 * <p>Supports three auth types defined in {@link AuthConfig}:</p>
 * <ul>
 *   <li><b>none</b> — No authentication required.</li>
 *   <li><b>queryParam</b> — API key appended as a URL query parameter.</li>
 *   <li><b>header</b> / <b>bearer</b> — API key sent as an HTTP header.</li>
 * </ul>
 *
 * <p>API keys are resolved in priority order:</p>
 * <ol>
 *   <li>Encrypted SharedPreferences (key: {@code "wx_apikey_{sourceId}"})</li>
 *   <li>{@code auth.value} field from the source definition JSON</li>
 *   <li>System property from {@code auth.envVar} (useful for CI/testing)</li>
 * </ol>
 */
public final class AuthProvider {

    private static final String TAG = "AuthProvider";
    private static final String PREFS_FILE = "weather_api_keys";
    private static final String KEY_PREFIX = "wx_apikey_";

    private AuthProvider() { /* utility */ }

    /**
     * Get the stored API key for a source, checking multiple sources in priority order.
     *
     * @param context  Android context for SharedPreferences access
     * @param sourceId unique source identifier
     * @param auth     auth configuration from the source definition
     * @return the API key, or null if none is configured
     */
    public static String getApiKey(Context context, String sourceId, AuthConfig auth) {
        // 1. Check SharedPreferences (user-stored key)
        if (context != null) {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREFS_FILE,
                        Context.MODE_PRIVATE);
                String stored = prefs.getString(KEY_PREFIX + sourceId, null);
                if (stored != null && !stored.isEmpty()) {
                    return stored;
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to read API key from prefs for " + sourceId, e);
            }
        }

        // 2. Check auth.value from definition
        if (auth != null && auth.getValue() != null && !auth.getValue().isEmpty()) {
            return auth.getValue();
        }

        // 3. Check system property from auth.envVar
        if (auth != null && auth.getEnvVar() != null && !auth.getEnvVar().isEmpty()) {
            try {
                String envVal = System.getProperty(auth.getEnvVar());
                if (envVal != null && !envVal.isEmpty()) {
                    return envVal;
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to read system property '" + auth.getEnvVar() + "'", e);
            }
        }

        return null;
    }

    /**
     * Store an API key for a source in SharedPreferences.
     *
     * @param context  Android context
     * @param sourceId unique source identifier
     * @param apiKey   the API key to store
     */
    public static void storeApiKey(Context context, String sourceId, String apiKey) {
        if (context == null || sourceId == null) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_FILE,
                    Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_PREFIX + sourceId, apiKey).apply();
            Log.d(TAG, "Stored API key for source: " + sourceId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to store API key for " + sourceId, e);
        }
    }

    /**
     * Remove a stored API key for a source.
     *
     * @param context  Android context
     * @param sourceId unique source identifier
     */
    public static void removeApiKey(Context context, String sourceId) {
        if (context == null || sourceId == null) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_FILE,
                    Context.MODE_PRIVATE);
            prefs.edit().remove(KEY_PREFIX + sourceId).apply();
            Log.d(TAG, "Removed API key for source: " + sourceId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to remove API key for " + sourceId, e);
        }
    }

    /**
     * Check if a source has a valid API key configured.
     *
     * @param context  Android context
     * @param sourceId unique source identifier
     * @param auth     auth configuration from the source definition
     * @return true if an API key is available from any source
     */
    public static boolean hasApiKey(Context context, String sourceId, AuthConfig auth) {
        if (auth == null || !auth.isRequired()) {
            return true; // No auth needed
        }
        return getApiKey(context, sourceId, auth) != null;
    }

    /**
     * Apply auth to a URL string. For query-param auth, appends the key as a
     * query parameter. For header auth, returns the URL unchanged (headers are
     * handled separately by {@link #getAuthHeaders(AuthConfig, String)}).
     *
     * @param url    the base URL (may already contain query params)
     * @param auth   auth configuration
     * @param apiKey the resolved API key (may be null for no-auth sources)
     * @return the URL, possibly with an appended query parameter
     */
    public static String applyToUrl(String url, AuthConfig auth, String apiKey) {
        if (auth == null || apiKey == null || apiKey.isEmpty()) {
            return url;
        }

        String type = auth.getType();
        if ("queryParam".equalsIgnoreCase(type) || "query_param".equalsIgnoreCase(type)
                || "query".equalsIgnoreCase(type)) {
            String paramName = auth.getQueryParam();
            if (paramName == null || paramName.isEmpty()) {
                paramName = "key"; // sensible default
            }
            char separator = url.contains("?") ? '&' : '?';
            return url + separator + paramName + "=" + apiKey;
        }

        // For header-based auth, URL is unchanged
        return url;
    }

    /**
     * Get auth headers to add to the HTTP request.
     *
     * @param auth   auth configuration
     * @param apiKey the resolved API key
     * @return map of header name -> value, or empty map for none/queryParam types
     */
    public static Map<String, String> getAuthHeaders(AuthConfig auth, String apiKey) {
        if (auth == null || apiKey == null || apiKey.isEmpty()) {
            return Collections.emptyMap();
        }

        String type = auth.getType();
        if (type == null || "none".equalsIgnoreCase(type)
                || "queryParam".equalsIgnoreCase(type)
                || "query_param".equalsIgnoreCase(type)
                || "query".equalsIgnoreCase(type)) {
            return Collections.emptyMap();
        }

        Map<String, String> headers = new HashMap<>();

        if ("bearer".equalsIgnoreCase(type)) {
            headers.put("Authorization", "Bearer " + apiKey);
        } else if ("header".equalsIgnoreCase(type) || "apiKey".equalsIgnoreCase(type)
                || "api_key".equalsIgnoreCase(type)) {
            String headerName = auth.getHeaderName();
            if (headerName == null || headerName.isEmpty()) {
                headerName = "X-API-Key"; // sensible default
            }
            headers.put(headerName, apiKey);
        } else if ("basic".equalsIgnoreCase(type)) {
            // Basic auth: apiKey should be "user:password" base64 encoded
            headers.put("Authorization", "Basic " + apiKey);
        }

        return Collections.unmodifiableMap(headers);
    }
}
