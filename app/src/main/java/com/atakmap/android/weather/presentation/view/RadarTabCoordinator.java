package com.atakmap.android.weather.presentation.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.weather.data.remote.SourceDefinitionLoader;
import com.atakmap.android.weather.data.remote.WeatherSourceDefinition;
import com.atakmap.android.weather.overlay.radar.RadarOverlayManager;
import com.atakmap.android.weather.overlay.radar.RadarTileProvider;
import com.atakmap.android.weather.plugin.R;
import com.atakmap.android.weather.util.WeatherUiUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Coordinator for the CONF tab's radar overlay controls.
 *
 * <p>Extracted from {@code WeatherDropDownReceiver.initRadarControls()}.
 * The DDR creates one instance of this class in {@code initViewHelpers()} and
 * calls {@link #dispose()} from {@code disposeImpl()}.</p>
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Instantiate and own {@link RadarOverlayManager}.</li>
 *   <li>Populate the radar-source spinner from {@link SourceDefinitionLoader}.</li>
 *   <li>Wire the Show / Hide / Recenter buttons and the frame / opacity seekbars.</li>
 *   <li>Forward {@link RadarOverlayManager.Listener} events to the UI labels.</li>
 * </ul>
 */
@SuppressLint("SetTextI18n")
public class RadarTabCoordinator {

    private final Context            pluginContext;
    private final RadarOverlayManager radarManager;
    private final View               rootView;

    public RadarTabCoordinator(MapView mapView, View rootView, Context pluginContext) {
        this.pluginContext = pluginContext;
        this.rootView      = rootView;
        this.radarManager  = new RadarOverlayManager(mapView);

        wireRadarSourceSpinner();
        wireListenerAndButtons(mapView);
        wireSeekBars();
    }

    // ── Private wiring ────────────────────────────────────────────────────────

    private void wireRadarSourceSpinner() {
        android.widget.Spinner radarSourceSpinner =
                rootView.findViewById(R.id.spinner_radar_source);
        if (radarSourceSpinner == null) return;

        List<WeatherSourceDefinition> radarSources =
                SourceDefinitionLoader.loadRadarSources(pluginContext);

        if (radarSources.isEmpty()) {
            radarSources = new ArrayList<>();
            radarSources.add(new WeatherSourceDefinition.Builder()
                    .radarSourceId("rainviewer")
                    .displayName("RainViewer (built-in)")
                    .manifestUrl(RadarTileProvider.MANIFEST_URL)
                    .build());
        }

        final List<WeatherSourceDefinition> finalSources = radarSources;
        List<String> names = new ArrayList<>();
        for (WeatherSourceDefinition d : radarSources) names.add(d.displayName);

        ArrayAdapter<String> adapter =
                WeatherUiUtils.makeDarkSpinnerAdapter(
                        rootView.getContext(), names);
        radarSourceSpinner.setAdapter(adapter);
        radarSourceSpinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
                    @Override public void onItemSelected(android.widget.AdapterView<?> p,
                                                         android.view.View v,
                                                         int pos, long id) {
                        WeatherSourceDefinition def = finalSources.get(pos);
                        if (def.manifestUrl != null) {
                            radarManager.setRadarSource(def.manifestUrl, def.tileUrlTemplate);
                        }
                    }
                });

        // Refresh-sources button
        Button btnRefreshRadarSrc = rootView.findViewById(R.id.btn_refresh_radar_sources);
        if (btnRefreshRadarSrc != null) {
            btnRefreshRadarSrc.setOnClickListener(v -> {
                SourceDefinitionLoader.clearCache();
                List<WeatherSourceDefinition> refreshed =
                        SourceDefinitionLoader.loadRadarSources(pluginContext);
                Toast.makeText(pluginContext,
                        pluginContext.getString(R.string.radar_sources_refreshed, refreshed.size()),
                        Toast.LENGTH_SHORT).show();
                if (radarSourceSpinner.getAdapter() instanceof ArrayAdapter) {
                    @SuppressWarnings("unchecked")
                    ArrayAdapter<String> ada =
                            (ArrayAdapter<String>) radarSourceSpinner.getAdapter();
                    ada.clear();
                    for (WeatherSourceDefinition d : refreshed) ada.add(d.displayName);
                    ada.notifyDataSetChanged();
                }
            });
        }
    }

    private void wireListenerAndButtons(MapView mapView) {
        final TextView statusView = rootView.findViewById(R.id.textview_radar_status);
        final TextView diagView   = rootView.findViewById(R.id.textview_radar_diagnostics);
        final SeekBar  frameSeek  = rootView.findViewById(R.id.seekbar_radar_frame);
        final TextView timeLabel  = rootView.findViewById(R.id.textview_radar_time);
        final Button   btnShow    = rootView.findViewById(R.id.btn_radar_show);
        final Button   btnHide    = rootView.findViewById(R.id.btn_radar_hide);
        final Button   btnRecenter= rootView.findViewById(R.id.btn_radar_recenter);

        radarManager.setListener(new RadarOverlayManager.Listener() {
            @Override public void onManifestLoaded(int total, int defIdx) {
                if (frameSeek  != null) { frameSeek.setMax(total - 1); frameSeek.setProgress(defIdx); }
                if (statusView != null)
                    statusView.setText(pluginContext.getString(R.string.radar_status_ready, total));
            }
            @Override public void onFrameDisplayed(int idx, String label) {
                if (timeLabel != null) timeLabel.setText(label);
            }
            @Override public void onDiagnosticsUpdated(String info) {
                if (diagView != null) diagView.setText(info);
            }
            @Override public void onError(String msg) {
                if (statusView != null) statusView.setText("⚠ " + msg);
                Toast.makeText(pluginContext, "Radar: " + msg, Toast.LENGTH_SHORT).show();
            }
        });

        if (btnShow != null) btnShow.setOnClickListener(v -> {
            if (mapView.getMapTilt() > 5.0) {
                Toast.makeText(pluginContext,
                        "ⓘ Radar overlay is 2D only — disable 3D tilt for geo-locked display",
                        Toast.LENGTH_LONG).show();
            }
            if (statusView != null) statusView.setText(R.string.radar_status_loading);
            radarManager.start();
        });

        if (btnHide != null) btnHide.setOnClickListener(v -> {
            radarManager.stop();
            if (statusView != null) statusView.setText(R.string.radar_status_idle);
            if (timeLabel  != null) timeLabel.setText("—");
            if (diagView   != null) diagView.setText("");
        });

        if (btnRecenter != null) btnRecenter.setOnClickListener(v -> radarManager.refresh());
    }

    private void wireSeekBars() {
        final SeekBar  frameSeek   = rootView.findViewById(R.id.seekbar_radar_frame);
        final SeekBar  opacitySeek = rootView.findViewById(R.id.seekbar_radar_opacity);
        final TextView opacityLbl  = rootView.findViewById(R.id.textview_radar_opacity);

        if (frameSeek != null) {
            frameSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                    if (u) radarManager.setFrameIndex(p);
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb)  {}
            });
        }

        if (opacitySeek != null) {
            opacitySeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                    radarManager.setOpacity(p);
                    if (opacityLbl != null) opacityLbl.setText(p + "%");
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb)  {}
            });
        }
    }

    // ── Public lifecycle ──────────────────────────────────────────────────────

    /** Call from DDR {@code disposeImpl()}. */
    public void dispose() {
        radarManager.dispose();
    }
}
