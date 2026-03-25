package com.atakmap.android.weather.overlay.heatmap;

import android.content.Context;

import com.atakmap.android.weather.data.remote.HttpClient;
import com.atakmap.android.weather.data.remote.schema.WeatherSourceDefinitionV2;
import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Fetches hourly weather data for a grid of points using Open-Meteo batch API.
 *
 * <p>Open-Meteo supports batch requests with comma-separated lat/lon values.
 * This fetcher builds a single URL with all grid points and parses the
 * response array into a {@link HeatmapDataSet}.</p>
 *
 * <h3>Batch API format</h3>
 * <pre>
 *   GET https://api.open-meteo.com/v1/forecast
 *       ?latitude=lat1,lat2,...
 *       &amp;longitude=lon1,lon2,...
 *       &amp;hourly=temperature_2m,wind_speed_10m,...
 *       &amp;forecast_hours=48
 * </pre>
 *
 * <p>Response: JSON array of N objects, each containing {@code hourly.time[0..47]}
 * and the requested parameter arrays.</p>
 */
public class HeatmapBatchFetcher {

    private static final String TAG = "HeatmapBatchFetcher";

    /** Default Open-Meteo base URL. */
    private static final String DEFAULT_BASE_URL = "https://api.open-meteo.com/v1/forecast";

    /** Parameters to request for heatmap rendering (includes wind_direction for arrows). */
    private static final String HOURLY_PARAMS =
            "temperature_2m,wind_speed_10m,wind_direction_10m,relative_humidity_2m,"
            + "surface_pressure,visibility,precipitation,weather_code";

    /** Number of forecast hours to request. */
    private static final int FORECAST_HOURS = 48;

    public interface Callback {
        void onResult(HeatmapDataSet dataSet);
        void onError(String error);
    }

    /**
     * Fetch 48h hourly data for all grid points in ONE batch API call.
     *
     * @param grid      the grid specification with lat/lon arrays
     * @param sourceDef the v2 source definition (may be null for default Open-Meteo)
     * @param context   Android context
     * @param callback  result callback
     */
    public void fetchGrid(GridSpec grid, WeatherSourceDefinitionV2 sourceDef,
                          Context context, Callback callback) {
        String baseUrl = DEFAULT_BASE_URL;
        if (sourceDef != null && sourceDef.getEndpoints() != null) {
            // Try to use the hourly endpoint URL from the source definition
            com.atakmap.android.weather.data.remote.schema.EndpointDef hourlyEp =
                    sourceDef.getEndpoints().get("hourly");
            if (hourlyEp != null && hourlyEp.getUrl() != null
                    && !hourlyEp.getUrl().isEmpty()) {
                baseUrl = hourlyEp.getUrl();
            }
        }

        // Build batch URL with comma-separated coordinates
        String latParam = grid.buildLatitudeParam();
        String lonParam = grid.buildLongitudeParam();

        String url = String.format(Locale.US,
                "%s?latitude=%s&longitude=%s&hourly=%s&forecast_hours=%d"
                        + "&wind_speed_unit=ms&temperature_unit=celsius",
                baseUrl, latParam, lonParam, HOURLY_PARAMS, FORECAST_HOURS);

        Log.d(TAG, "Fetching heatmap grid: " + grid.getTotalPoints()
                + " points (" + grid.getRows() + "x" + grid.getCols() + ")");

        HttpClient.get(url, new HttpClient.Callback() {
            @Override
            public void onSuccess(String body) {
                try {
                    HeatmapDataSet dataSet = parseResponse(body, grid);
                    callback.onResult(dataSet);
                } catch (Exception e) {
                    Log.e(TAG, "Parse error", e);
                    callback.onError("Parse error: " + e.getMessage());
                }
            }

            @Override
            public void onFailure(String error) {
                Log.e(TAG, "Fetch failed: " + error);
                callback.onError(error);
            }
        });
    }

    /**
     * Parse the Open-Meteo batch response into a HeatmapDataSet.
     *
     * <p>For a single point, Open-Meteo returns a single JSON object.
     * For multiple points, it returns a JSON array of objects.
     * Each object has {@code hourly.time[]} and parameter arrays.</p>
     */
    private HeatmapDataSet parseResponse(String body, GridSpec grid) throws Exception {
        int rows = grid.getRows();
        int cols = grid.getCols();
        int totalPoints = grid.getTotalPoints();

        // Parse as array or wrap single object
        JSONArray pointsArray;
        body = body.trim();
        if (body.startsWith("[")) {
            pointsArray = new JSONArray(body);
        } else {
            // Single point: wrap in array
            pointsArray = new JSONArray();
            pointsArray.put(new JSONObject(body));
        }

        if (pointsArray.length() < totalPoints) {
            Log.w(TAG, "Response has " + pointsArray.length()
                    + " points, expected " + totalPoints);
        }

        // Extract time labels from the first point
        JSONObject first = pointsArray.getJSONObject(0);
        JSONObject hourlyFirst = first.getJSONObject("hourly");
        JSONArray timeArray = hourlyFirst.getJSONArray("time");
        int hoursCount = Math.min(timeArray.length(), FORECAST_HOURS);

        String[] timeLabels = new String[hoursCount];
        for (int h = 0; h < hoursCount; h++) {
            timeLabels[h] = timeArray.getString(h);
        }

        // Initialize data arrays: paramKey -> double[hour][row][col]
        String[] paramKeys = HOURLY_PARAMS.split(",");
        Map<String, double[][][]> data = new LinkedHashMap<>();
        for (String key : paramKeys) {
            data.put(key, new double[hoursCount][rows][cols]);
        }

        // Fill data from response
        int pointIdx = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (pointIdx >= pointsArray.length()) break;

                JSONObject point = pointsArray.getJSONObject(pointIdx);
                JSONObject hourly = point.getJSONObject("hourly");

                for (String key : paramKeys) {
                    double[][][] paramData = data.get(key);
                    if (paramData == null) continue;

                    JSONArray values = hourly.optJSONArray(key);
                    if (values == null) continue;

                    for (int h = 0; h < hoursCount && h < values.length(); h++) {
                        if (values.isNull(h)) {
                            paramData[h][r][c] = Double.NaN;
                        } else {
                            paramData[h][r][c] = values.getDouble(h);
                        }
                    }
                }
                pointIdx++;
            }
        }

        return new HeatmapDataSet(grid, data, timeLabels, hoursCount,
                System.currentTimeMillis());
    }
}
