package com.atakmap.android.weather.data.remote.schema;

import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Navigates nested JSON objects using dot-separated paths.
 *
 * <p>For example, {@code "radar.past"} navigates
 * {@code root.getJSONObject("radar").get("past")}.</p>
 *
 * <p>Each path segment is resolved left-to-right. Intermediate segments must
 * be {@link JSONObject}s; the final segment may be any JSON type
 * ({@link JSONObject}, {@link JSONArray}, or a primitive wrapper).</p>
 */
public final class ResponsePathNavigator {

    private static final String TAG = "ResponsePathNav";

    private ResponsePathNavigator() { /* utility */ }

    /**
     * Navigate a JSON object by dot-separated path.
     *
     * @param root the root JSON object
     * @param path dot-separated path (e.g. "radar.past", "hourly", "data.current.weather")
     * @return the value at the end of the path (JSONObject, JSONArray, or primitive wrapper),
     *         or {@code null} if the path is null/empty or navigation fails
     */
    public static Object navigate(JSONObject root, String path) {
        if (root == null || path == null || path.isEmpty()) {
            return root;
        }
        try {
            String[] segments = path.split("\\.");
            Object current = root;
            for (String segment : segments) {
                if (current instanceof JSONObject) {
                    current = ((JSONObject) current).get(segment);
                } else {
                    Log.w(TAG, "Cannot navigate into non-object at segment: " + segment
                            + " (path: " + path + ")");
                    return null;
                }
            }
            return current;
        } catch (Exception e) {
            Log.w(TAG, "Navigation failed for path '" + path + "': " + e.getMessage());
            return null;
        }
    }

    /**
     * Navigate and return as {@link JSONObject}.
     *
     * @param root the root JSON object
     * @param path dot-separated path
     * @return the JSONObject at the path, or {@code null} if not found or not an object
     */
    public static JSONObject navigateToObject(JSONObject root, String path) {
        Object result = navigate(root, path);
        if (result instanceof JSONObject) {
            return (JSONObject) result;
        }
        if (result != null) {
            Log.w(TAG, "Expected JSONObject at path '" + path + "' but got "
                    + result.getClass().getSimpleName());
        }
        return null;
    }

    /**
     * Navigate and return as {@link JSONArray}.
     *
     * @param root the root JSON object
     * @param path dot-separated path
     * @return the JSONArray at the path, or {@code null} if not found or not an array
     */
    public static JSONArray navigateToArray(JSONObject root, String path) {
        Object result = navigate(root, path);
        if (result instanceof JSONArray) {
            return (JSONArray) result;
        }
        if (result != null) {
            Log.w(TAG, "Expected JSONArray at path '" + path + "' but got "
                    + result.getClass().getSimpleName());
        }
        return null;
    }
}
