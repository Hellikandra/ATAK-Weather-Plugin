package org.dtakc.weather.atak.plugin;

import android.content.Context;
import com.atak.plugins.impl.AbstractPluginTool;

/** ATAK toolbar button entry point. */
public final class WeatherTool extends AbstractPluginTool {
    public WeatherTool(Context context) {
        super(context,
              context.getString(R.string.app_name),
              context.getString(R.string.app_name),
              context.getResources().getDrawable(R.drawable.ic_launcher),
              "org.dtakc.weather.atak.SHOW_PLUGIN");
        PluginNativeLoader.init(context);
    }
}
