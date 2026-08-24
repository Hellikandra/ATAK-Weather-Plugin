package com.atakmap.android.weather.overlay.marine;

import android.content.Context;

import com.atakmap.android.weather.data.remote.HttpClient;
import com.atakmap.android.weather.overlay.heatmap.GridSpec;
import com.atakmap.android.weather.overlay.heatmap.HeatmapDataSet;
import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Fetches marine weather data (waves, currents, tides, SST) from
 * the Open-Meteo Marine API for a grid of points.
 *
 * <p>Uses the same batch lat/lon pattern as {@code HeatmapBatchFetcher}
 * but targets the marine-specific endpoint with marine parameters.</p>
 *
 * <h3>API endpoint</h3>
 * <pre>
 *   GET https://marine-api.open-meteo.com/v1/marine
 *       ?latitude=lat1,lat2,...
 *       &amp;longitude=lon1,lon2,...
 *       &amp;hourly=wave_height,ocean_current_velocity,...
 *       &amp;forecast_hours=48
 * </pre>
 *
 * <p>Returns a {@link HeatmapDataSet} — same data structure used by the
 * weather heatmap. The overlay manager renders it identically.</p>
 */
public class MarineBatchFetcher {

    private static final String TAG = "MarineBatchFetcher";

    /** Open-Meteo Marine API endpoint. */
    private static final String BASE_URL =
            "https://marine-api.open-meteo.com/v1/marine";

    /**
     * Marine parameters to fetch.
     * Includes wave, swell, current, tide, and SST data.
     * The wind_wave_* params are separate from swell — both matter for sea state.
     */
    private static final String HOURLY_PARAMS =
            "wave_height,wave_direction,wave_period,"
            + "wind_wave_height,wind_wave_direction,wind_wave_period,"
            + "swell_wave_height,swell_wave_direction,swell_wave_period,"
            + "ocean_current_velocity,ocean_current_direction,"
            + "sea_surface_temperature,sea_level_height_msl";

    /** Number of forecast hours. */
    private static final int FORECAST_HOURS = 48;

    public interface Callback {
        void onResult(HeatmapDataSet dataSet);
        void onError(String error);
    }

    /**
     * Fetch marine data for all grid points in one batch API call.
     */
    public void fetchGrid(GridSpec grid, Context context, Callback callback) {
        String latParam = grid.buildLatitudeParam();
        String lonParam = grid.buildLongitudeParam();

        String url = String.format(Locale.US,
                "%s?latitude=%s&longitude=%s&hourly=%s&forecast_hours=%d"
                        + "&length_unit=metric",
                BASE_URL, latParam, lonParam, HOURLY_PARAMS, FORECAST_HOURS);

        Log.d(TAG, "Fetching marine grid: " + grid.getTotalPoints()
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
     * Parse the Open-Meteo Marine batch response into a HeatmapDataSet.
     * Marine API returns NaN/null for land points — stored as Double.NaN.
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
            pointsArray = new JSONArray();
            pointsArray.put(new JSONObject(body));
        }

        if (pointsArray.length() < totalPoints) {
            Log.w(TAG, "Response has " + pointsArray.length()
                    + " points, expected " + totalPoints);
        }

        // Find first point with hourly data (some may be land-only with no data)
        JSONObject hourlyFirst = null;
        int hoursCount = FORECAST_HOURS;
        for (int i = 0; i < pointsArray.length(); i++) {
            JSONObject pt = pointsArray.getJSONObject(i);
            if (pt.has("hourly")) {
                hourlyFirst = pt.getJSONObject("hourly");
                if (hourlyFirst.has("time")) {
                    hoursCount = Math.min(
                            hourlyFirst.getJSONArray("time").length(),
                            FORECAST_HOURS);
                }
                break;
            }
        }

        // If no point had hourly data, this area is all land
        if (hourlyFirst == null) {
            Log.w(TAG, "No marine data in response — area may be all land");
            // Return dataset with all NaN
            String[] emptyLabels = new String[hoursCount];
            for (int h = 0; h < hoursCount; h++) emptyLabels[h] = "+"+h+"h";
            Map<String, double[][][]> emptyData = new LinkedHashMap<>();
            String[] paramKeys = HOURLY_PARAMS.split(",");
            for (String key : paramKeys) {
                double[][][] arr = new double[hoursCount][rows][cols];
                for (int h = 0; h < hoursCount; h++)
                    for (int r = 0; r < rows; r++)
                        for (int c = 0; c < cols; c++)
                            arr[h][r][c] = Double.NaN;
                emptyData.put(key, arr);
            }
            return new HeatmapDataSet(grid, emptyData, emptyLabels, hoursCount,
                    System.currentTimeMillis());
        }

        // Extract time labels
        JSONArray timeArray = hourlyFirst.getJSONArray("time");
        String[] timeLabels = new String[hoursCount];
        for (int h = 0; h < hoursCount; h++) {
            timeLabels[h] = timeArray.getString(h);
        }

        // Initialize data arrays: paramKey -> double[hour][row][col]
        String[] paramKeys = HOURLY_PARAMS.split(",");
        Map<String, double[][][]> data = new LinkedHashMap<>();
        for (String key : paramKeys) {
            double[][][] arr = new double[hoursCount][rows][cols];
            // Initialize all to NaN (land points will stay NaN)
            for (int h = 0; h < hoursCount; h++)
                for (int r = 0; r < rows; r++)
                    for (int c = 0; c < cols; c++)
                        arr[h][r][c] = Double.NaN;
            data.put(key, arr);
        }

        // Fill data from response
        int pointIdx = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (pointIdx >= pointsArray.length()) break;

                JSONObject point = pointsArray.getJSONObject(pointIdx);
                JSONObject hourly = point.optJSONObject("hourly");

                if (hourly != null) {
                    for (String key : paramKeys) {
                        double[][][] paramData = data.get(key);
                        if (paramData == null) continue;

                        JSONArray values = hourly.optJSONArray(key);
                        if (values == null) continue;

                        for (int h = 0; h < hoursCount && h < values.length(); h++) {
                            if (!values.isNull(h)) {
                                paramData[h][r][c] = values.getDouble(h);
                            }
                            // else stays NaN (land or missing data)
                        }
                    }
                }
                pointIdx++;
            }
        }

        Log.d(TAG, "Parsed marine dataset: " + rows + "x" + cols
                + ", " + hoursCount + " hours, " + paramKeys.length + " params");

        return new HeatmapDataSet(grid, data, timeLabels, hoursCount,
                System.currentTimeMillis());
    }

    /** Get the list of marine parameter keys (for UI spinner population). */
    public static String[] getParameterKeys() {
        return HOURLY_PARAMS.split(",");
    }

    /** User-friendly display names for marine parameters. */
    public static String getDisplayName(String paramKey) {
        switch (paramKey) {
            case "wave_height":               return "Wave Height";
            case "wave_direction":            return "Wave Direction";
            case "wave_period":               return "Wave Period";
            case "wind_wave_height":          return "Wind Wave Height";
            case "wind_wave_direction":       return "Wind Wave Dir";
            case "wind_wave_period":          return "Wind Wave Period";
            case "swell_wave_height":         return "Swell Height";
            case "swell_wave_direction":      return "Swell Direction";
            case "swell_wave_period":         return "Swell Period";
            case "ocean_current_velocity":    return "Ocean Current";
            case "ocean_current_direction":   return "Current Direction";
            case "sea_surface_temperature":   return "Sea Surface Temp";
            case "sea_level_height_msl":      return "Tide Level";
            default:                          return paramKey;
        }
    }
}
