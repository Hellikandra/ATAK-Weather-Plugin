package com.atakmap.android.weather.presentation.view;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.routes.Route;
import com.atakmap.android.weather.overlay.marker.WeatherMarkerManager;
import com.atakmap.android.weather.overlay.wind.WindMarkerManager;
import com.atakmap.android.weather.plugin.R;
import com.atakmap.android.weather.domain.model.LocationSnapshot;
import com.atakmap.android.weather.domain.model.LocationSource;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.util.WeatherConstants;
import com.atakmap.android.weather.util.WeatherPlaceTool;
import com.atakmap.android.weather.util.WeatherUiUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * MarkerTabCoordinator — Sprint 17+
 *
 * Manages the Markers view (tab_markers.xml):
 * <ul>
 *   <li>Marker type spinner (dark styled)</li>
 *   <li>Drop / Share / Remove All buttons</li>
 *   <li>Active markers list populated from MapGroups</li>
 *   <li>Route Weather section with ATAK route picker dialog</li>
 * </ul>
 */
public class MarkerTabCoordinator {

    private static final String TAG = "MarkerTabCoordinator";

    private final View rootView;
    private final Context pluginContext;
    private final MapView mapView;

    private WeatherMarkerManager weatherMarkerManager;
    private WindMarkerManager windMarkerManager;

    private Spinner markerTypeSpinner;
    private Button btnDrop, btnShare, btnRemoveAll;
    private Button btnSelectRoute, btnFetchRouteWeather;
    private ListView markerList;
    private TextView markerCountLabel;
    private View routeWeatherContent;
    private ListView routeWeatherResults;

    private String lastPlacedMarkerUid;
    private WeatherModel lastWeather;

    /** Route weather chart (wind + humidity along route). */
    private RouteWeatherChartView routeChart;

    /** Selected ATAK route for weather-along-route. */
    private Route selectedRoute;
    private String selectedRouteName;

    /** Callback for route weather fetch. */
    private RouteWeatherCallback routeWeatherCallback;

    public interface RouteWeatherCallback {
        void onSelectRoute();
        void onFetchRouteWeather();
        /** Fetch weather at a list of waypoints along the route. */
        void onFetchWeatherAtPoints(List<GeoPoint> waypoints, String routeName);
    }

    /** MapGroup listener for auto-refreshing marker list on any add/remove. */
    private MapGroup.OnItemListChangedListener groupListener;

    public MarkerTabCoordinator(View rootView, Context pluginContext, MapView mapView) {
        this.rootView = rootView;
        this.pluginContext = pluginContext;
        this.mapView = mapView;
        initViews();
    }

    public void setWeatherMarkerManager(WeatherMarkerManager mgr) {
        this.weatherMarkerManager = mgr;
        registerGroupListener(mgr != null ? mgr.getMapGroup() : null);
    }

    public void setWindMarkerManager(WindMarkerManager mgr) {
        this.windMarkerManager = mgr;
        registerGroupListener(mgr != null ? mgr.getMapGroup() : null);
    }

    /**
     * Register a MapGroup listener to auto-refresh the marker list whenever
     * markers are added or removed from ANY source (radial menu, CoT, DDR, etc.).
     */
    private void registerGroupListener(MapGroup group) {
        if (group == null) return;
        if (groupListener == null) {
            groupListener = new MapGroup.OnItemListChangedListener() {
                @Override public void onItemAdded(MapItem item, MapGroup grp) {
                    mapView.postDelayed(() -> refreshMarkerList(), 200);
                }
                @Override public void onItemRemoved(MapItem item, MapGroup grp) {
                    mapView.postDelayed(() -> refreshMarkerList(), 200);
                }
            };
        }
        group.addOnItemListChangedListener(groupListener);
    }

    public void setLastWeather(WeatherModel weather) {
        this.lastWeather = weather;
    }

    public void setRouteWeatherCallback(RouteWeatherCallback callback) {
        this.routeWeatherCallback = callback;
    }

    private void initViews() {
        markerTypeSpinner = rootView.findViewById(R.id.marker_type_spinner);
        btnDrop = rootView.findViewById(R.id.btn_drop_marker);
        btnShare = rootView.findViewById(R.id.btn_share_marker);
        btnRemoveAll = rootView.findViewById(R.id.btn_remove_all_markers);
        markerList = rootView.findViewById(R.id.marker_list);
        markerCountLabel = rootView.findViewById(R.id.marker_count_label);
        btnSelectRoute = rootView.findViewById(R.id.btn_select_route);
        btnFetchRouteWeather = rootView.findViewById(R.id.btn_fetch_route_weather);
        routeWeatherContent = rootView.findViewById(R.id.route_weather_content);
        routeWeatherResults = rootView.findViewById(R.id.route_weather_results);

        // ── Marker type spinner (dark styled) ─────────────────────────────
        if (markerTypeSpinner != null) {
            List<String> types = Arrays.asList("Weather", "Wind", "Route Weather", "Custom");
            markerTypeSpinner.setAdapter(
                    WeatherUiUtils.makeDarkSpinnerAdapter(pluginContext, types));
            WeatherUiUtils.styleSpinnerDark(markerTypeSpinner);
        }

        // Wire buttons
        if (btnDrop != null) btnDrop.setOnClickListener(v -> dropMarker());
        if (btnShare != null) btnShare.setOnClickListener(v -> shareMarker());
        if (btnRemoveAll != null) btnRemoveAll.setOnClickListener(v -> removeAllMarkers());

        // ── Route weather collapsible ─────────────────────────────────────
        View routeHeader = rootView.findViewById(R.id.section_route_weather_header);
        if (routeHeader != null && routeWeatherContent != null) {
            routeHeader.setOnClickListener(v -> {
                boolean showing = routeWeatherContent.getVisibility() == View.VISIBLE;
                routeWeatherContent.setVisibility(showing ? View.GONE : View.VISIBLE);
                if (routeHeader instanceof TextView) {
                    ((TextView) routeHeader).setText(showing
                            ? "\u25B8 Route Weather"
                            : "\u25BE Route Weather");
                }
            });
        }

        if (btnSelectRoute != null) {
            btnSelectRoute.setOnClickListener(v -> showRouteSelectionDialog());
        }
        if (btnFetchRouteWeather != null) {
            btnFetchRouteWeather.setOnClickListener(v -> fetchWeatherAlongRoute());
        }

        // Route weather chart
        android.widget.FrameLayout chartFrame = rootView.findViewById(R.id.route_weather_chart_frame);
        if (chartFrame != null) {
            routeChart = new RouteWeatherChartView(pluginContext);
            chartFrame.addView(routeChart, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        }

        // Initial marker list population
        mapView.postDelayed(this::refreshMarkerList, 500);

        // Allow ListView to scroll inside parent ScrollView
        if (markerList != null) {
            markerList.setOnTouchListener((v, event) -> {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                return false;
            });
        }

        // Marker list item click → pan to marker on map
        if (markerList != null) {
            markerList.setOnItemClickListener((parent, view, position, id) -> {
                Object item = parent.getItemAtPosition(position);
                if (item instanceof MarkerInfo) {
                    MarkerInfo mi = (MarkerInfo) item;
                    // Find the actual MapItem and pan to it
                    MapItem mapItem = findMapItemByUid(mi.uid);
                    if (mapItem instanceof Marker) {
                        GeoPoint pt = ((Marker) mapItem).getPoint();
                        mapView.getMapController().panTo(pt, true);
                        Toast.makeText(pluginContext,
                                "Navigating to " + mi.callsign, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
    }

    private MapItem findMapItemByUid(String uid) {
        if (uid == null) return null;
        MapGroup wg = weatherMarkerManager != null ? weatherMarkerManager.getMapGroup() : null;
        if (wg != null) {
            MapItem found = wg.deepFindUID(uid);
            if (found != null) return found;
        }
        MapGroup windG = windMarkerManager != null ? windMarkerManager.getMapGroup() : null;
        if (windG != null) {
            MapItem found = windG.deepFindUID(uid);
            if (found != null) return found;
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MARKER OPERATIONS
    // ══════════════════════════════════════════════════════════════════════════

    private void dropMarker() {
        int selected = markerTypeSpinner != null ? markerTypeSpinner.getSelectedItemPosition() : 0;
        WeatherPlaceTool.Mode mode = (selected == 1)
                ? WeatherPlaceTool.Mode.WIND
                : WeatherPlaceTool.Mode.WEATHER;

        WeatherPlaceTool.start(mapView, mode, (pickedPoint, pickedMode) -> {
            LocationSnapshot snapshot = new LocationSnapshot(
                    pickedPoint.getLatitude(), pickedPoint.getLongitude(),
                    null, LocationSource.MAP_CENTRE);

            WeatherModel wx = lastWeather;
            if (wx == null) {
                wx = new WeatherModel.Builder(
                        pickedPoint.getLatitude(), pickedPoint.getLongitude())
                        .requestTimestamp(new java.text.SimpleDateFormat(
                                "yyyy-MM-dd'T'HH:mm", Locale.US)
                                .format(new java.util.Date()))
                        .build();
            }

            if (pickedMode == WeatherPlaceTool.Mode.WIND && windMarkerManager != null) {
                windMarkerManager.placeMarker(snapshot, wx);
                lastPlacedMarkerUid = String.format(Locale.US,
                        WindMarkerManager.UID_PREFIX + "_%.4f_%.4f",
                        pickedPoint.getLatitude(), pickedPoint.getLongitude());
            } else if (weatherMarkerManager != null) {
                weatherMarkerManager.placeMarker(snapshot, wx);
                lastPlacedMarkerUid = String.format(Locale.US,
                        "wx_centre_%.4f_%.4f",
                        pickedPoint.getLatitude(), pickedPoint.getLongitude());
            }

            Intent reopen = new Intent(WeatherConstants.ACTION_SHOW_PLUGIN);
            reopen.putExtra(WeatherConstants.EXTRA_REQUESTED_TAB, "markers");
            AtakBroadcast.getInstance().sendBroadcast(reopen);
            mapView.postDelayed(this::refreshMarkerList, 500);
        });
    }

    private void shareMarker() {
        if (lastPlacedMarkerUid == null) {
            Toast.makeText(pluginContext, "No marker to share — drop a marker first",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Intent shareIntent = new Intent(WeatherConstants.ACTION_SHARE_MARKER);
        shareIntent.putExtra(WeatherConstants.EXTRA_TARGET_UID, lastPlacedMarkerUid);
        AtakBroadcast.getInstance().sendBroadcast(shareIntent);
    }

    private void removeAllMarkers() {
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Remove All Markers")
                .setMessage("Remove all weather and wind markers?")
                .setPositiveButton("Remove", (d, w) -> {
                    if (weatherMarkerManager != null) weatherMarkerManager.removeAllMarkers();
                    if (windMarkerManager != null) windMarkerManager.removeAllMarkers();
                    lastPlacedMarkerUid = null;
                    // Immediately clear the list UI
                    if (markerList != null) {
                        markerList.setAdapter(new MarkerListAdapter(pluginContext, new ArrayList<>()));
                    }
                    if (markerCountLabel != null) markerCountLabel.setText("0 markers");
                    // Also refresh after delay to catch any async removals
                    mapView.postDelayed(this::refreshMarkerList, 500);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ACTIVE MARKERS LIST
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Refresh the marker list and count from actual MapGroup items.
     */
    public void refreshMarkerList() {
        List<MarkerInfo> markers = new ArrayList<>();

        // Collect weather markers
        if (weatherMarkerManager != null) {
            collectMarkersFromGroup(weatherMarkerManager.getMapGroup(), "Weather", markers);
        }
        // Collect wind markers
        if (windMarkerManager != null) {
            collectMarkersFromGroup(windMarkerManager.getMapGroup(), "Wind", markers);
        }

        int count = markers.size();
        if (markerCountLabel != null) {
            markerCountLabel.setText(count + (count == 1 ? " marker" : " markers"));
        }

        if (markerList != null) {
            markerList.setAdapter(new MarkerListAdapter(pluginContext, markers));
        }
    }

    /** Also exposed as updateMarkerCount for backward compat. */
    public void updateMarkerCount() { refreshMarkerList(); }

    private void collectMarkersFromGroup(MapGroup group, String type, List<MarkerInfo> out) {
        if (group == null) return;
        for (MapItem item : group.getItems()) {
            MarkerInfo info = new MarkerInfo();
            info.uid = item.getUID();
            info.type = type;
            info.callsign = item.getMetaString("callsign", "—");
            if (item instanceof Marker) {
                GeoPoint pt = ((Marker) item).getPoint();
                info.coords = String.format(Locale.US, "%.4f\u00b0N  %.4f\u00b0E",
                        pt.getLatitude(), pt.getLongitude());
            } else {
                info.coords = "";
            }
            info.title = item.getMetaString("title", "");
            out.add(info);
        }
    }

    /** Simple data holder for a marker in the list. */
    private static class MarkerInfo {
        String uid, type, callsign, coords, title;
    }

    /** Simple list adapter for active markers. */
    private static class MarkerListAdapter extends BaseAdapter {
        private final List<MarkerInfo> items;
        private final Context ctx;

        MarkerListAdapter(Context ctx, List<MarkerInfo> items) {
            this.ctx = ctx;
            this.items = items;
        }

        @Override public int getCount() { return items.size(); }
        @Override public MarkerInfo getItem(int pos) { return items.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv;
            if (convertView instanceof TextView) {
                tv = (TextView) convertView;
            } else {
                tv = new TextView(ctx);
                float dp = ctx.getResources().getDisplayMetrics().density;
                tv.setPadding((int)(8*dp), (int)(6*dp), (int)(8*dp), (int)(6*dp));
                tv.setTextSize(11);
                tv.setTextColor(0xFFc9d1d9);
            }
            MarkerInfo mi = items.get(position);
            tv.setText(String.format("[%s] %s\n%s", mi.type, mi.callsign, mi.coords));
            return tv;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ROUTE WEATHER
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Open an AlertDialog listing all ATAK routes from the "Route" MapGroup.
     * User selects one, and we store it for subsequent weather fetch.
     */
    private void showRouteSelectionDialog() {
        // Find routes in ATAK's route MapGroup
        MapGroup rootGroup = mapView.getRootGroup();
        List<Route> routes = new ArrayList<>();
        List<String> routeNames = new ArrayList<>();

        findRoutes(rootGroup, routes, routeNames);

        if (routes.isEmpty()) {
            Toast.makeText(pluginContext, "No routes found. Create a route in ATAK first.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        String[] namesArray = routeNames.toArray(new String[0]);
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Select Route")
                .setItems(namesArray, (dialog, which) -> {
                    selectedRoute = routes.get(which);
                    selectedRouteName = namesArray[which];
                    if (btnSelectRoute != null) {
                        btnSelectRoute.setText("Route: " + selectedRouteName);
                    }
                    Toast.makeText(pluginContext,
                            "Selected: " + selectedRouteName
                                    + " (" + selectedRoute.getNumPoints() + " waypoints)",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Recursively find Route items in the MapGroup tree. */
    private void findRoutes(MapGroup group, List<Route> routes, List<String> names) {
        if (group == null) return;
        for (MapItem item : group.getItems()) {
            if (item instanceof Route) {
                Route route = (Route) item;
                String name = route.getMetaString("title", "");
                if (name == null || name.isEmpty()) {
                    name = route.getMetaString("callsign",
                            "Route " + (routes.size() + 1));
                }
                routes.add(route);
                names.add(name + " (" + route.getNumPoints() + " pts)");
            }
        }
        // Search child groups
        Collection<MapGroup> children = group.getChildGroups();
        if (children != null) {
            for (MapGroup child : children) {
                findRoutes(child, routes, names);
            }
        }
    }

    /**
     * Fetch weather at waypoints along the selected route.
     * Extracts GeoPoints from the route and delegates to the callback or
     * directly fetches weather at each waypoint.
     */
    private void fetchWeatherAlongRoute() {
        if (selectedRoute == null) {
            Toast.makeText(pluginContext, "Select a route first", Toast.LENGTH_SHORT).show();
            return;
        }

        int numPoints = selectedRoute.getNumPoints();
        if (numPoints == 0) {
            Toast.makeText(pluginContext, "Route has no waypoints", Toast.LENGTH_SHORT).show();
            return;
        }

        // Extract waypoints at regular intervals (max ~20 points to avoid API overload)
        List<GeoPoint> waypoints = new ArrayList<>();
        int step = Math.max(1, numPoints / 20);
        for (int i = 0; i < numPoints; i += step) {
            GeoPoint pt = selectedRoute.getPoint(i).get();
            if (pt != null) waypoints.add(pt);
        }
        // Always include the last point
        GeoPoint lastPt = selectedRoute.getPoint(numPoints - 1).get();
        if (lastPt != null && (waypoints.isEmpty()
                || !waypoints.get(waypoints.size() - 1).equals(lastPt))) {
            waypoints.add(lastPt);
        }

        Toast.makeText(pluginContext,
                "Fetching weather at " + waypoints.size() + " points along "
                        + selectedRouteName + "...",
                Toast.LENGTH_SHORT).show();

        if (routeWeatherCallback != null) {
            routeWeatherCallback.onFetchWeatherAtPoints(waypoints, selectedRouteName);
        }

        // Show route weather results as simple text list
        if (routeWeatherResults != null) {
            List<String> resultLines = new ArrayList<>();
            for (int i = 0; i < waypoints.size(); i++) {
                GeoPoint wp = waypoints.get(i);
                resultLines.add(String.format(Locale.US,
                        "WP%d: %.4f\u00b0N %.4f\u00b0E  (fetching...)",
                        i + 1, wp.getLatitude(), wp.getLongitude()));
            }
            routeWeatherResults.setAdapter(
                    WeatherUiUtils.makeDarkSpinnerAdapter(pluginContext, resultLines));
        }
    }

    /**
     * Update route weather results after fetch completes.
     * Called from DDR when weather data arrives for each waypoint.
     */
    public void updateRouteWeatherResult(int waypointIndex, String summary) {
        // Update the specific line in the results list
        if (routeWeatherResults != null && routeWeatherResults.getAdapter() != null) {
            @SuppressWarnings("unchecked")
            ArrayAdapter<String> adapter =
                    (ArrayAdapter<String>) routeWeatherResults.getAdapter();
            if (waypointIndex >= 0 && waypointIndex < adapter.getCount()) {
                // Can't update individual items easily in ArrayAdapter;
                // the caller should build the full list and set it
            }
        }
    }

    /**
     * Set the full route weather results list (called after all waypoints fetched).
     */
    public void setRouteWeatherResults(List<String> results) {
        if (routeWeatherResults != null) {
            routeWeatherResults.setAdapter(
                    WeatherUiUtils.makeDarkSpinnerAdapter(pluginContext, results));
        }
    }

    /**
     * Feed route weather chart data. Called from DDR after all waypoints are fetched.
     */
    public void setRouteWeatherChartData(List<RouteWeatherChartView.WaypointData> chartData) {
        if (routeChart != null) {
            routeChart.setData(chartData);
        }
    }

    public void dispose() {
        // Unregister MapGroup listeners
        if (groupListener != null) {
            if (weatherMarkerManager != null && weatherMarkerManager.getMapGroup() != null) {
                weatherMarkerManager.getMapGroup().removeOnItemListChangedListener(groupListener);
            }
            if (windMarkerManager != null && windMarkerManager.getMapGroup() != null) {
                windMarkerManager.getMapGroup().removeOnItemListChangedListener(groupListener);
            }
        }
        weatherMarkerManager = null;
        windMarkerManager = null;
        routeWeatherCallback = null;
        selectedRoute = null;
    }
}
