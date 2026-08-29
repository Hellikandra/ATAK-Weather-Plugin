package com.atakmap.android.weather.presentation.view;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.weather.data.cache.MissionPrepManager;
import com.atakmap.android.weather.domain.model.RadarSourceDescriptor;
import com.atakmap.android.weather.domain.repository.ApiKeyStore;
import com.atakmap.android.weather.domain.repository.SourceCatalog;
import com.atakmap.android.weather.overlay.radar.RadarSourceSelector;
import com.atakmap.android.weather.plugin.R;
import com.atakmap.android.weather.util.AutoRefreshManager;
import com.atakmap.android.weather.util.WeatherUiUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Coordinator for the Settings (PARM) tab.
 *
 * <p>Extracted from {@code WeatherDropDownReceiver} (Sprint 21 — S21.2).
 * Manages: collapsible sections, source spinner, auto-refresh,
 * radar source list, mission prep, cache management, import buttons.</p>
 *
 * <p>Theme switcher removed — the plugin uses a single fixed dark palette.
 * Previously the theme system blanked the host MapView via
 * {@code rootView.getRootView()} — see issue #20.</p>
 */
public class SettingsCoordinator {

    private final View rootView;
    private final Context pluginContext;
    private final Context appContext;

    /**
     * Where API keys are read and written (finding F35). Injected from the
     * composition root; the screen never learns where keys actually live.
     */
    private ApiKeyStore apiKeyStore;

    /**
     * The source registry, behind its domain port (finding F22). Only used here
     * for importing definition files; the radar list goes through
     * {@link RadarSourceSelector}, which owns radar definitions.
     */
    private SourceCatalog sourceCatalog;
    private final MapView mapView;

    private AutoRefreshManager autoRefreshManager;
    private MissionPrepManager missionPrepManager;

    /** Callback for when the active weather source changes. */
    public interface SourceChangeListener {
        void onSourceChanged(String sourceId);
        void onParamsReloading();
    }

    private SourceChangeListener sourceChangeListener;

    public SettingsCoordinator(View rootView, Context pluginContext,
                                Context appContext, MapView mapView) {
        this.rootView      = rootView;
        this.pluginContext  = pluginContext;
        this.appContext     = appContext;
        this.mapView        = mapView;
    }

    public void setAutoRefreshManager(AutoRefreshManager mgr) {
        this.autoRefreshManager = mgr;
    }

    public void setMissionPrepManager(MissionPrepManager mgr) {
        this.missionPrepManager = mgr;
    }

    public void setSourceChangeListener(SourceChangeListener l) {
        this.sourceChangeListener = l;
    }

    /**
     * Initialize all Settings tab controls.
     * Call from DDR after all managers are created.
     */
    public void init() {
        initCollapsibleSections();
        wireParmAutoRefreshSpinner();
        wireParmMissionPrep();
        wireParmCacheManagement();
        wireImportButtons();
        wireParmRadarSourceList();
    }

    // ── Collapsible sections ──────────────────────────────────────────────

    private void initCollapsibleSections() {
        SharedPreferences sectionPrefs = appContext.getSharedPreferences(
                "weather_section_prefs", Context.MODE_PRIVATE);

        CollapsibleSection.setup(
                rootView.findViewById(R.id.settings_header_sources),
                rootView.findViewById(R.id.settings_content_sources),
                "settings_sources", sectionPrefs);
        CollapsibleSection.setup(
                rootView.findViewById(R.id.settings_header_auto_refresh),
                rootView.findViewById(R.id.settings_content_auto_refresh),
                "settings_auto_refresh", sectionPrefs);
        CollapsibleSection.setup(
                rootView.findViewById(R.id.settings_header_mission_prep),
                rootView.findViewById(R.id.settings_content_mission_prep),
                "settings_mission_prep", sectionPrefs);
        CollapsibleSection.setup(
                rootView.findViewById(R.id.settings_header_radar_sources),
                rootView.findViewById(R.id.settings_content_radar_sources),
                "settings_radar_sources", sectionPrefs);
    }

    // ── Auto-Refresh ──────────────────────────────────────────────────────

    private void wireParmAutoRefreshSpinner() {
        Spinner spinner = rootView.findViewById(R.id.spinner_auto_refresh);
        if (spinner == null) return;

        List<String> labels = Arrays.asList(
                "Off", "5 min", "10 min", "15 min", "30 min", "60 min");
        spinner.setAdapter(WeatherUiUtils.makeDarkSpinnerAdapter(pluginContext, labels));
        WeatherUiUtils.styleSpinnerDark(spinner);

        // Restore persisted interval
        if (autoRefreshManager != null) {
            int mins = autoRefreshManager.getInterval();
            int idx = 0;
            int[] vals = {0, 5, 10, 15, 30, 60};
            for (int i = 0; i < vals.length; i++) {
                if (vals[i] == mins) { idx = i; break; }
            }
            spinner.setSelection(idx, false);
        }

        final int[] intervals = {0, 5, 10, 15, 30, 60};
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                if (autoRefreshManager != null && pos < intervals.length) {
                    autoRefreshManager.setInterval(intervals[pos]);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    // ── Mission Prep ──────────────────────────────────────────────────────

    private void wireParmMissionPrep() {
        Button btnMissionPrep = rootView.findViewById(R.id.btn_mission_prep);
        if (btnMissionPrep == null || missionPrepManager == null) return;

        final ProgressBar progress = rootView.findViewById(R.id.mission_prep_progress);
        final TextView statusText  = rootView.findViewById(R.id.mission_prep_status);

        btnMissionPrep.setOnClickListener(v -> {
            com.atakmap.coremap.maps.coords.GeoBounds bounds = mapView.getBounds();
            if (bounds == null) return;
            double north = bounds.getNorth(), south = bounds.getSouth();
            double east = bounds.getEast(), west = bounds.getWest();

            if (progress != null) { progress.setProgress(0); progress.setVisibility(View.VISIBLE); }
            if (statusText != null) { statusText.setText("Downloading..."); statusText.setVisibility(View.VISIBLE); }

            missionPrepManager.downloadArea(north, south, east, west, 48,
                    new MissionPrepManager.ProgressCallback() {
                        @Override public void onProgress(int current, int total, String status) {
                            mapView.post(() -> {
                                if (progress != null && total > 0)
                                    progress.setProgress(current * 100 / total);
                                if (statusText != null) statusText.setText(status);
                            });
                        }
                        @Override public void onComplete(int itemsDownloaded) {
                            mapView.post(() -> {
                                if (progress != null) progress.setVisibility(View.GONE);
                                if (statusText != null)
                                    statusText.setText("Downloaded " + itemsDownloaded + " items");
                                updateCacheInfo();
                            });
                        }
                        @Override public void onError(String error) {
                            mapView.post(() -> {
                                if (progress != null) progress.setVisibility(View.GONE);
                                if (statusText != null) statusText.setText("Error: " + error);
                            });
                        }
                    });
        });
    }

    // ── Cache management ──────────────────────────────────────────────────

    private void wireParmCacheManagement() {
        updateCacheInfo();
    }

    public void updateCacheInfo() {
        TextView cacheInfo = rootView.findViewById(R.id.cache_info_text);
        if (cacheInfo == null || missionPrepManager == null) return;
        MissionPrepManager.OfflineStatus status = missionPrepManager.getOfflineStatus();
        cacheInfo.setText(pluginContext.getString(R.string.cache_info,
                MissionPrepManager.formatBytes(status.cacheSizeBytes)));
    }

    // ── Import buttons ────────────────────────────────────────────────────

    private void wireImportButtons() {
        // Import Radar Source
        Button btnImportTile = rootView.findViewById(R.id.btn_import_tile_source);
        if (btnImportTile != null) {
            btnImportTile.setOnClickListener(v -> {
                java.io.File startDir = new java.io.File(
                        android.os.Environment.getExternalStorageDirectory(),
                        "atak/tools/weather_tiles");
                if (!startDir.exists()) startDir.mkdirs();

                com.atakmap.android.gui.ImportFileBrowserDialog.show(
                        "Import Radar Source",
                        startDir.getAbsolutePath(),
                        new String[] { "json", "xml" },
                        new com.atakmap.android.gui.ImportFileBrowserDialog.DialogDismissed() {
                            @Override public void onFileSelected(java.io.File f) {
                                if (f == null || sourceCatalog == null) return;
                                SourceCatalog.ImportOutcome outcome =
                                        sourceCatalog.importRadarDefinition(f);
                                Toast.makeText(pluginContext, outcome.message(),
                                        Toast.LENGTH_SHORT).show();
                            }
                            @Override public void onDialogClosed() {}
                        },
                        mapView.getContext()
                );
            });
        }
    }

    /** Inject the API key store. Call before {@code init()} for the key rows to appear. */
    public void setApiKeyStore(ApiKeyStore store) { this.apiKeyStore = store; }

    /** Inject the source catalog. Call before {@code init()}. */
    public void setSourceCatalog(SourceCatalog catalog) { this.sourceCatalog = catalog; }

    // ── Radar source list ─────────────────────────────────────────────────

    private void wireParmRadarSourceList() {
        LinearLayout radarList = rootView.findViewById(R.id.radar_source_list);
        if (radarList == null) return;

        RadarSourceSelector selector = new RadarSourceSelector(appContext);
        selector.loadSources();
        List<RadarSourceDescriptor> sources = selector.getAvailableDescriptors();

        populateRadarSourceList(radarList, sources, selector);

        // Scan Folder button
        Button btnScan = rootView.findViewById(R.id.btn_scan_radar_folder);
        if (btnScan != null) {
            btnScan.setOnClickListener(v -> {
                List<RadarSourceDescriptor> refreshed = selector.refreshDescriptors();
                populateRadarSourceList(radarList, refreshed, selector);
                Toast.makeText(pluginContext,
                        "Radar sources: " + refreshed.size() + " found",
                        Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void populateRadarSourceList(
            LinearLayout container,
            List<RadarSourceDescriptor> sources,
            RadarSourceSelector selector) {
        container.removeAllViews();

        if (sources == null || sources.isEmpty()) {
            TextView empty = new TextView(pluginContext);
            empty.setText("No radar sources found");
            empty.setTextSize(11);
            empty.setTextColor(0xFF8b949e);
            empty.setPadding(0, 16, 0, 16);
            container.addView(empty);
            return;
        }

        int activeIdx = selector.getActiveSourceIndex();
        float dp = pluginContext.getResources().getDisplayMetrics().density;

        for (int i = 0; i < sources.size(); i++) {
            RadarSourceDescriptor def = sources.get(i);

            final String srcId    = def.id();
            final String provider = def.provider();

            LinearLayout row = new LinearLayout(pluginContext);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding((int)(4*dp), (int)(6*dp), (int)(4*dp), (int)(6*dp));

            android.widget.Switch toggle = new android.widget.Switch(mapView.getContext());
            toggle.setChecked(i == activeIdx);
            toggle.setTextSize(11);
            toggle.setText("");

            TextView label = new TextView(pluginContext);
            String name = def.displayName();
            label.setText(name);
            label.setTextSize(12);
            label.setTextColor(0xFFc9d1d9);
            label.setPadding((int)(8*dp), 0, 0, 0);
            label.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView info = new TextView(pluginContext);
            info.setText(provider);
            info.setTextSize(9);
            info.setTextColor(0xFF8b949e);
            info.setPadding((int)(4*dp), 0, 0, 0);

            row.addView(toggle);
            row.addView(label);
            row.addView(info);

            // ── API key row (finding F35) ──────────────────────────────
            //
            // Keyed radar sources shipped with no way to supply a key: the
            // list showed a name and a switch and nothing else, so the bundled
            // OpenWeatherMap source could be selected but never made to work.
            LinearLayout keyRow = null;
            if (def.requiresApiKey() && apiKeyStore != null) {
                keyRow = buildRadarKeyRow(srcId, label, name, dp);
            }

            toggle.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) {
                    selector.setActiveSourceId(srcId);
                    for (int c = 0; c < container.getChildCount(); c++) {
                        View child = container.getChildAt(c);
                        if (child instanceof LinearLayout) {
                            View first = ((LinearLayout) child).getChildAt(0);
                            if (first instanceof android.widget.Switch && first != btn) {
                                ((android.widget.Switch) first).setChecked(false);
                            }
                        }
                    }
                    Toast.makeText(pluginContext,
                            "Active radar: " + name, Toast.LENGTH_SHORT).show();
                }
            });

            container.addView(row);
            if (keyRow != null) container.addView(keyRow);
        }
    }

    /**
     * Build the "API key" line shown under a radar source that needs one.
     *
     * <p>Writes through {@link AuthProvider}, the same store
     * {@code RadarOverlayManager} reads at request time — the mismatch between
     * where keys were written and where they were read is half of finding
     * F35.</p>
     */
    private LinearLayout buildRadarKeyRow(final String sourceId,
                                          final TextView label, final String name,
                                          float dp) {
        LinearLayout keyRow = new LinearLayout(pluginContext);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keyRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        keyRow.setPadding((int) (36 * dp), 0, (int) (4 * dp), (int) (6 * dp));

        final EditText etKey = new EditText(pluginContext);
        etKey.setHint("API key");
        etKey.setTextSize(11);
        etKey.setSingleLine(true);
        etKey.setTextColor(0xFFc9d1d9);
        etKey.setHintTextColor(0xFF6e7681);
        etKey.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        String existing = apiKeyStore.get(sourceId);
        if (existing != null && !existing.isEmpty()) etKey.setText(existing);

        Button btnSave = new Button(pluginContext);
        btnSave.setText("Save");
        btnSave.setTextSize(10);
        btnSave.setOnClickListener(v -> {
            String key = etKey.getText().toString().trim();
            if (key.isEmpty()) {
                apiKeyStore.remove(sourceId);
                Toast.makeText(pluginContext, name + ": key cleared",
                        Toast.LENGTH_SHORT).show();
            } else {
                apiKeyStore.put(sourceId, key);
                Toast.makeText(pluginContext, name + ": key saved",
                        Toast.LENGTH_SHORT).show();
            }
            markRadarKeyState(label, name, sourceId);
        });

        keyRow.addView(etKey);
        keyRow.addView(btnSave);

        markRadarKeyState(label, name, sourceId);
        return keyRow;
    }

    /**
     * Flag a keyed source that has no key, so the list says why it will not
     * draw anything rather than leaving the user to guess.
     */
    private void markRadarKeyState(TextView label, String name, String sourceId) {
        boolean ok = apiKeyStore.has(sourceId);
        label.setText(ok ? name : name + "  — key required");
        label.setTextColor(ok ? 0xFFc9d1d9 : 0xFFd29922);
    }
}
