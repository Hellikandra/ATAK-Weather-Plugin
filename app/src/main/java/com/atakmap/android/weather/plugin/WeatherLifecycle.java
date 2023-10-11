package com.atakmap.android.weather.plugin;

// ----- Android API import ----- //

// ----- Local API import ----- //

// ----- ATAK API import ----- //
import com.atak.plugins.impl.AbstractPlugin;
import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.maps.MapComponent;
import com.atakmap.android.weather.WeatherMapComponent;
import com.atakmap.android.weather.plugin.WeatherTool;

import gov.tak.api.plugin.IServiceController;

public class WeatherLifecycle extends AbstractPlugin
{
    /** ************************* CONSTRUCTOR ************************* **/
    public WeatherLifecycle (IServiceController isc)
    {
        super(isc, new WeatherTool(((PluginContextProvider) isc.getService(PluginContextProvider.class)).getPluginContext()),
                (MapComponent) new WeatherMapComponent());
    }
}