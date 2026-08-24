package com.atakmap.android.weather.overlay.wind;

import android.content.Context;

import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight bundled city database for wind arrow placement.
 *
 * <p>Loads ~120 cities from {@code assets/cities_wind.json}.
 * Each city has: name, lat, lon, rank (2=capital, 3=major, 4=large, 5=medium).
 * Rank determines at which zoom level the city appears.</p>
 *
 * <p>Zoom-to-rank mapping:</p>
 * <ul>
 *   <li>Continental (>500km scale): rank 2–3 (capitals + major)</li>
 *   <li>Country (100-500km): rank 2–4</li>
 *   <li>Region (30-100km): rank 2–5</li>
 *   <li>City (<30km): all ranks</li>
 * </ul>
 */
public class CityWindDatabase {

    private static final String TAG = "CityWindDB";

    public static class City {
        public final String name;
        public final double lat;
        public final double lon;
        public final int rank;      // 2=capital, 3=major, 4=large, 5=medium
        public final String country;

        public City(String name, double lat, double lon, int rank, String country) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
            this.rank = rank;
            this.country = country;
        }
    }

    private final List<City> allCities = new ArrayList<>();
    private boolean loaded = false;

    /**
     * Load cities from bundled asset. Call once during init.
     */
    public void load(Context pluginContext) {
        if (loaded) return;
        try {
            InputStream is = pluginContext.getAssets().open("cities_wind.json");
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();

            JSONArray arr = new JSONArray(new String(buf, "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                allCities.add(new City(
                        obj.getString("n"),
                        obj.getDouble("lat"),
                        obj.getDouble("lon"),
                        obj.optInt("r", 4),
                        obj.optString("cc", "")
                ));
            }
            loaded = true;
            Log.d(TAG, "Loaded " + allCities.size() + " cities");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load cities", e);
        }
    }

    /**
     * Query cities visible in the given bounds at the given map scale.
     *
     * @param north  viewport north bound
     * @param south  viewport south bound
     * @param west   viewport west bound
     * @param east   viewport east bound
     * @param mapResolution  meters per pixel (from mapView.getMapResolution())
     * @return list of visible cities appropriate for current zoom level
     */
    public List<City> queryVisible(double north, double south,
                                    double west, double east,
                                    double mapResolution) {
        // Determine max rank to show based on zoom
        // mapResolution: ~100 m/px = city zoom, ~1000 = country, ~10000 = continental
        int maxRank;
        if (mapResolution > 5000)      maxRank = 2;  // continental: capitals only
        else if (mapResolution > 1500) maxRank = 3;  // country: + major cities
        else if (mapResolution > 500)  maxRank = 4;  // region: + large cities
        else                            maxRank = 5;  // city: all

        List<City> result = new ArrayList<>();
        for (City c : allCities) {
            if (c.rank > maxRank) continue;
            if (c.lat < south || c.lat > north) continue;
            if (c.lon < west || c.lon > east) continue;
            result.add(c);
        }
        return result;
    }

    public boolean isLoaded() { return loaded; }
    public int totalCities() { return allCities.size(); }
}
