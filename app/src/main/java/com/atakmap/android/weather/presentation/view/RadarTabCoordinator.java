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
import com.atakmap.android.weather.data.cache.RadarTileCache;
import com.atakmap.android.weather.data.remote.SourceDefinitionLoader;
import com.atakmap.android.weather.data.remote.WeatherSourceDefinition;
import com.atakmap.android.weather.overlay.radar.RadarOverlayManager;
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
 *   <li><b>Sprint 1.1b:</b> Wire cache management UI (size label + clear button).</li>
 * </ul>
 */
@SuppressLint("SetTextI18n")
public class RadarTabCoordinator {

    private final Context            pluginContext;
    private final RadarOverlayManager radarManager;
    private final View               rootView;

    public RadarTabCoordinator(MapView mapView, View rootView, Context pluginContext,
                               RadarOverlayManager sharedRadarManager) {
        this.pluginContext = pluginContext;
        this.rootView      = rootView;
        // Use the shared RadarOverlayManager from WeatherMapComponent so the
        // Overlay Manager toggle and the DDR Show/Hide buttons act on the same instance.
        this.radarManager  = sharedRadarManager;

        wireRadarSourceSpinner();
        wireListenerAndButtons(mapView);
        wireSeekBars();
        wireColorSeekBars();
        wireCacheControls();
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
                updateCacheSizeLabel();
            }
            @Override public void onFrameDisplayed(int idx, String label) {
                if (timeLabel != null) timeLabel.setText(label);
                updateCacheSizeLabel();
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

    /**
     * Wire the saturation / brightness / intensity seekbars (Sprint 1.1c).
     * Each seekbar maps 0–100 → 0.0–1.0 and delegates to {@link RadarOverlayManager}.
     */
    private void wireColorSeekBars() {
        final SeekBar  satSeek  = rootView.findViewById(R.id.seekbar_radar_saturation);
        final TextView satLbl   = rootView.findViewById(R.id.textview_radar_saturation);
        final SeekBar  valSeek  = rootView.findViewById(R.id.seekbar_radar_value);
        final TextView valLbl   = rootView.findViewById(R.id.textview_radar_value);
        final SeekBar  intSeek  = rootView.findViewById(R.id.seekbar_radar_intensity);
        final TextView intLbl   = rootView.findViewById(R.id.textview_radar_intensity);

        if (satSeek != null) {
            satSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                    radarManager.setSaturation(p / 100f);
                    if (satLbl != null) satLbl.setText(p + "%");
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb)  {}
            });
        }

        if (valSeek != null) {
            valSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                    radarManager.setValue(p / 100f);
                    if (valLbl != null) valLbl.setText(p + "%");
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb)  {}
            });
        }

        if (intSeek != null) {
            intSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                    radarManager.setIntensity(p / 100f);
                    if (intLbl != null) intLbl.setText(p + "%");
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb)  {}
            });
        }
    }

    /**
     * Wire the radar tile cache management controls (Sprint 1.1b).
     * Shows cache size and provides a clear button.
     */
    private void wireCacheControls() {
        final TextView cacheSizeLabel = rootView.findViewById(R.id.textview_radar_cache_size);
        final Button   btnClearCache  = rootView.findViewById(R.id.btn_radar_clear_cache);

        updateCacheSizeLabel();

        if (btnClearCache != null) {
            btnClearCache.setOnClickListener(v -> {
                RadarTileCache cache = radarManager.getDiskCache();
                if (cache != null) {
                    cache.clearAll();
                    Toast.makeText(pluginContext,
                            pluginContext.getString(R.string.radar_cache_cleared),
                            Toast.LENGTH_SHORT).show();
                    updateCacheSizeLabel();
                }
            });
        }
    }

    /** Update the cache size label with current L2 disk cache stats. */
    private void updateCacheSizeLabel() {
        final TextView cacheSizeLabel = rootView.findViewById(R.id.textview_radar_cache_size);
        if (cacheSizeLabel == null) return;
        RadarTileCache cache = radarManager.getDiskCache();
        if (cache != null) {
            cacheSizeLabel.setText(pluginContext.getString(
                    R.string.radar_cache_size_label,
                    cache.getCacheSizeLabel(),
                    cache.getTileCount()));
        } else {
            cacheSizeLabel.setText(R.string.radar_cache_disabled);
        }
    }

    // ── Public lifecycle ──────────────────────────────────────────────────────

    /**
     * Called when the RadarOverlayManager active state changes from outside the DDR
     * (e.g. via the Overlay Manager toggle). Updates the CONF tab status label.
     */
    public void onRadarActiveChanged(boolean isActive) {
        View statusView = rootView.findViewById(R.id.textview_radar_status);
        if (statusView instanceof android.widget.TextView) {
            ((android.widget.TextView) statusView).setText(
                    isActive ? R.string.radar_status_loading : R.string.radar_status_idle);
        }
        updateCacheSizeLabel();
    }

    /**
     * Call from DDR {@code disposeImpl()}.
     * Note: does NOT call radarManager.dispose() — that is WeatherMapComponent's
     * responsibility since the manager outlives the DDR.
     */
    public void dispose() {
        // radarManager lifecycle is owned by WeatherMapComponent, not by this coordinator.
    }
}
