package org.dtakc.weather.atak.ui;

import android.content.Context;
import android.content.Intent;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;

import org.dtakc.weather.atak.map.marker.WeatherMarkerManager;
import org.dtakc.weather.atak.map.marker.WindMarkerManager;
import org.dtakc.weather.atak.plugin.R;
import org.dtakc.weather.atak.ui.tab.WeatherTabCoordinator;

import android.view.View;

/**
 * Thin DropDownReceiver — intent routing only (~150 lines).
 *
 * ISS-01 fix: God Class split into:
 *   WeatherDropDownController  — this file (intent routing)
 *   WeatherTabCoordinator      — tab host wiring
 *   WeatherDependencyContainer — DI
 *   ForecastTabPresenter       — Tab 1 (weather + chart)
 *   WindTabPresenter           — Tab 2 (wind profile)
 *   ConfigTabPresenter         — Tab 3 (source + parameters)
 *   RadarTabPresenter          — Tab 4 (radar overlay)
 *   MapPickTabPresenter        — Tab 5 (marker placement)
 *   ComparisonTabPresenter     — Tab 6 (comparison)
 *
 * ISS-09 fix: SHARE_MARKER and REMOVE_MARKER are registered in onCreate()
 *             via the constructor here, NOT lazily inside onReceive().
 */
public final class WeatherDropDownController extends DropDownReceiver
        implements OnStateListener {

    public static final String SHOW_PLUGIN   = "org.dtakc.weather.atak.SHOW_PLUGIN";
    public static final String SHARE_MARKER  = "org.dtakc.weather.atak.SHARE_MARKER";
    public static final String REMOVE_MARKER = "org.dtakc.weather.atak.REMOVE_MARKER";

    public static final String EXTRA_TARGET_UID    = "targetUID";
    public static final String EXTRA_REQUESTED_TAB = "requestedTab";

    private final View                    templateView;
    private final Context                 pluginContext;
    private final WeatherDependencyContainer deps;
    private final WeatherTabCoordinator   tabCoordinator;

    public WeatherDropDownController(MapView mapView,
                                     Context pluginContext,
                                     WeatherMarkerManager markerManager,
                                     WindMarkerManager windMarkerManager) {
        super(mapView);
        this.pluginContext = pluginContext;
        templateView = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);
        deps = new WeatherDependencyContainer(pluginContext, mapView);

        // ISS-09: register for SHARE_MARKER / REMOVE_MARKER here (not in onReceive)
        AtakBroadcast.DocumentedIntentFilter extraFilter =
                new AtakBroadcast.DocumentedIntentFilter();
        extraFilter.addAction(SHARE_MARKER);
        extraFilter.addAction(REMOVE_MARKER);
        AtakBroadcast.getInstance().registerReceiver(this, extraFilter);

        tabCoordinator = new WeatherTabCoordinator(
                mapView, pluginContext, templateView,
                deps, markerManager, windMarkerManager);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (action == null) return;

        if (SHARE_MARKER.equals(action)) {
            tabCoordinator.handleShareMarker(intent.getStringExtra(EXTRA_TARGET_UID));
            return;
        }
        if (REMOVE_MARKER.equals(action)) {
            tabCoordinator.handleRemoveMarker(intent.getStringExtra(EXTRA_TARGET_UID));
            return;
        }
        if (!SHOW_PLUGIN.equals(action)) return;

        showDropDown(templateView, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, false, this);
        tabCoordinator.onShow(
                intent.getStringExtra(EXTRA_TARGET_UID),
                intent.getStringExtra(EXTRA_REQUESTED_TAB));
    }

    /** Called from WeatherPluginComponent.onDestroyImpl(). */
    public void clearWindShapes() { tabCoordinator.clearWindShapes(); }

    @Override
    public void disposeImpl() {
        tabCoordinator.dispose();
        deps.dispose();
    }

    @Override public void onDropDownSelectionRemoved() {}
    @Override public void onDropDownVisible(boolean v) {}
    @Override public void onDropDownSizeChanged(double w, double h) {}
    @Override public void onDropDownClose() {}
}
