package com.atakmap.android.weather.plugin;

// ----- Android API import ----- //;
import android.content.Context;

// ----- ATAK API import ----- //
import com.atak.plugins.impl.AbstractPluginTool;

public class WeatherTool extends AbstractPluginTool
{
    /** ************************* CONSTRUCTOR ************************* **/
    public WeatherTool (final Context context)
    {
        super(context, context.getString(R.string.app_name), context.getString(R.string.app_name),
                context.getResources().getDrawable(R.drawable.ic_launcher),
                "com.atakmap.android.weather.SHOW_PLUGIN");
        PluginNativeLoader.init(context);
    }
}