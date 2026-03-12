 package org.dtakc.weather.atak.ui.preferences;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;

import com.atakmap.android.gui.PanEditTextPreference;
import com.atakmap.android.gui.PanListPreference;
import com.atakmap.android.preference.PluginPreferenceFragment;
import org.dtakc.weather.atak.plugin.R;
import com.atakmap.coremap.log.Log;

public class WeatherPreferenceFragment extends PluginPreferenceFragment {

    private static Context staticPluginContext;
    private static final String TAG = "WeatherPreferenceFragment";
    public static final String PANE_EDITTEXT_INT = "WeatherPanEditTextPreferenceInt";

    public WeatherPreferenceFragment() {
        super(staticPluginContext, R.xml.preferences);
    }

    @SuppressLint("ValidFragment")
    public WeatherPreferenceFragment(final Context pluginContext) {
        super(pluginContext, R.xml.preferences);
        staticPluginContext = pluginContext;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            ((PanEditTextPreference) findPreference("pan_edit_text_test")).checkValidInteger();
        } catch (Exception e) {
            Log.d(TAG, "error regarding PanEditTextPreference => " + e);
        }

        PanListPreference panEditTextTestInput = (PanListPreference) findPreference(PANE_EDITTEXT_INT);
    }

    @Override
    public String getSubTitle() {
        return getSubTitle("Tool Preferences", "Weather Preferences");
    }
}
