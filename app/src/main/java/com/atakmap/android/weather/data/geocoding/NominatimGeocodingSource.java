package com.atakmap.android.weather.data.geocoding;

import com.atakmap.android.weather.data.remote.HttpClient;
import com.atakmap.android.weather.domain.repository.IGeocodingRepository;
import com.atakmap.android.weather.util.CoordFormatter;
import com.atakmap.coremap.log.Log;

import org.json.JSONObject;

/**
 * IGeocodingRepository backed by Nominatim (OpenStreetMap).
 *
 * Correct URL format (jsonv2):
 *   https://nominatim.openstreetmap.org/reverse
 *     ?lat=50.697&lon=5.258&zoom=18&format=jsonv2
 *
 * Response key used: "display_name"
 * Example: "17, Avenue Guillaume Joachim, Waremme, Liège, Wallonia, 4300, Belgium"
 *
 * Short label extracted from address.town / address.city as a prefix.
 */
public class NominatimGeocodingSource implements IGeocodingRepository {

    private static final String TAG = "NominatimGeocoding";

    // zoom=18 → building-level detail (most precise)
    // format=jsonv2 → stable v2 response format
    private static final String BASE_URL =
            "https://nominatim.openstreetmap.org/reverse" +
                    "?format=jsonv2&zoom=18";

    @Override
    public void reverseGeocode(double latitude, double longitude,
                               Callback callback) {
        String url = BASE_URL
                + "&lat=" + CoordFormatter.format(latitude)
                + "&lon=" + CoordFormatter.format(longitude);

        HttpClient.get(url, new HttpClient.Callback() {
            @Override
            public void onSuccess(String body) {
                try {
                    JSONObject json = new JSONObject(body);

                    // Full display name e.g.
                    // "17, Avenue Guillaume Joachim, Waremme, Liège, Wallonia, 4300, Belgium"
                    String displayName = json.optString("display_name", "");

                    // Short label: town/city for the header TextView
                    String shortName = extractShortName(json);

                    // Combine: "Waremme — 17, Avenue Guillaume Joachim, …"
                    String label = shortName.isEmpty()
                            ? displayName
                            : shortName + " — " + displayName;

                    callback.onSuccess(label);

                } catch (Exception e) {
                    Log.e(TAG, "Geocoding parse error", e);
                    callback.onError("Parse error: " + e.getMessage());
                }
            }

            @Override
            public void onFailure(String error) {
                callback.onError(error);
            }
        });
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private static String extractShortName(JSONObject json) {
        try {
            JSONObject address = json.optJSONObject("address");
            if (address == null) return "";
            // Priority order for a readable short label
            String[] keys = { "town", "city", "city_district", "municipality", "county", "state" };
            for (String key : keys) {
                String val = address.optString(key, "");
                if (!val.isEmpty()) return val;
            }
        } catch (Exception ignored) {}
        return "";
    }
}
