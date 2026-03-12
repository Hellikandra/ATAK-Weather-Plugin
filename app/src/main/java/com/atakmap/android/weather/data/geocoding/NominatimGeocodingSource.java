package com.atakmap.android.weather.data.geocoding;

import com.atakmap.android.weather.data.remote.HttpClient;
import com.atakmap.android.weather.domain.model.LocationSnapshot;
import com.atakmap.android.weather.domain.model.LocationSource;
import com.atakmap.android.weather.domain.repository.IGeocodingRepository;
import com.atakmap.android.weather.util.CoordFormatter;
import com.atakmap.coremap.log.Log;

import org.json.JSONObject;

/**
 * IGeocodingRepository backed by Nominatim (OpenStreetMap).
 *
 * Sprint 1 changes:
 *  - reverseGeocode now accepts a LocationSource and returns a LocationSnapshot
 *  - onError fallback: instead of propagating the error, builds a snapshot
 *    with a coords-only displayName so the UI always shows something meaningful
 *    ("50.6971, 5.2583") rather than "Unknown location"
 */
public class NominatimGeocodingSource implements IGeocodingRepository {

    private static final String TAG = "NominatimGeocoding";

    // zoom=18 → building-level detail; format=jsonv2 → stable response
    private static final String BASE_URL =
            "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18";

    @Override
    public void reverseGeocode(double latitude, double longitude,
                               LocationSource source,
                               Callback callback) {

        // Build fallback snapshot immediately — used if network/parse fails
        // so the UI never shows "Unknown location"
        final LocationSnapshot fallback = new LocationSnapshot(
                latitude, longitude,
                LocationSnapshot.coordsFallback(latitude, longitude),
                source);

        String url = BASE_URL
                + "&lat=" + CoordFormatter.format(latitude)
                + "&lon=" + CoordFormatter.format(longitude);

        HttpClient.get(url, new HttpClient.Callback() {
            @Override
            public void onSuccess(String body) {
                try {
                    JSONObject json = new JSONObject(body);

                    String displayName = json.optString("display_name", "");
                    String shortName   = extractShortName(json);

                    // "Waremme — 17, Avenue Guillaume Joachim, …"
                    String label = shortName.isEmpty()
                            ? displayName
                            : shortName + " — " + displayName;

                    // If Nominatim returned nothing useful, fall back to coords
                    if (label.trim().isEmpty()) {
                        label = LocationSnapshot.coordsFallback(latitude, longitude);
                    }

                    callback.onSuccess(new LocationSnapshot(latitude, longitude, label, source));

                } catch (Exception e) {
                    Log.e(TAG, "Geocoding parse error", e);
                    // Fallback: coords string — never surface "Unknown location"
                    callback.onSuccess(fallback);
                }
            }

            @Override
            public void onFailure(String error) {
                Log.w(TAG, "Geocoding network failure: " + error);
                // Fallback: coords string — still a success from the UI perspective
                callback.onSuccess(fallback);
            }
        });
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private static String extractShortName(JSONObject json) {
        try {
            JSONObject address = json.optJSONObject("address");
            if (address == null) return "";
            String[] keys = { "town", "city", "city_district", "municipality", "county", "state" };
            for (String key : keys) {
                String val = address.optString(key, "");
                if (!val.isEmpty()) return val;
            }
        } catch (Exception ignored) {}
        return "";
    }
}
