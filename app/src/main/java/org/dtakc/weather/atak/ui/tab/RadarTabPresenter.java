package org.dtakc.weather.atak.ui.tab;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import com.atakmap.coremap.log.Log;

import com.atakmap.android.maps.MapView;

import org.dtakc.weather.atak.data.remote.SourceDefinitionLoader;
import org.dtakc.weather.atak.data.remote.WeatherSourceDefinition;
import org.dtakc.weather.atak.plugin.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RadarTabPresenter — drives the CONF tab radar source selector.
 *
 * Reads radar source definitions from assets/weather_sources/ (files with a
 * "radarSourceId" field) and populates spinner_radar_source.  The ⟳ button
 * rescans without a plugin restart.
 *
 * Actual tile rendering is handled by RadarOverlayManager (map layer);
 * this presenter only manages source selection and info display.
 */
public final class RadarTabPresenter {

    private static final String TAG = "RadarTabPresenter";

    private final MapView  mapView;
    private final Context  pluginContext;
    private final View     root;

    private boolean initialized = false;

    public RadarTabPresenter(MapView mv, Context ctx, View root) {
        this.mapView       = mv;
        this.pluginContext = ctx;
        this.root          = root;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void init() {
        if (initialized) return;
        initialized = true;

        Button refreshBtn = root.findViewById(R.id.btn_refresh_radar_sources);
        if (refreshBtn != null) {
            refreshBtn.setOnClickListener(v -> {
                SourceDefinitionLoader.clearCache();
                buildRadarSpinner();
            });
        }

        buildRadarSpinner();
    }

    public void dispose() {
        initialized = false;
    }

    // ── Spinner ───────────────────────────────────────────────────────────────

    private void buildRadarSpinner() {
        Spinner spinner = root.findViewById(R.id.spinner_radar_source);
        if (spinner == null) {
            Log.w(TAG, "spinner_radar_source not found");
            return;
        }

        List<WeatherSourceDefinition> radarSources =
                SourceDefinitionLoader.loadRadarSources(pluginContext);

        Log.d(TAG, "buildRadarSpinner: found " + radarSources.size() + " radar sources");

        if (radarSources.isEmpty()) {
            spinner.setAdapter(makeDarkAdapter(java.util.Collections.singletonList("No sources found")));
            setInfoText("No radar source definitions found.\nDrop a JSON file in /sdcard/atak/tools/weather_sources/ and tap ⟳.");
            return;
        }

        final List<String> labels = new ArrayList<>();
        for (WeatherSourceDefinition d : radarSources) labels.add(d.displayName);

        spinner.setAdapter(makeDarkAdapter(labels));
        spinner.setSelection(0, false);

        // Show info for the first source immediately
        showRadarInfo(radarSources.get(0));

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view,
                                       int position, long id) {
                if (position >= 0 && position < radarSources.size()) {
                    showRadarInfo(radarSources.get(position));
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void showRadarInfo(WeatherSourceDefinition def) {
        if (def == null) return;
        StringBuilder sb = new StringBuilder();
        if (def.description != null && !def.description.isEmpty()) {
            // Truncate long descriptions for the info line
            String desc = def.description.length() > 120
                    ? def.description.substring(0, 117) + "…"
                    : def.description;
            sb.append(desc).append("\n");
        }
        if (def.attribution != null && !def.attribution.isEmpty()) {
            sb.append("© ").append(def.attribution);
        }
        setInfoText(sb.toString().trim());
    }

    private void setInfoText(String msg) {
        TextView tv = root.findViewById(R.id.textview_radar_source_info);
        if (tv == null) return;
        tv.setText(msg);
        tv.setVisibility(msg.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ── Custom dark-background spinner adapter ────────────────────────────────

    private ArrayAdapter<String> makeDarkAdapter(final List<String> items) {
        return new ArrayAdapter<String>(pluginContext,
                android.R.layout.simple_spinner_item, items) {

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = makeTextView(getItem(position));
                tv.setTextColor(Color.WHITE);
                tv.setBackgroundColor(Color.TRANSPARENT);
                tv.setPadding(8, 8, 8, 8);
                return tv;
            }

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
