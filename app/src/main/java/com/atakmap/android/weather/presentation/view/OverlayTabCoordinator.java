package com.atakmap.android.weather.presentation.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.weather.data.remote.SourceDefinitionLoader;
import com.atakmap.android.weather.data.remote.schema.WeatherSourceDefinitionV2;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.model.WindProfileModel;
import com.atakmap.android.weather.overlay.cbrn.CbrnOverlayManager;
import com.atakmap.android.weather.overlay.heatmap.HeatmapOverlayManager;
import com.atakmap.android.weather.overlay.aviation.SigmetOverlayManager;
import com.atakmap.android.weather.overlay.lightning.LightningOverlayManager;
import com.atakmap.android.weather.overlay.radar.RadarOverlayManager;
import com.atakmap.android.weather.overlay.radar.RadarSourceSelector;
import com.atakmap.android.weather.plugin.R;
import com.atakmap.android.weather.util.WeatherPlaceTool;
import com.atakmap.android.weather.util.WeatherUiUtils;
import com.atakmap.coremap.log.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Manages the Overlays view sub-tab switching and overlay controls.
 *
 * <p>Sprint 16+: pill-based sub-tab navigation across four overlay panels
 * (Radar, Heatmap, Aviation, CBRN). Each panel provides controls for its
 * overlay type, delegating to the corresponding manager instance.</p>
 */
@SuppressLint("SetTextI18n")
public class OverlayTabCoordinator {

    private static final String TAG = "OverlayTabCoord";

    private final View rootView;
    private final Context pluginContext;
    private final MapView mapView;

    // Sub-panels
    private View radarPanel, heatmapPanel, aviationPanel, cbrnPanel;

    // Pill buttons
    private Button pillRadar, pillHeatmap, pillAviation, pillCbrn;
    private Button activePill = null;

    // Overlay managers (injected)
    private RadarOverlayManager radarManager;
    private HeatmapOverlayManager heatmapManager;
    private SigmetOverlayManager sigmetManager;
    private LightningOverlayManager lightningManager;
    private CbrnOverlayManager cbrnManager;

    // Radar source selector
    private RadarSourceSelector radarSourceSelector;

    // Heatmap legend (panel + map widget)
    private HeatmapLegendView heatmapLegend;
    private com.atakmap.android.weather.overlay.heatmap.HeatmapLegendWidget heatmapLegendWidget;

    // Wind arrow overlay
    private com.atakmap.android.weather.overlay.wind.WindArrowOverlayView windArrowOverlay;

    // Wind particle flow (Windy-style GL overlay)
    private com.atakmap.android.weather.overlay.wind.WindParticleLayer windParticleLayer;

    // Last weather for CBRN
    private WeatherModel lastWeather;
    private List<WindProfileModel> lastWindProfiles;

    // Hourly cache for CBRN time evolution + wind arrows
    private List<com.atakmap.android.weather.domain.model.HourlyEntryModel> hourlyCache;

    // CBRN plume mode: false = straight, true = curved (wind-following)
    private boolean cbrnCurvedMode = false;

    public OverlayTabCoordinator(View rootView, Context pluginContext, MapView mapView) {
        this.rootView = rootView;
        this.pluginContext = pluginContext;
        this.mapView = mapView;
        initViews();
    }

    // ── Manager injection ──────────────────────────────────────────────────────

    public void setRadarManager(RadarOverlayManager mgr) {
        this.radarManager = mgr;
        if (mgr != null) wireRadarListener();
    }

    public void setHeatmapManager(HeatmapOverlayManager mgr) {
        this.heatmapManager = mgr;
        if (mgr != null) wireHeatmapListener();
    }

    public void setSigmetManager(SigmetOverlayManager mgr) {
        this.sigmetManager = mgr;
        if (mgr != null) wireSigmetListener();
    }

    public void setLightningManager(LightningOverlayManager mgr) {
        this.lightningManager = mgr;
        if (mgr != null) wireLightningListener();
    }

    public void setCbrnManager(CbrnOverlayManager mgr) {
        this.cbrnManager = mgr;
        if (mgr != null) wireCbrnListener();
    }

    public void setHeatmapLegendWidget(
            com.atakmap.android.weather.overlay.heatmap.HeatmapLegendWidget w) {
        this.heatmapLegendWidget = w;
    }

    public void setWindArrowOverlay(
            com.atakmap.android.weather.overlay.wind.WindArrowOverlayView v) {
        this.windArrowOverlay = v;
    }

    public void setWindParticleLayer(
            com.atakmap.android.weather.overlay.wind.WindParticleLayer layer) {
        this.windParticleLayer = layer;
    }

    /** Called from DDR when weather data refreshes. */
    public void setLastWeather(WeatherModel weather) {
        this.lastWeather = weather;
        updateCbrnInfo();
    }

    /** Called from DDR when wind profiles refresh. */
    public void setLastWindProfiles(List<WindProfileModel> profiles) {
        this.lastWindProfiles = profiles;
    }

    /** Called from DDR when hourly forecast refreshes. Feeds CBRN time evolution + wind arrows. */
    public void setHourlyCache(List<com.atakmap.android.weather.domain.model.HourlyEntryModel> data) {
        this.hourlyCache = data;
        if (cbrnManager != null) cbrnManager.setHourlyData(data);
        if (windArrowOverlay != null) windArrowOverlay.setWindData(data);
    }

    // ── Init ───────────────────────────────────────────────────────────────────

    private void initViews() {
        pillRadar    = rootView.findViewById(R.id.pill_radar);
        pillHeatmap  = rootView.findViewById(R.id.pill_heatmap);
        pillAviation = rootView.findViewById(R.id.pill_aviation);
        pillCbrn     = rootView.findViewById(R.id.pill_cbrn);

        radarPanel    = rootView.findViewById(R.id.overlay_radar_panel);
        heatmapPanel  = rootView.findViewById(R.id.overlay_heatmap_panel);
        aviationPanel = rootView.findViewById(R.id.overlay_aviation_panel);
        cbrnPanel     = rootView.findViewById(R.id.overlay_cbrn_panel);

        if (pillRadar    != null) pillRadar.setOnClickListener(v -> switchToPanel(radarPanel, pillRadar));
        if (pillHeatmap  != null) pillHeatmap.setOnClickListener(v -> switchToPanel(heatmapPanel, pillHeatmap));
        if (pillAviation != null) pillAviation.setOnClickListener(v -> switchToPanel(aviationPanel, pillAviation));
        if (pillCbrn     != null) pillCbrn.setOnClickListener(v -> switchToPanel(cbrnPanel, pillCbrn));

        switchToPanel(radarPanel, pillRadar);

        initRadarControls();
        initHeatmapControls();
        initAviationControls();
        initCbrnControls();
    }

    // ── Panel switching ────────────────────────────────────────────────────────

    private void switchToPanel(View panel, Button pill) {
        if (radarPanel    != null) radarPanel.setVisibility(View.GONE);
        if (heatmapPanel  != null) heatmapPanel.setVisibility(View.GONE);
        if (aviationPanel != null) aviationPanel.setVisibility(View.GONE);
        if (cbrnPanel     != null) cbrnPanel.setVisibility(View.GONE);

        resetPillStyle(pillRadar);
        resetPillStyle(pillHeatmap);
        resetPillStyle(pillAviation);
        resetPillStyle(pillCbrn);

        if (panel != null) panel.setVisibility(View.VISIBLE);
        if (pill != null) {
            pill.setAlpha(1.0f);
            pill.setTextColor(Color.WHITE);
        }
        activePill = pill;
    }

    private void resetPillStyle(Button pill) {
        if (pill != null) {
            pill.setAlpha(0.6f);
            pill.setTextColor(Color.parseColor("#8b949e"));
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // RADAR
    // ════════════════════════════════════════════════════════════════════════════

    private void initRadarControls() {
        // ── Source spinner ─────────────────────────────────────────────
        Spinner radarSource = rootView.findViewById(R.id.overlay_radar_source);
        if (radarSource != null) {
            radarSourceSelector = new RadarSourceSelector(pluginContext);
            // Clear cache and reload to ensure we have latest sources (including imports)
            SourceDefinitionLoader.clearCache();
            radarSourceSelector.loadSources();
            List<WeatherSourceDefinitionV2> v2 = radarSourceSelector.getAvailableSources();

            if (!v2.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (WeatherSourceDefinitionV2 d : v2) names.add(d.getDisplayName());
                radarSource.setAdapter(WeatherUiUtils.makeDarkSpinnerAdapter(pluginContext, names));
                WeatherUiUtils.styleSpinnerDark(radarSource);

                final List<WeatherSourceDefinitionV2> finalV2 = v2;
                radarSource.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
                    @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                        if (radarManager != null && pos < finalV2.size()) {
                            radarManager.setRadarSource(finalV2.get(pos));
                            radarSourceSelector.setActiveSourceId(
                                    finalV2.get(pos).getRadarSourceId() != null
                                            ? finalV2.get(pos).getRadarSourceId()
                                            : finalV2.get(pos).getSourceId());
                        }
                    }
                });
                int activeIdx = radarSourceSelector.getActiveSourceIndex();
                if (activeIdx >= 0) radarSource.setSelection(activeIdx);
            } else {
                // Fallback: single built-in source
                List<String> fallback = new ArrayList<>();
                fallback.add("RainViewer (built-in)");
                radarSource.setAdapter(WeatherUiUtils.makeDarkSpinnerAdapter(pluginContext, fallback));
                WeatherUiUtils.styleSpinnerDark(radarSource);
            }
        }

        // Refresh sources button
        Button btnRefresh = rootView.findViewById(R.id.overlay_radar_refresh_src);
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> {
                if (radarSourceSelector != null) {
                    SourceDefinitionLoader.clearCache();
                    radarSourceSelector.refreshSources();
                    Toast.makeText(pluginContext, "Radar sources refreshed", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Show/Hide toggle
        CheckBox radarToggle = rootView.findViewById(R.id.overlay_radar_toggle);
        if (radarToggle != null) {
            radarToggle.setOnCheckedChangeListener((btn, checked) -> {
                if (radarManager == null) return;
                if (checked) {
                    radarManager.start();
                    updateRadarStatus("Loading...");
                } else {
                    radarManager.stop();
                    updateRadarStatus("Idle");
                }
            });
        }

        // Opacity
        SeekBar radarOpacity = rootView.findViewById(R.id.overlay_radar_opacity);
        if (radarOpacity != null) {
            radarOpacity.setOnSeekBarChangeListener(new SimpleSeekListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (fromUser && radarManager != null) radarManager.setOpacity(progress);
                    setText(R.id.overlay_radar_opacity_label, progress + "%");
                }
            });
        }

        // Frame seekbar
        SeekBar radarFrame = rootView.findViewById(R.id.overlay_radar_frame);
        if (radarFrame != null) {
            radarFrame.setOnSeekBarChangeListener(new SimpleSeekListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (fromUser && radarManager != null) radarManager.setFrameIndex(progress);
                }
            });
        }

        // Playback
        View btnPlay = rootView.findViewById(R.id.overlay_radar_play);
        if (btnPlay != null) btnPlay.setOnClickListener(v -> {
            if (radarManager != null) { radarManager.start(); updateRadarStatus("Loading..."); }
        });

        View btnStop = rootView.findViewById(R.id.overlay_radar_stop);
        if (btnStop != null) btnStop.setOnClickListener(v -> {
            if (radarManager != null) { radarManager.stop(); updateRadarStatus("Idle"); }
        });

        View btnStep = rootView.findViewById(R.id.overlay_radar_step);
        if (btnStep != null) btnStep.setOnClickListener(v -> {
            SeekBar fSeek = rootView.findViewById(R.id.overlay_radar_frame);
            if (fSeek != null && radarManager != null && fSeek.getProgress() < fSeek.getMax()) {
                int next = fSeek.getProgress() + 1;
                fSeek.setProgress(next);
                radarManager.setFrameIndex(next);
            }
        });
    }

    private void wireRadarListener() {
        // Use addListener to avoid replacing RadarTabCoordinator's primary listener
        radarManager.addListener(new RadarOverlayManager.Listener() {
            @Override public void onManifestLoaded(int total, int defIdx) {
                SeekBar fSeek = rootView.findViewById(R.id.overlay_radar_frame);
                if (fSeek != null) { fSeek.setMax(total - 1); fSeek.setProgress(defIdx); }
                updateRadarStatus("Ready \u2022 " + total + " frames");
                updateRadarDataInfo("cached");
            }
            @Override public void onFrameDisplayed(int idx, String label) {
                setText(R.id.overlay_radar_time, label);
            }
            @Override public void onDiagnosticsUpdated(String info) {
                // no separate diag view in overlay tab
            }
            @Override public void onError(String msg) {
                updateRadarStatus("\u26A0 " + msg);
            }
        });
    }

    private void updateRadarStatus(String text) {
        setText(R.id.overlay_radar_status, text);
    }

    private void updateRadarDataInfo(String source) {
        double lat = mapView.getCenterPoint().get().getLatitude();
        double lon = mapView.getCenterPoint().get().getLongitude();
        String info = String.format(Locale.US,
                "Centre: %.4f\u00B0N %.4f\u00B0E \u2022 %s \u2022 %s",
                lat, lon, source,
                new java.text.SimpleDateFormat("HH:mm", Locale.US)
                        .format(new java.util.Date()));
        setText(R.id.overlay_radar_data_info, info);
    }

    private void updateHeatmapDataInfo(String source) {
        double lat = mapView.getCenterPoint().get().getLatitude();
        double lon = mapView.getCenterPoint().get().getLongitude();
        String info = String.format(Locale.US,
                "Centre: %.4f\u00B0N %.4f\u00B0E \u2022 %s \u2022 %s",
                lat, lon, source,
                new java.text.SimpleDateFormat("HH:mm", Locale.US)
                        .format(new java.util.Date()));
        setText(R.id.overlay_heatmap_data_info, info);
    }

    // ════════════════════════════════════════════════════════════════════════════
    // HEATMAP
    // ════════════════════════════════════════════════════════════════════════════

    private void initHeatmapControls() {
        // Legend
        FrameLayout legendFrame = rootView.findViewById(R.id.overlay_heatmap_legend_frame);
        if (legendFrame != null) {
            heatmapLegend = new HeatmapLegendView(pluginContext);
            legendFrame.addView(heatmapLegend, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }

        // Show/Hide toggle
        CheckBox heatmapToggle = rootView.findViewById(R.id.overlay_heatmap_toggle);
        if (heatmapToggle != null) {
            heatmapToggle.setOnCheckedChangeListener((btn, checked) -> {
                if (heatmapManager == null) return;
                if (checked) {
                    heatmapManager.start();
                } else {
                    heatmapManager.stop();
                }
                // Show/hide the legend widget on the map
                if (heatmapLegendWidget != null) heatmapLegendWidget.setVisible(checked);
                // Toggle wind arrows if Wind Speed is selected
                if (windArrowOverlay != null) {
                    Spinner ps = rootView.findViewById(R.id.overlay_heatmap_param);
                    boolean isWind = ps != null && ps.getSelectedItemPosition() == 1;
                    windArrowOverlay.setArrowsVisible(checked && isWind);
                }
            });
        }

        // Parameter spinner
        Spinner paramSpinner = rootView.findViewById(R.id.overlay_heatmap_param);
        if (paramSpinner != null) {
            List<String> params = Arrays.asList(
                    "Temperature", "Wind Speed", "Humidity", "Pressure");
            paramSpinner.setAdapter(WeatherUiUtils.makeDarkSpinnerAdapter(pluginContext, params));
            WeatherUiUtils.styleSpinnerDark(paramSpinner);

            final String[] paramKeys = {"temperature_2m", "wind_speed_10m",
                    "relative_humidity_2m", "surface_pressure"};
            paramSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
                @Override public void onItemSelected(android.widget.AdapterView<?> p,
                                                     View v, int pos, long id) {
                    if (heatmapManager != null && pos < paramKeys.length) {
                        heatmapManager.setParameter(paramKeys[pos]);
                    }
                    if (heatmapLegend != null && pos < paramKeys.length) {
                        heatmapLegend.applyParameter(paramKeys[pos]);
                    }
                    if (heatmapLegendWidget != null && pos < paramKeys.length) {
                        heatmapLegendWidget.applyParameter(paramKeys[pos]);
                    }
                    // Show wind arrows + config when Wind Speed is selected
                    boolean isWind = pos < paramKeys.length
                            && "wind_speed_10m".equals(paramKeys[pos]);
                    CheckBox heatToggle = rootView.findViewById(R.id.overlay_heatmap_toggle);
                    boolean heatmapOn = heatToggle != null && heatToggle.isChecked();
                    if (windArrowOverlay != null) {
                        windArrowOverlay.setArrowsVisible(isWind && heatmapOn);
                    }
                    View arrowConfig = rootView.findViewById(R.id.overlay_wind_arrow_config);
                    if (arrowConfig != null) {
                        arrowConfig.setVisibility(isWind ? View.VISIBLE : View.GONE);
                    }
                }
            });
        }

        // Refetch button — forces fresh network fetch for current viewport
        Button btnRefetch = rootView.findViewById(R.id.overlay_heatmap_refetch);
        if (btnRefetch != null) {
            btnRefetch.setOnClickListener(v -> {
                if (heatmapManager == null) return;
                heatmapManager.forceRefresh();
                setText(R.id.overlay_heatmap_grid_info, "Fetching fresh data for viewport...");
                updateHeatmapDataInfo("fetching");
                Toast.makeText(pluginContext, "Refetching heatmap data...",
                        Toast.LENGTH_SHORT).show();
            });
        }

        // Wind arrow config controls
        wireWindArrowConfig();

        // Opacity
        SeekBar heatmapOpacity = rootView.findViewById(R.id.overlay_heatmap_opacity);
        if (heatmapOpacity != null) {
            heatmapOpacity.setOnSeekBarChangeListener(new SimpleSeekListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (fromUser && heatmapManager != null) heatmapManager.setOpacity(progress / 100f);
                    setText(R.id.overlay_heatmap_opacity_label, progress + "%");
                }
            });
        }

        // Time seekbar — syncs heatmap, wind arrows, and time label
        SeekBar timeSeek = rootView.findViewById(R.id.overlay_heatmap_time);
        if (timeSeek != null) {
            timeSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (fromUser && heatmapManager != null) {
                        heatmapManager.setHourIndex(progress);
                    }
                    // Sync wind arrows to same hour + update grid data
                    if (windArrowOverlay != null) {
                        windArrowOverlay.setHourIndex(progress);
                    }
                    updateWindGridFromHeatmap(progress);
                    setText(R.id.overlay_heatmap_time_label,
                            progress == 0 ? "Now" : "+" + progress + "h");
                }
            });
        }

        // Heatmap Play/Stop/Step — simple auto-advance timer
        final android.os.Handler playHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable[] playRunnable = { null };

        View btnHeatPlay = rootView.findViewById(R.id.overlay_heatmap_play);
        if (btnHeatPlay != null) {
            btnHeatPlay.setOnClickListener(v -> {
                final SeekBar ts = rootView.findViewById(R.id.overlay_heatmap_time);
                if (ts == null) return;
                playRunnable[0] = new Runnable() {
                    @Override public void run() {
                        int next = ts.getProgress() + 1;
                        if (next > ts.getMax()) next = 0;
                        ts.setProgress(next);
                        if (heatmapManager != null) heatmapManager.setHourIndex(next);
                        if (windArrowOverlay != null) windArrowOverlay.setHourIndex(next);
                        setText(R.id.overlay_heatmap_time_label,
                                next == 0 ? "Now" : "+" + next + "h");
                        playHandler.postDelayed(this, 1000);
                    }
                };
                playHandler.post(playRunnable[0]);
            });
        }

        View btnHeatStop = rootView.findViewById(R.id.overlay_heatmap_stop);
        if (btnHeatStop != null) {
            btnHeatStop.setOnClickListener(v -> {
                if (playRunnable[0] != null) playHandler.removeCallbacks(playRunnable[0]);
            });
        }

        View btnHeatStep = rootView.findViewById(R.id.overlay_heatmap_step);
        if (btnHeatStep != null) {
            btnHeatStep.setOnClickListener(v -> {
                final SeekBar ts = rootView.findViewById(R.id.overlay_heatmap_time);
                if (ts == null) return;
                int next = ts.getProgress() + 1;
                if (next > ts.getMax()) next = 0;
                ts.setProgress(next);
                if (heatmapManager != null) heatmapManager.setHourIndex(next);
                if (windArrowOverlay != null) windArrowOverlay.setHourIndex(next);
                setText(R.id.overlay_heatmap_time_label,
                        next == 0 ? "Now" : "+" + next + "h");
            });
        }
    }

    private void wireWindArrowConfig() {
        // Show/Hide wind arrows checkbox
        CheckBox arrowToggle = rootView.findViewById(R.id.overlay_wind_arrow_toggle);
        if (arrowToggle != null) {
            arrowToggle.setOnCheckedChangeListener((btn, checked) -> {
                if (windArrowOverlay != null) windArrowOverlay.setArrowsVisible(checked);
            });
        }

        // Grid density
        SeekBar gridSeek = rootView.findViewById(R.id.overlay_arrow_grid);
        if (gridSeek != null) {
            gridSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    int cells = progress + 3;  // range 3–16
                    setText(R.id.overlay_arrow_grid_label, String.valueOf(cells));
                    if (windArrowOverlay != null) windArrowOverlay.setGridDensity(cells);
                }
            });
        }

        // Arrow size
        SeekBar sizeSeek = rootView.findViewById(R.id.overlay_arrow_size);
        if (sizeSeek != null) {
            sizeSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    float dp = progress + 8;  // range 8–40
                    setText(R.id.overlay_arrow_size_label, (int) dp + "dp");
                    if (windArrowOverlay != null) windArrowOverlay.setArrowSizeDp(dp);
                }
            });
        }

        // Style buttons
        final Button btnArrow   = rootView.findViewById(R.id.overlay_arrow_style_arrow);
        final Button btnBarb    = rootView.findViewById(R.id.overlay_arrow_style_barb);
        final Button btnChevron = rootView.findViewById(R.id.overlay_arrow_style_chevron);
        final Button btnDot     = rootView.findViewById(R.id.overlay_arrow_style_dot);

        View.OnClickListener styleClick = v -> {
            // Reset all alpha
            if (btnArrow   != null) btnArrow.setAlpha(0.5f);
            if (btnBarb    != null) btnBarb.setAlpha(0.5f);
            if (btnChevron != null) btnChevron.setAlpha(0.5f);
            if (btnDot     != null) btnDot.setAlpha(0.5f);
            // Highlight selected
            v.setAlpha(1.0f);

            if (windArrowOverlay == null) return;
            int id = v.getId();
            if (id == R.id.overlay_arrow_style_barb) {
                windArrowOverlay.setArrowStyle(
                    com.atakmap.android.weather.overlay.wind.WindArrowOverlayView.ArrowStyle.BARB);
            } else if (id == R.id.overlay_arrow_style_chevron) {
                windArrowOverlay.setArrowStyle(
                    com.atakmap.android.weather.overlay.wind.WindArrowOverlayView.ArrowStyle.CHEVRON);
            } else if (id == R.id.overlay_arrow_style_dot) {
                windArrowOverlay.setArrowStyle(
                    com.atakmap.android.weather.overlay.wind.WindArrowOverlayView.ArrowStyle.DOT);
            } else {
                windArrowOverlay.setArrowStyle(
                    com.atakmap.android.weather.overlay.wind.WindArrowOverlayView.ArrowStyle.ARROW);
            }
        };

        if (btnArrow   != null) { btnArrow.setOnClickListener(styleClick);   btnArrow.setAlpha(1.0f); }
        if (btnBarb    != null) { btnBarb.setOnClickListener(styleClick);    btnBarb.setAlpha(0.5f); }
        if (btnChevron != null) { btnChevron.setOnClickListener(styleClick); btnChevron.setAlpha(0.5f); }
        if (btnDot     != null) { btnDot.setOnClickListener(styleClick);     btnDot.setAlpha(0.5f); }

        // ── Windy-style particle flow toggle ─────────────────────────────
        CheckBox particleToggle = rootView.findViewById(R.id.overlay_wind_particle_toggle);
        View particleConfig = rootView.findViewById(R.id.overlay_particle_config);
        if (particleToggle != null) {
            particleToggle.setOnCheckedChangeListener((btn, checked) -> {
                if (windParticleLayer != null) {
                    windParticleLayer.setShowParticles(checked);
                    windParticleLayer.setVisible(checked);
                    // Ensure wind data is loaded into particle layer
                    if (checked && !windParticleLayer.hasData()) {
                        // Try to push current heatmap data
                        SeekBar timeSeek = rootView.findViewById(R.id.overlay_heatmap_time);
                        int hour = timeSeek != null ? timeSeek.getProgress() : 0;
                        updateWindGridFromHeatmap(hour);
                        if (!windParticleLayer.hasData()) {
                            Toast.makeText(pluginContext,
                                    "Enable heatmap + fetch data first to see particles",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                } else if (checked) {
                    Toast.makeText(pluginContext,
                            "Particle layer not available", Toast.LENGTH_SHORT).show();
                }
                if (particleConfig != null) {
                    particleConfig.setVisibility(checked ? View.VISIBLE : View.GONE);
                }
                // Sync wind arrow visibility with arrow checkbox state
                if (windArrowOverlay != null) {
                    CheckBox arrowCb = rootView.findViewById(R.id.overlay_wind_arrow_toggle);
                    boolean arrowsWanted = arrowCb != null && arrowCb.isChecked();
                    windArrowOverlay.setArrowsVisible(arrowsWanted);
                }
            });
        }

        // Particle count
        SeekBar particleCountSeek = rootView.findViewById(R.id.overlay_particle_count);
        if (particleCountSeek != null) {
            particleCountSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    int count = (progress + 1) * 500; // 500–10000
                    setText(R.id.overlay_particle_count_label, String.valueOf(count));
                    if (windParticleLayer != null) windParticleLayer.setParticleCount(count);
                }
            });
        }

        // Particle speed
        SeekBar particleSpeedSeek = rootView.findViewById(R.id.overlay_particle_speed);
        if (particleSpeedSeek != null) {
            particleSpeedSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    float speed = (progress + 2) * 0.1f; // 0.2–2.0
                    setText(R.id.overlay_particle_speed_label,
                            String.format(java.util.Locale.US, "%.1fx", speed));
                    if (windParticleLayer != null) windParticleLayer.setParticleSpeed(speed);
                }
            });
        }

        // Particle trail length
        SeekBar particleTrailSeek = rootView.findViewById(R.id.overlay_particle_trail);
        if (particleTrailSeek != null) {
            particleTrailSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    float life = (progress + 2) * 10; // 20–200
                    setText(R.id.overlay_particle_trail_label, String.valueOf((int) life));
                    if (windParticleLayer != null) windParticleLayer.setParticleLife(life);
                }
            });
        }

        // ── Particle color controls (Intensity / Saturation / Brightness) ──

        SeekBar intensitySeek = rootView.findViewById(R.id.overlay_particle_intensity);
        if (intensitySeek != null) {
            intensitySeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    float v = progress / 100f;
                    setText(R.id.overlay_particle_intensity_label, progress + "%");
                    if (windParticleLayer != null) windParticleLayer.setColorIntensity(v);
                }
            });
        }

        SeekBar saturationSeek = rootView.findViewById(R.id.overlay_particle_saturation);
        if (saturationSeek != null) {
            saturationSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    float v = progress / 100f;
                    setText(R.id.overlay_particle_saturation_label, progress + "%");
                    if (windParticleLayer != null) windParticleLayer.setColorSaturation(v);
                }
            });
        }

        SeekBar brightnessSeek = rootView.findViewById(R.id.overlay_particle_brightness);
        if (brightnessSeek != null) {
            brightnessSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    float v = progress / 100f; // 0–150 → 0.0–1.5
                    setText(R.id.overlay_particle_brightness_label, progress + "%");
                    if (windParticleLayer != null) windParticleLayer.setColorValue(v);
                }
            });
        }
    }

    private void wireHeatmapListener() {
        heatmapManager.setListener(new HeatmapOverlayManager.Listener() {
            @Override public void onDataLoaded(int hoursCount, String[] paramKeys) {
                SeekBar timeSeek = rootView.findViewById(R.id.overlay_heatmap_time);
                if (timeSeek != null && hoursCount > 0) {
                    timeSeek.setMax(hoursCount - 1);
                    timeSeek.setProgress(0);
                }
                setText(R.id.overlay_heatmap_grid_info,
                        "Grid loaded \u2022 " + hoursCount + " hours");
                updateHeatmapCacheBadge();
                updateHeatmapDataInfo("fetched");
                // Feed wind grid data to arrow overlay
                updateWindGridFromHeatmap(0);
            }
            @Override public void onFrameDisplayed(int hourIndex, String timeLabel) {
                setText(R.id.overlay_heatmap_time_label, timeLabel);
                // Update wind grid for the displayed hour
                updateWindGridFromHeatmap(hourIndex);
            }
            @Override public void onError(String message) {
                setText(R.id.overlay_heatmap_grid_info, "\u26A0 " + message);
            }
        });
    }

    /**
     * Extract per-cell wind speed + direction grids from the heatmap dataset
     * and feed them to the wind arrow overlay for per-cell rendering.
     */
    private void updateWindGridFromHeatmap(int hourIndex) {
        com.atakmap.android.weather.overlay.heatmap.HeatmapDataSet ds =
                heatmapManager != null ? heatmapManager.getCurrentDataSet() : null;

        double[][] wsGrid = ds != null ? ds.getGrid("wind_speed_10m", hourIndex) : null;
        double[][] wdGrid = ds != null ? ds.getGrid("wind_direction_10m", hourIndex) : null;

        // Feed arrow overlay
        if (windArrowOverlay != null) {
            if (wsGrid != null && wdGrid != null) {
                com.atakmap.android.weather.overlay.heatmap.GridSpec grid = ds.getGrid();
                windArrowOverlay.setGridWindData(wsGrid, wdGrid,
                        grid.getNorth(), grid.getSouth(),
                        grid.getWest(), grid.getEast());
            } else {
                windArrowOverlay.clearGridData();
            }
        }

        // Feed particle flow layer (Windy-style)
        if (windParticleLayer != null) {
            if (wsGrid != null && wdGrid != null) {
                com.atakmap.android.weather.overlay.heatmap.GridSpec grid = ds.getGrid();
                windParticleLayer.setWindField(wsGrid, wdGrid,
                        grid.getNorth(), grid.getSouth(),
                        grid.getWest(), grid.getEast());
            } else {
                windParticleLayer.clearWindField();
            }
        }
    }

    private void updateHeatmapCacheBadge() {
        TextView badge = rootView.findViewById(R.id.overlay_heatmap_cache_badge);
        if (badge == null) return;
        long now = System.currentTimeMillis();
        // Simple freshness: show "just now" or time since last load
        badge.setText("Updated just now");
        badge.setVisibility(View.VISIBLE);
    }

    // ════════════════════════════════════════════════════════════════════════════
    // AVIATION (SIGMETs + Lightning)
    // ════════════════════════════════════════════════════════════════════════════

    private void initAviationControls() {
        // SIGMET toggle
        CheckBox cbSigmet = rootView.findViewById(R.id.overlay_sigmet_toggle);
        if (cbSigmet != null) {
            cbSigmet.setOnCheckedChangeListener((btn, checked) -> {
                if (sigmetManager != null) {
                    if (checked) sigmetManager.start(); else sigmetManager.stop();
                }
                setText(R.id.overlay_sigmet_status, checked ? "Loading..." : "Off");
            });
        }

        // SIGMET refresh
        Button btnRefreshSigmet = rootView.findViewById(R.id.overlay_sigmet_refresh);
        if (btnRefreshSigmet != null) {
            btnRefreshSigmet.setOnClickListener(v -> {
                if (sigmetManager != null) {
                    sigmetManager.refresh();
                    setText(R.id.overlay_sigmet_status, "Refreshing...");
                }
            });
        }

        // Lightning toggle
        CheckBox cbLightning = rootView.findViewById(R.id.overlay_lightning_toggle);
        if (cbLightning != null) {
            cbLightning.setOnCheckedChangeListener((btn, checked) -> {
                if (lightningManager != null) {
                    if (checked) lightningManager.start(); else lightningManager.stop();
                }
                setText(R.id.overlay_lightning_status, checked ? "Connecting..." : "Off");
            });
        }

        // SIGMET opacity
        SeekBar sigmetOpacity = rootView.findViewById(R.id.overlay_sigmet_opacity);
        if (sigmetOpacity != null) {
            sigmetOpacity.setOnSeekBarChangeListener(new SimpleSeekListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    setText(R.id.overlay_sigmet_opacity_label, progress + "%");
                    // SIGMETs are MapItem Polylines — we can't set opacity directly
                    // on already-placed items. The opacity value is stored and applied
                    // on next refresh when polygons are recreated.
                }
            });
        }

        // Lightning radius spinner
        Spinner radiusSpinner = rootView.findViewById(R.id.overlay_lightning_radius);
        if (radiusSpinner != null) {
            List<String> radii = Arrays.asList("10 km", "25 km", "50 km", "100 km");
            radiusSpinner.setAdapter(WeatherUiUtils.makeDarkSpinnerAdapter(pluginContext, radii));
            WeatherUiUtils.styleSpinnerDark(radiusSpinner);

            final double[] radiusValues = {10.0, 25.0, 50.0, 100.0};
            radiusSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
                @Override public void onItemSelected(android.widget.AdapterView<?> p,
                                                     View v, int pos, long id) {
                    if (lightningManager != null && pos < radiusValues.length) {
                        lightningManager.setProximityRadiusKm(radiusValues[pos]);
                    }
                }
            });
            radiusSpinner.setSelection(1); // default 25 km
        }

        // Lightning altitude filter
        SeekBar altMin = rootView.findViewById(R.id.overlay_lightning_alt_min);
        SeekBar altMax = rootView.findViewById(R.id.overlay_lightning_alt_max);
        if (altMin != null) {
            altMin.setOnSeekBarChangeListener(new SimpleSeekListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    int altKm = progress;
                    setText(R.id.overlay_lightning_alt_min_label, altKm + " km");
                    if (fromUser && lightningManager != null) {
                        lightningManager.setAltitudeFilterMin(altKm * 1000);
                    }
                }
            });
        }
        if (altMax != null) {
            altMax.setOnSeekBarChangeListener(new SimpleSeekListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    int altKm = progress;
                    setText(R.id.overlay_lightning_alt_max_label, altKm + " km");
                    if (fromUser && lightningManager != null) {
                        lightningManager.setAltitudeFilterMax(altKm * 1000);
                    }
                }
            });
        }
    }

    private void wireSigmetListener() {
        sigmetManager.setStatusListener(status -> {
            setText(R.id.overlay_sigmet_status, status);
        });
    }

    private void wireLightningListener() {
        lightningManager.setStatusListener(new LightningOverlayManager.StatusListener() {
            @Override public void onStatusChanged(String status) {
                setText(R.id.overlay_lightning_status, status);
            }
            @Override public void onStrikeCountChanged(int count) {
                setText(R.id.overlay_lightning_count, "Strikes: " + count);
            }
            @Override public void onProximityAlert(double distKm,
                    LightningOverlayManager.LightningStrike strike) {
                Toast.makeText(pluginContext,
                        String.format(Locale.US, "\u26A1 Lightning %.1f km away!", distKm),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ════════════════════════════════════════════════════════════════════════════
    // CBRN
    // ════════════════════════════════════════════════════════════════════════════

    private void initCbrnControls() {
        // Distance spinner
        Spinner distSpinner = rootView.findViewById(R.id.overlay_cbrn_distance);
        if (distSpinner != null) {
            List<String> distances = Arrays.asList("1 km", "2 km", "5 km", "10 km", "20 km");
            distSpinner.setAdapter(WeatherUiUtils.makeDarkSpinnerAdapter(pluginContext, distances));
            WeatherUiUtils.styleSpinnerDark(distSpinner);
            distSpinner.setSelection(2); // default 5 km
        }

        // Plume mode buttons (Straight / Curved)
        Button btnStraight = rootView.findViewById(R.id.overlay_cbrn_mode_straight);
        Button btnCurved   = rootView.findViewById(R.id.overlay_cbrn_mode_curved);
        if (btnStraight != null && btnCurved != null) {
            // Default: straight
            btnStraight.setAlpha(1.0f);
            btnCurved.setAlpha(0.5f);

            btnStraight.setOnClickListener(v -> {
                cbrnCurvedMode = false;
                btnStraight.setAlpha(1.0f);
                btnCurved.setAlpha(0.5f);
            });
            btnCurved.setOnClickListener(v -> {
                cbrnCurvedMode = true;
                btnStraight.setAlpha(0.5f);
                btnCurved.setAlpha(1.0f);
            });
        }

        // Place Release Point button
        Button btnPlace = rootView.findViewById(R.id.overlay_cbrn_place);
        if (btnPlace != null) {
            btnPlace.setOnClickListener(v -> {
                WeatherPlaceTool.start(mapView, WeatherPlaceTool.Mode.CBRN,
                        (pickedPoint, mode) -> {
                            double lat = pickedPoint.getLatitude();
                            double lon = pickedPoint.getLongitude();
                            if (cbrnManager != null && lastWeather != null) {
                                Spinner ds = rootView.findViewById(R.id.overlay_cbrn_distance);
                                double maxKm = 5.0;
                                if (ds != null) {
                                    String sel = (String) ds.getSelectedItem();
                                    if (sel != null) {
                                        try { maxKm = Double.parseDouble(sel.replaceAll("[^0-9.]", "")); }
                                        catch (NumberFormatException ignored) {}
                                    }
                                }

                                if (cbrnCurvedMode && hourlyCache != null && !hourlyCache.isEmpty()) {
                                    // Curved plume — follow hourly wind changes
                                    cbrnManager.calculateAndDisplayCurved(lat, lon,
                                            lastWeather, hourlyCache, maxKm);
                                    Toast.makeText(pluginContext,
                                            "CBRN curved plume placed", Toast.LENGTH_SHORT).show();
                                } else {
                                    // Straight plume — classic Gaussian cone
                                    cbrnManager.calculateAndDisplay(lat, lon, lastWeather,
                                            lastWindProfiles, maxKm);
                                    Toast.makeText(pluginContext,
                                            "CBRN plume placed", Toast.LENGTH_SHORT).show();
                                }
                            } else {
                                Toast.makeText(pluginContext,
                                        "Need weather data first \u2014 fetch from Summary tab",
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
            });
        }

        // Clear button
        Button btnClear = rootView.findViewById(R.id.overlay_cbrn_clear);
        if (btnClear != null) {
            btnClear.setOnClickListener(v -> {
                if (cbrnManager != null) cbrnManager.clear();
                setText(R.id.overlay_cbrn_status, "Cleared");
            });
        }

        // NBC-1 report toggle
        CheckBox nbc1Toggle = rootView.findViewById(R.id.overlay_cbrn_nbc1_toggle);
        if (nbc1Toggle != null) {
            nbc1Toggle.setOnCheckedChangeListener((btn, checked) -> {
                if (cbrnManager != null) cbrnManager.setShowNbc1Report(checked);
            });
        }

        // Time evolution seekbar
        SeekBar cbrnTime = rootView.findViewById(R.id.overlay_cbrn_time);
        if (cbrnTime != null) {
            cbrnTime.setOnSeekBarChangeListener(new SimpleSeekListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (fromUser && cbrnManager != null) {
                        cbrnManager.setTimeHour(progress);
                    }
                    setText(R.id.overlay_cbrn_time_label,
                            progress == 0 ? "Now" : "+" + progress + "h");
                }
            });
        }

        updateCbrnInfo();
    }

    private void wireCbrnListener() {
        cbrnManager.setStatusListener(new CbrnOverlayManager.StatusListener() {
            @Override public void onPlumeCalculated(char stabilityClass, String desc,
                    double windSpeed, double windDir, double maxDownwindKm) {
                setText(R.id.overlay_cbrn_stability,
                        "Stability class: " + stabilityClass + " (" + desc + ")");
                setText(R.id.overlay_cbrn_wind,
                        String.format(Locale.US, "Wind: %.0f\u00B0 @ %.1f m/s", windDir, windSpeed));
                setText(R.id.overlay_cbrn_status,
                        String.format(Locale.US, "Plume: %.1f km downwind", maxDownwindKm));
            }
            @Override public void onPlumeCleared() {
                setText(R.id.overlay_cbrn_status, "Cleared");
            }
        });
    }

    private void updateCbrnInfo() {
        if (cbrnManager == null) return;
        char sc = cbrnManager.getLastStabilityClass();
        setText(R.id.overlay_cbrn_stability,
                "Stability class: " + (sc != 0 ? String.valueOf(sc) : "--"));
        double ws = cbrnManager.getLastWindSpeed();
        double wd = cbrnManager.getLastWindDir();
        if (ws > 0) {
            setText(R.id.overlay_cbrn_wind,
                    String.format(Locale.US, "Wind: %.0f\u00B0 @ %.1f m/s", wd, ws));
        } else {
            setText(R.id.overlay_cbrn_wind, "Wind: --");
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    public void showSubPanel(String panelName) {
        switch (panelName) {
            case "radar":    switchToPanel(radarPanel, pillRadar);       break;
            case "heatmap":  switchToPanel(heatmapPanel, pillHeatmap);   break;
            case "aviation": switchToPanel(aviationPanel, pillAviation); break;
            case "cbrn":     switchToPanel(cbrnPanel, pillCbrn);         break;
            default: Log.w(TAG, "Unknown sub-panel: " + panelName);     break;
        }
    }

    public void dispose() {
        radarManager     = null;
        heatmapManager   = null;
        sigmetManager    = null;
        lightningManager = null;
        cbrnManager      = null;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void setText(int viewId, String text) {
        TextView tv = rootView.findViewById(viewId);
        if (tv != null) tv.setText(text);
    }

    /** Compact SeekBar listener — only onProgressChanged needs implementation. */
    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar sb) {}
        @Override public void onStopTrackingTouch(SeekBar sb) {}
    }
}
