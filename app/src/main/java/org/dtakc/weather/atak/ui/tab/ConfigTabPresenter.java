package org.dtakc.weather.atak.ui.tab;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

import org.dtakc.weather.atak.data.remote.SourceDefinitionLoader;
import org.dtakc.weather.atak.data.remote.WeatherDataSourceRegistry;
import org.dtakc.weather.atak.data.remote.WeatherSourceDefinition;
import org.dtakc.weather.atak.plugin.R;
import org.dtakc.weather.atak.ui.WeatherDependencyContainer;
import org.dtakc.weather.atak.ui.view.ParametersView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ConfigTabPresenter — drives the PARM tab (subTabWidget4).
 *
 * Wires the source Spinner → SourceDefinitionLoader → ParametersView pipeline
 * that was previously unconnected (stub init()).
 */
public final class ConfigTabPresenter {

    private static final String TAG = "ConfigTabPresenter";

    private final Context                    pluginContext;
    private final View                       root;
    private final WeatherDependencyContainer deps;

    private ParametersView paramsView;
    private boolean        initialized = false;

    public ConfigTabPresenter(MapView mv, Context ctx, View root,
                              WeatherDependencyContainer deps) {
        this.pluginContext = ctx;
        this.root          = root;
        this.deps          = deps;
    }

    public ConfigTabPresenter(Context ctx, View root, WeatherDependencyContainer deps) {
        this(null, ctx, root, deps);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void init() {
        if (initialized) return;
        initialized = true;

        paramsView = new ParametersView(root, pluginContext, deps.paramPrefs);

        Button refreshBtn = root.findViewById(R.id.btn_refresh_sources);
        if (refreshBtn != null) {
            refreshBtn.setOnClickListener(v -> {
                SourceDefinitionLoader.clearCache();
                buildSpinner();
            });
        }

        buildSpinner();
    }

    public void dispose() {
        initialized = false;
    }

    // ── Spinner ───────────────────────────────────────────────────────────────

    private void buildSpinner() {
        Spinner spinner = root.findViewById(R.id.spinner_parm_source);
        if (spinner == null) {
            Log.w(TAG, "spinner_parm_source not found in root view");
            return;
        }

        WeatherDataSourceRegistry registry =
                WeatherDataSourceRegistry.getInstance(pluginContext);
        List<WeatherDataSourceRegistry.SourceEntry> entries =
                registry.getAvailableEntries();

        final List<String> labels = new ArrayList<>();
        for (WeatherDataSourceRegistry.SourceEntry e : entries) labels.add(e.displayName);

        spinner.setAdapter(makeDarkAdapter(labels));

        int activeIdx = registry.getActiveSourceIndex();
        if (activeIdx >= 0 && activeIdx < labels.size()) {
            spinner.setSelection(activeIdx, false);
        }

        if (!entries.isEmpty()) {
            int idx = (activeIdx >= 0 && activeIdx < entries.size()) ? activeIdx : 0;
            loadParamsForSource(entries.get(idx).sourceId);
        }

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view,
                                       int position, long id) {
                if (position < 0 || position >= entries.size()) return;
                String selectedId = entries.get(position).sourceId;
                registry.setActiveSourceId(selectedId);
                loadParamsForSource(selectedId);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    // ── Params ────────────────────────────────────────────────────────────────

    private void loadParamsForSource(String sourceId) {
        if (paramsView == null) return;

        Map<String, WeatherSourceDefinition> allDefs =
                SourceDefinitionLoader.loadAll(pluginContext);
        WeatherSourceDefinition def = allDefs.get(sourceId);

        Log.d(TAG, "loadParamsForSource: id=" + sourceId
                + " def=" + (def != null ? "found" : "null")
                + " allDefs.size=" + allDefs.size());

        if (def != null) {
            paramsView.setDefinitionParams(
                    sourceId,
                    def.hourlyParams  != null ? def.hourlyParams  : new ArrayList<>(),
                    def.dailyParams   != null ? def.dailyParams   : new ArrayList<>(),
                    def.currentParams != null ? def.currentParams : new ArrayList<>());
            paramsView.setSourceDescription(def.description);
            setStatusText("");
        } else {
            paramsView.setDefinitionParams(
                    sourceId, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            paramsView.setSourceDescription(null);
            setStatusText("No definition file for \"" + sourceId + "\".");
        }
    }

    private void setStatusText(String msg) {
        TextView tv = root.findViewById(R.id.textview_parm_source_status);
        if (tv == null) return;
        tv.setText(msg);
        tv.setVisibility(msg.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ── Custom dark-background spinner adapter ────────────────────────────────

    /**
     * Standard android.R.layout.simple_spinner_* items use the app theme text
     * colour, which in ATAK's Holo-dark context can be white, making white-on-white
     * text invisible in the dropdown popup.  We create our own views with explicit
     * black text on a white/light background for the dropdown rows, and white text
     * for the collapsed view (which sits on the plugin's dark background).
     */
    private ArrayAdapter<String> makeDarkAdapter(final List<String> items) {
        return new ArrayAdapter<String>(pluginContext,
                android.R.layout.simple_spinner_item, items) {

            // Collapsed (selected item shown in the spinner bar) — white text on dark bg
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = makeTextView(getItem(position));
                tv.setTextColor(Color.WHITE);
                tv.setBackgroundColor(Color.TRANSPARENT);
                tv.setPadding(8, 8, 8, 8);
                return tv;
            }

            // Dropdown rows — dark text on light background so they're readable
            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView tv = makeTextView(getItem(position));
                tv.setTextColor(Color.BLACK);
                tv.setBackgroundColor(Color.WHITE);
                tv.setPadding(16, 20, 16, 20);
                return tv;
            }

            private TextView makeTextView(String text) {
                TextView tv = new TextView(pluginContext);
                tv.setText(text != null ? text : "");
                tv.setTextSize(14f);
                return tv;
            }
        };
    }
}
