package org.dtakc.weather.atak.plugin;

import com.atak.plugins.impl.AbstractPlugin;
import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.maps.MapComponent;

import gov.tak.api.plugin.IServiceController;

/** ATAK plugin lifecycle entry point. */
public final class WeatherLifecycle extends AbstractPlugin {
    public WeatherLifecycle(IServiceController isc) {
        super(isc,
              new WeatherTool(((PluginContextProvider) isc.getService(PluginContextProvider.class))
                      .getPluginContext()),
              (MapComponent) new WeatherPluginComponent());
    }
}
