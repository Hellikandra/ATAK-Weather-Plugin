package org.dtakc.weather.atak.map.widget;

import android.content.Context;
import android.content.Intent;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.widgets.AbstractWidgetMapComponent;
import com.atakmap.android.widgets.RootLayoutWidget;

import org.dtakc.weather.atak.domain.model.WeatherModel;
import org.dtakc.weather.atak.ui.WeatherDropDownController;

/**
 * WeatherWidgetComponent — HUD overlay showing live weather conditions.
 *
 * Registered in WeatherPluginComponent.onCreate().
 * Shows: temperature, wind speed/dir, location label,
 *        flight-category badge (METAR sources).
 *
 * Tap → opens the weather drop-down (SHOW_PLUGIN broadcast).
 */
public final class WeatherWidgetComponent extends AbstractWidgetMapComponent {

    private WeatherHudWidget hudWidget;

    @Override
    public void onCreateWidgets(Context context, Intent intent, MapView mapView) {
        hudWidget = new WeatherHudWidget(context, mapView);
        RootLayoutWidget root = (RootLayoutWidget) mapView.getComponentExtra("rootLayoutWidget");
        if (root != null) {
            root.addWidget(hudWidget);
            hudWidget.setVisible(true);
        }
    }

    /** Required by AbstractWidgetMapComponent in ATAK 5.x. */
    @Override
    public void onDestroyWidgets(Context context, MapView view) {
        removeHud(view);
    }

    @Override
    protected void onDestroyImpl(Context context, MapView view) {
        removeHud(view);
        super.onDestroyImpl(context, view);
    }

    /** Update the HUD with the latest weather data. Called from WeatherViewModel observer. */
    public void updateWeather(WeatherModel w) {
        if (hudWidget != null) hudWidget.bind(w);
    }

    private void removeHud(MapView view) {
        if (hudWidget != null) {
            RootLayoutWidget root = (RootLayoutWidget) view.getComponentExtra("rootLayoutWidget");
            if (root != null) root.removeWidget(hudWidget);
            hudWidget = null;
        }
    }
}
