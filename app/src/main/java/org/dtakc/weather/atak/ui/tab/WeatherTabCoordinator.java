package org.dtakc.weather.atak.ui.tab;

import android.content.Context;
import android.view.View;
import android.widget.TabHost;

import com.atakmap.android.maps.MapView;

import org.dtakc.weather.atak.map.marker.WeatherMarkerManager;
import org.dtakc.weather.atak.map.marker.WindMarkerManager;
import org.dtakc.weather.atak.plugin.R;
import org.dtakc.weather.atak.ui.WeatherDependencyContainer;

/**
 * Owns the TabHost, creates all tab Presenters, and routes onShow() calls.
 * ~80 lines — zero business logic.
 */
public final class WeatherTabCoordinator {

    private final TabHost          tabHost;
    private final ForecastTabPresenter   forecastPresenter;
    private final WindTabPresenter       windPresenter;
    private final ConfigTabPresenter     configPresenter;
    private final RadarTabPresenter      radarPresenter;
    private final MapPickTabPresenter    mapPickPresenter;
    private final ComparisonTabPresenter comparisonPresenter;

    private boolean initialized = false;

    public WeatherTabCoordinator(MapView mapView, Context pluginContext,
                                 View templateView,
                                 WeatherDependencyContainer deps,
                                 WeatherMarkerManager markerManager,
                                 WindMarkerManager windMarkerManager) {
        tabHost = templateView.findViewById(R.id.mainTabHost);
        setupTabHost(tabHost);

        forecastPresenter   = new ForecastTabPresenter(mapView, pluginContext, templateView, deps);
        windPresenter       = new WindTabPresenter(mapView, pluginContext, templateView, deps, windMarkerManager);
        configPresenter     = new ConfigTabPresenter(pluginContext, templateView, deps);
        radarPresenter      = new RadarTabPresenter(mapView, pluginContext, templateView);
        mapPickPresenter    = new MapPickTabPresenter(mapView, pluginContext, templateView, deps, markerManager);
        comparisonPresenter = new ComparisonTabPresenter(pluginContext, templateView, deps);
    }

    public void onShow(String targetUid, String requestedTab) {
        if (!initialized) {
            forecastPresenter.init();
            windPresenter.init();
            configPresenter.init();
            radarPresenter.init();
            mapPickPresenter.init();
            comparisonPresenter.init();
            initialized = true;
        }
        if (targetUid != null) {
            forecastPresenter.loadForMarker(targetUid);
            jumpToTab(requestedTab != null ? requestedTab
                    : (targetUid.startsWith("wx_wind") ? "wind" : "wthr"));
        } else {
            forecastPresenter.triggerAutoLoad();
            if (requestedTab != null) jumpToTab(requestedTab);
        }
    }

    public void handleShareMarker(String uid)  { mapPickPresenter.shareMarker(uid); }
    public void handleRemoveMarker(String uid) { mapPickPresenter.removeMarker(uid); }
    public void clearWindShapes()              { windPresenter.clearWindShapes(); }

    public void dispose() {
        forecastPresenter.dispose();
        windPresenter.dispose();
        radarPresenter.dispose();
        comparisonPresenter.dispose();
        initialized = false;
    }

    @SuppressWarnings("deprecation")
    private static void setupTabHost(TabHost h) {
        h.setup();
        addTab(h, "wthr", R.id.subTabWidget1, "WTHR");
        addTab(h, "wind", R.id.subTabWidget2, "WIND");
        addTab(h, "conf", R.id.subTabWidget3, "CONF");
        addTab(h, "parm", R.id.subTabWidget4, "PARM");
    }

    @SuppressWarnings("deprecation")
    private static void addTab(TabHost h, String tag, int contentId, String indicator) {
        TabHost.TabSpec spec = h.newTabSpec(tag);
        spec.setIndicator(indicator);
        spec.setContent(contentId);
        h.addTab(spec);
    }

    @SuppressWarnings("deprecation")
    private void jumpToTab(String tag) {
        try { tabHost.setCurrentTabByTag(tag); } catch (Exception ignored) {}
    }
}
