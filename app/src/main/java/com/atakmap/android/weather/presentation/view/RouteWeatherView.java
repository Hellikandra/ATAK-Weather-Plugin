package com.atakmap.android.weather.presentation.view;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.service.MultiPointForecastService.PointForecast;
import com.atakmap.android.weather.util.WeatherUnitConverter;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * View helper for multi-point route weather results display.
 *
 * <p>Sprint 12 (S12.1): Renders a sortable table of forecast data
 * for multiple waypoints. Each row shows location, temperature, wind,
 * visibility, precipitation, and tactical condition. Rows are color-coded
 * by tactical condition (GREEN/AMBER/RED).</p>
 */
public class RouteWeatherView {

    private static final String TAG = "RouteWeatherView";

    private final LinearLayout resultsContainer;
    private final TextView summaryText;
    private final Button btnSortTemp;
    private final Button btnSortWind;
    private final Button btnSortTac;
    private final Context context;

    private List<PointForecast> currentForecasts;
    private MapView mapView;

    // Sort modes
    private static final int SORT_NONE = 0;
    private static final int SORT_TEMP = 1;
    private static final int SORT_WIND = 2;
    private static final int SORT_TAC  = 3;
    private int currentSort = SORT_NONE;

    /**
     * Construct from a parent view containing the route weather layout.
     *
     * @param root    the inflated view_route_weather layout
     * @param context plugin context
     */
    public RouteWeatherView(View root, Context context) {
        this.context = context;

        // Look up views by resource name since the IDs are in a plugin package
        int containerId = context.getResources().getIdentifier(
                "route_results_container", "id", context.getPackageName());
        int summaryId = context.getResources().getIdentifier(
                "route_summary", "id", context.getPackageName());
        int btnTempId = context.getResources().getIdentifier(
                "btn_sort_temp", "id", context.getPackageName());
        int btnWindId = context.getResources().getIdentifier(
                "btn_sort_wind", "id", context.getPackageName());
        int btnTacId = context.getResources().getIdentifier(
                "btn_sort_tac", "id", context.getPackageName());

        resultsContainer = containerId != 0 ? root.findViewById(containerId) : null;
        summaryText = summaryId != 0 ? root.findViewById(summaryId) : null;
        btnSortTemp = btnTempId != 0 ? root.findViewById(btnTempId) : null;
        btnSortWind = btnWindId != 0 ? root.findViewById(btnWindId) : null;
        btnSortTac = btnTacId != 0 ? root.findViewById(btnTacId) : null;

        if (btnSortTemp != null) btnSortTemp.setOnClickListener(v -> sortBy(SORT_TEMP));
        if (btnSortWind != null) btnSortWind.setOnClickListener(v -> sortBy(SORT_WIND));
        if (btnSortTac != null) btnSortTac.setOnClickListener(v -> sortBy(SORT_TAC));
    }

    /**
     * Set the MapView for map-interaction features (zoom to point on tap).
     */
    public void setMapView(MapView mapView) {
        this.mapView = mapView;
    }

    /**
     * Bind route forecast results into the results container.
     * Builds a sortable table with columns: Location, Temp, Wind, Vis, Precip, Tactical.
     *
     * @param forecasts list of point forecasts to display
     */
    public void bindResults(List<PointForecast> forecasts) {
        this.currentForecasts = new ArrayList<>(forecasts);
        renderRows(currentForecasts);
        updateSummary(forecasts);
    }

    /**
     * Place weather markers on the map for each forecast point.
     *
     * @param forecasts list of point forecasts
     * @param mapView   the ATAK map view
     */
    public void placeMarkers(List<PointForecast> forecasts, MapView mapView) {
        if (forecasts == null || mapView == null) return;

        for (PointForecast pf : forecasts) {
            if (pf.current == null) continue;

            try {
                GeoPoint gp = new GeoPoint(pf.location.lat, pf.location.lon);
                String uid = "route_wx_" + String.format(Locale.US, "%.4f_%.4f",
                        pf.location.lat, pf.location.lon);

                Marker marker = new Marker(gp, uid);
                marker.setType("a-f-G-U-C");
                marker.setTitle(pf.location.label);
                String summary = buildMarkerSummary(pf);
                marker.setMetaString("remarks", summary);
                marker.setMetaDouble("latitude", pf.location.lat);
                marker.setMetaDouble("longitude", pf.location.lon);

                mapView.getRootGroup().addItem(marker);
            } catch (Exception e) {
                Log.w(TAG, "Failed to place marker for " + pf.location.label, e);
            }
        }
    }

    // ── Sorting ──────────────────────────────────────────────────────────────

    private void sortBy(int sortMode) {
        if (currentForecasts == null || currentForecasts.isEmpty()) return;
        currentSort = sortMode;

        List<PointForecast> sorted = new ArrayList<>(currentForecasts);

        switch (sortMode) {
            case SORT_TEMP:
                Collections.sort(sorted, (a, b) -> {
                    double ta = a.current != null ? a.current.getTemperatureMax() : -999;
                    double tb = b.current != null ? b.current.getTemperatureMax() : -999;
                    return Double.compare(tb, ta); // descending
                });
                break;
            case SORT_WIND:
                Collections.sort(sorted, (a, b) -> {
                    double wa = a.current != null ? a.current.getWindSpeed() : 0;
                    double wb = b.current != null ? b.current.getWindSpeed() : 0;
                    return Double.compare(wb, wa); // descending
                });
                break;
            case SORT_TAC:
                Collections.sort(sorted, (a, b) -> {
                    int pa = tacPriority(a.tacticalCondition);
                    int pb = tacPriority(b.tacticalCondition);
                    return Integer.compare(pb, pa); // RED first
                });
                break;
        }

        renderRows(sorted);
    }

    private int tacPriority(String tac) {
        if ("RED".equals(tac)) return 2;
        if ("AMBER".equals(tac)) return 1;
        return 0;
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    private void renderRows(List<PointForecast> forecasts) {
        if (resultsContainer == null) return;
        resultsContainer.removeAllViews();

        // Header row
        TextView header = new TextView(context);
        header.setText(String.format(Locale.US, "%-16s %8s %10s %8s %8s %5s",
                "Location", "Temp", "Wind", "Vis", "Precip", "TAC"));
        header.setTextSize(9);
        header.setTextColor(0xFFCCCCCC);
        header.setPadding(4, 4, 4, 4);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        resultsContainer.addView(header);

        // Divider
        View divider = new View(context);
        divider.setBackgroundColor(0xFF555555);
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        divParams.setMargins(0, 2, 0, 2);
        resultsContainer.addView(divider, divParams);

        // Data rows
        for (final PointForecast pf : forecasts) {
            TextView row = new TextView(context);
            row.setTextSize(9);
            row.setPadding(4, 3, 4, 3);
            row.setTypeface(android.graphics.Typeface.MONOSPACE);

            if (pf.current != null) {
                WeatherModel w = pf.current;
                String label = pf.location.label;
                if (label.length() > 16) label = label.substring(0, 16);

                String temp = WeatherUnitConverter.fmtTemp(w.getTemperatureMax());
                String wind = WeatherUnitConverter.fmtWind(w.getWindSpeed());
                String vis = WeatherUnitConverter.fmtVisibility(w.getVisibility());
                String precip = WeatherUnitConverter.fmtPrecip(w.getPrecipitationSum());
                String tac = pf.tacticalCondition;

                row.setText(String.format(Locale.US, "%-16s %8s %10s %8s %8s %5s",
                        label, temp, wind, vis, precip, tac));
                row.setTextColor(tacColor(tac));
            } else {
                String label = pf.location.label;
                if (label.length() > 16) label = label.substring(0, 16);
                row.setText(String.format(Locale.US, "%-16s  -- no data --", label));
                row.setTextColor(0xFF888888);
            }

            // Tap to zoom map to this point
            row.setOnClickListener(v -> {
                if (mapView != null) {
                    GeoPoint gp = new GeoPoint(pf.location.lat, pf.location.lon);
                    mapView.getMapController().panTo(gp, true);
                }
            });

            resultsContainer.addView(row);
        }
    }

    private void updateSummary(List<PointForecast> forecasts) {
        if (summaryText == null) return;

        int total = forecasts.size();
        int green = 0, amber = 0, red = 0;
        for (PointForecast pf : forecasts) {
            if ("GREEN".equals(pf.tacticalCondition)) green++;
            else if ("AMBER".equals(pf.tacticalCondition)) amber++;
            else if ("RED".equals(pf.tacticalCondition)) red++;
        }

        summaryText.setText(String.format(Locale.US,
                "%d points  |  %d GREEN  |  %d AMBER  |  %d RED",
                total, green, amber, red));
    }

    private int tacColor(String tac) {
        if ("GREEN".equals(tac)) return 0xFF44CC44;
        if ("AMBER".equals(tac)) return 0xFFCCAA00;
        if ("RED".equals(tac))   return 0xFFCC4444;
        return 0xFFAAAAAA;
    }

    private String buildMarkerSummary(PointForecast pf) {
        if (pf.current == null) return "No data";
        WeatherModel w = pf.current;
        return String.format(Locale.US,
                "Temp: %s | Wind: %s | Vis: %s | %s",
                WeatherUnitConverter.fmtTemp(w.getTemperatureMax()),
                WeatherUnitConverter.fmtWind(w.getWindSpeed()),
                WeatherUnitConverter.fmtVisibility(w.getVisibility()),
                pf.tacticalCondition);
    }
}
