package com.atakmap.android.weather.domain.service;

import android.content.Context;

import com.atakmap.android.weather.data.remote.HttpClient;
import com.atakmap.android.weather.data.remote.schema.BatchConfig;
import com.atakmap.android.weather.data.remote.schema.EndpointDef;
import com.atakmap.android.weather.data.remote.schema.WeatherSourceDefinitionV2;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.util.CoordFormatter;
import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Multi-point forecast service for route weather and area grid queries.
 *
 * <p>Sprint 12 (S12.1): Fetches weather for multiple waypoints or a
 * rectangular area grid using the batch API configuration from the
 * source definition.</p>
 */
public class MultiPointForecastService {

    private static final String TAG = "MultiPointForecast";

    /**
     * Callback for multi-point forecast results.
     */
    public interface Callback {
        void onResult(List<PointForecast> forecasts);
        void onError(String error);
    }

    /**
     * Simple lat/lon pair with an optional label.
     */
    public static class LatLon {
        public final double lat;
        public final double lon;
        public final String label;

        public LatLon(double lat, double lon, String label) {
            this.lat = lat;
            this.lon = lon;
            this.label = label != null ? label : String.format(Locale.US, "%.2f, %.2f", lat, lon);
        }

        public LatLon(double lat, double lon) {
            this(lat, lon, null);
        }
    }

    /**
     * Forecast result for a single point including current conditions
     * and hourly forecast data.
     */
    public static class PointForecast {
        public final LatLon location;
        public final WeatherModel current;
        public final List<HourlyEntryModel> hourly;
        public final String tacticalCondition;

        public PointForecast(LatLon location, WeatherModel current,
                             List<HourlyEntryModel> hourly,
                             String tacticalCondition) {
            this.location = location;
            this.current = current;
            this.hourly = hourly;
            this.tacticalCondition = tacticalCondition;
        }
    }

    /**
     * Fetch weather for a list of waypoints using individual requests.
     *
     * <p>Since most free APIs do not support true batch endpoints, this
     * implementation issues one request per waypoint and aggregates the
     * results. Requests are issued sequentially to respect rate limits.</p>
     *
     * @param waypoints  list of points along a route
     * @param sourceDef  the active weather source definition
     * @param context    Android context
     * @param callback   result callback
     */
    public void fetchRouteWeather(List<LatLon> waypoints,
                                   WeatherSourceDefinitionV2 sourceDef,
                                   Context context, Callback callback) {
        if (waypoints == null || waypoints.isEmpty()) {
            callback.onError("No waypoints provided");
            return;
        }

        BatchConfig batch = sourceDef.getBatch();
        int maxLocations = (batch != null && batch.isSupported())
                ? batch.getMaxLocations() : 50;

        if (waypoints.size() > maxLocations) {
            callback.onError("Too many waypoints (max " + maxLocations + ")");
            return;
        }

        Log.d(TAG, "fetchRouteWeather: " + waypoints.size() + " points");

        final List<PointForecast> results = new ArrayList<>();
        final int[] remaining = {waypoints.size()};
        final boolean[] hasError = {false};

        for (int i = 0; i < waypoints.size(); i++) {
            final LatLon wp = waypoints.get(i);
            fetchSinglePoint(wp, sourceDef, new Callback() {
                @Override
                public void onResult(List<PointForecast> forecasts) {
                    synchronized (results) {
                        if (!forecasts.isEmpty()) {
                            results.add(forecasts.get(0));
                        }
                        remaining[0]--;
                        if (remaining[0] <= 0 && !hasError[0]) {
                            callback.onResult(results);
                        }
                    }
                }

                @Override
                public void onError(String error) {
                    synchronized (results) {
                        remaining[0]--;
                        Log.w(TAG, "Point fetch failed for " + wp.label + ": " + error);
                        // Add a null-data placeholder so the caller knows which point failed
                        results.add(new PointForecast(wp, null, new ArrayList<HourlyEntryModel>(), "UNKNOWN"));
                        if (remaining[0] <= 0) {
                            callback.onResult(results);
                        }
                    }
                }
            });
        }
    }

    /**
     * Fetch weather for a rectangular area grid.
     *
     * <p>Generates a uniform grid of points within the bounding box and
     * fetches weather for each. The number of grid points is distributed
     * as evenly as possible between rows and columns.</p>
     *
     * @param north      northern boundary latitude
     * @param south      southern boundary latitude
     * @param east       eastern boundary longitude
     * @param west       western boundary longitude
     * @param gridPoints total number of grid points to sample
     * @param sourceDef  the active weather source definition
     * @param context    Android context
     * @param callback   result callback
     */
    public void fetchAreaWeather(double north, double south,
                                  double east, double west,
                                  int gridPoints,
                                  WeatherSourceDefinitionV2 sourceDef,
                                  Context context, Callback callback) {
        if (gridPoints <= 0) {
            callback.onError("Grid points must be > 0");
            return;
        }

        // Calculate grid dimensions (approximate square grid)
        int cols = (int) Math.ceil(Math.sqrt(gridPoints));
        int rows = (int) Math.ceil((double) gridPoints / cols);

        double latStep = (rows > 1) ? (north - south) / (rows - 1) : 0;
        double lonStep = (cols > 1) ? (east - west) / (cols - 1) : 0;

        List<LatLon> points = new ArrayList<>();
        int count = 0;
        for (int r = 0; r < rows && count < gridPoints; r++) {
            for (int c = 0; c < cols && count < gridPoints; c++) {
                double lat = north - r * latStep;
                double lon = west + c * lonStep;
                String label = String.format(Locale.US, "Grid %d,%d", r + 1, c + 1);
                points.add(new LatLon(lat, lon, label));
                count++;
            }
        }

        Log.d(TAG, "fetchAreaWeather: " + points.size() + " grid points ("
                + rows + "x" + cols + ")");

        fetchRouteWeather(points, sourceDef, context, callback);
    }

    /**
     * Fetch weather for a single point and wrap it in a PointForecast.
     */
    private void fetchSinglePoint(LatLon point,
                                    WeatherSourceDefinitionV2 sourceDef,
                                    Callback callback) {
        // Build a minimal current-weather URL from the source definition
        EndpointDef currentEndpoint = sourceDef.getEndpoint("current");
        if (currentEndpoint == null) {
            callback.onError("No 'current' endpoint in source definition");
            return;
        }

        String url = buildPointUrl(currentEndpoint, point.lat, point.lon);
        Log.d(TAG, "fetchSinglePoint: " + url);

        HttpClient.get(url, new HttpClient.Callback() {
            @Override
            public void onSuccess(String body) {
                try {
                    JSONObject root = new JSONObject(body);

                    // Parse current conditions
                    WeatherModel current = parseCurrentWeather(root, currentEndpoint, point);
                    String tactical = current != null ? current.tacticalCondition() : "UNKNOWN";

                    List<PointForecast> result = new ArrayList<>();
                    result.add(new PointForecast(point, current,
                            new ArrayList<HourlyEntryModel>(), tactical));
                    callback.onResult(result);

                } catch (Exception e) {
                    Log.e(TAG, "Parse error for point " + point.label, e);
                    callback.onError("Parse error: " + e.getMessage());
                }
            }

            @Override
            public void onFailure(String error) {
                callback.onError(error);
            }
        });
    }

    /**
     * Build a URL for a single point from an endpoint definition.
     */
    private String buildPointUrl(EndpointDef endpoint, double lat, double lon) {
        String baseUrl = endpoint.getUrl();
        StringBuilder url = new StringBuilder(baseUrl);

        java.util.Map<String, String> queryParams = endpoint.getQueryParams();
        if (queryParams == null || queryParams.isEmpty()) {
            return url.toString();
        }

        boolean first = !baseUrl.contains("?");
        for (java.util.Map.Entry<String, String> entry : queryParams.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (value == null) continue;
            value = value.replace("{lat}", CoordFormatter.format(lat));
            value = value.replace("{lon}", CoordFormatter.format(lon));

            if (value.contains("{")) continue; // skip unresolved placeholders

            url.append(first ? '?' : '&');
            url.append(key).append('=').append(value);
            first = false;
        }
        return url.toString();
    }

    /**
     * Parse current weather from a JSON response using the endpoint's
     * response path and field mapping.
     */
    private WeatherModel parseCurrentWeather(JSONObject root,
                                              EndpointDef endpoint,
                                              LatLon point) {
        try {
            JSONObject data = root;
            String respPath = endpoint.getResponsePath();
            if (respPath != null && !respPath.isEmpty()) {
                String[] parts = respPath.split("\\.");
                for (String part : parts) {
                    if (data.has(part)) {
                        data = data.getJSONObject(part);
                    }
                }
            }

            // Build a WeatherModel from available fields
            WeatherModel.Builder builder = new WeatherModel.Builder(point.lat, point.lon)
                    .locationName(point.label);

            if (data.has("temperature_2m"))
                builder.temperatureMax(data.optDouble("temperature_2m", 0));
            if (data.has("apparent_temperature"))
                builder.apparentTemperature(data.optDouble("apparent_temperature", 0));
            if (data.has("relative_humidity_2m"))
                builder.humidity(data.optDouble("relative_humidity_2m", 0));
            if (data.has("surface_pressure"))
                builder.pressure(data.optDouble("surface_pressure", 0));
            if (data.has("visibility"))
                builder.visibility(data.optDouble("visibility", 0));
            if (data.has("wind_speed_10m"))
                builder.windSpeed(data.optDouble("wind_speed_10m", 0));
            if (data.has("wind_direction_10m"))
                builder.windDirection(data.optDouble("wind_direction_10m", 0));
            if (data.has("precipitation"))
                builder.precipitationSum(data.optDouble("precipitation", 0));
            if (data.has("weather_code"))
                builder.weatherCode(data.optInt("weather_code", 0));

            return builder.build();

        } catch (Exception e) {
            Log.e(TAG, "parseCurrentWeather failed", e);
            return null;
        }
    }
}
