package com.atakmap.android.weather.presentation.view;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.weather.data.remote.IWeatherRemoteSource;
import com.atakmap.android.weather.data.remote.SourceDefinitionLoader;
import com.atakmap.android.weather.data.remote.WeatherSourceDefinition;
import com.atakmap.android.weather.data.remote.WeatherSourceManager;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.plugin.R;
import com.atakmap.coremap.log.Log;

import java.util.List;
import java.util.Map;

/**
 * SourceManagerView — Sprint 8.5: Source Management UI.
 *
 * View helper that manages the source list UI within the PARM tab.
 * Lists all registered {@link IWeatherRemoteSource} providers, lets
 * the user activate / test them, manage API keys, and scan for
 * external JSON definitions.
 *
 * Follows the same pattern as {@link ParametersView}: receives the
 * root view + plugin context in the constructor, then {@link #init()}
 * is called once from {@code WeatherDropDownReceiver}.
 */
public class SourceManagerView {

    private static final String TAG = "SourceManagerView";

    /** SharedPreferences file for API key storage (same as WeatherSourceManager). */
    private static final String PREFS_NAME = "WeatherToolPrefs";
    /** Prefix for per-source API key entries. */
    private static final String KEY_API_PREFIX = "wx_api_key_";

    /** Test location: Liege, Belgium (50.6, 5.5). */
    private static final double TEST_LAT = 50.6;
    private static final double TEST_LON = 5.5;

    // Colours — match the project's dark theme palette.
    private static final int COLOR_ACTIVE_BG   = 0xFF1a2233;  // slightly blue-tinted dark
    private static final int COLOR_INACTIVE_BG  = 0xFF161b22;
    private static final int COLOR_DOT_ACTIVE   = 0xFF3fb950;  // green
    private static final int COLOR_DOT_INACTIVE = 0xFF8b949e;  // gray
    private static final int COLOR_DOT_ERROR    = 0xFFf85149;  // red
    private static final int COLOR_TEXT_SUCCESS  = 0xFF3fb950;
    private static final int COLOR_TEXT_FAIL     = 0xFFf85149;

    private final View rootView;
    private final Context context;
    private final LinearLayout sourceListContainer;
    private final TextView emptyLabel;
    private final WeatherSourceManager sourceManager;
    private final SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Constructor ─────────────────────────────────────────────────────────────

    public SourceManagerView(View rootView, Context context) {
        this.rootView   = rootView;
        this.context    = context;
        this.sourceManager = WeatherSourceManager.getInstance(context);
        this.prefs      = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        sourceListContainer = rootView.findViewById(R.id.source_list_container);
        emptyLabel          = rootView.findViewById(R.id.src_mgr_empty_label);
    }

    // ── Public API ──────────────────────────────────────────────────────────────

    /**
     * Initialize: populate source list, wire header buttons.
     * Call once from WeatherDropDownReceiver after inflating the layout.
     */
    public void init() {
        // Header refresh button
        Button btnRefresh = rootView.findViewById(R.id.btn_src_mgr_refresh);
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> {
                SourceDefinitionLoader.clearCache();
                SourceDefinitionLoader.loadAll(context);
                refreshSourceList();
                Toast.makeText(context, R.string.src_mgr_refresh, Toast.LENGTH_SHORT).show();
            });
        }

        // Bottom action buttons
        Button btnAdd = rootView.findViewById(R.id.btn_src_mgr_add);
        if (btnAdd != null) {
            btnAdd.setOnClickListener(v -> showAddSourceDialog());
        }

        Button btnScan = rootView.findViewById(R.id.btn_src_mgr_scan);
        if (btnScan != null) {
            btnScan.setOnClickListener(v -> scanExternalFolder());
        }

        refreshSourceList();
    }

    /**
     * Refresh the source list (called after scan/add or externally).
     * Clears the container and rebuilds from the current registry.
     */
    public void refreshSourceList() {
        if (sourceListContainer == null) return;
        sourceListContainer.removeAllViews();

        List<WeatherSourceManager.SourceEntry> entries = sourceManager.getAvailableEntries();
        Map<String, WeatherSourceDefinition> allDefs = SourceDefinitionLoader.loadAll(context);
        String activeId = sourceManager.getActiveSourceId();

        if (entries.isEmpty()) {
            if (emptyLabel != null) emptyLabel.setVisibility(View.VISIBLE);
            return;
        }
        if (emptyLabel != null) emptyLabel.setVisibility(View.GONE);

        for (WeatherSourceManager.SourceEntry entry : entries) {
            IWeatherRemoteSource source = sourceManager.getSourceById(entry.sourceId);
            WeatherSourceDefinition def = allDefs.get(entry.sourceId);
            if (source == null) continue;

            View itemView = createSourceEntry(source, def, entry.sourceId.equals(activeId));
            sourceListContainer.addView(itemView);
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────────────

    /**
     * Create a single source entry view from item_source_entry.xml.
     */
    private View createSourceEntry(IWeatherRemoteSource source,
                                   WeatherSourceDefinition def,
                                   boolean isActive) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View item = inflater.inflate(R.layout.item_source_entry, sourceListContainer, false);

        String sourceId = source.getSourceId();

        // ── Background highlight for active source ──────────────────────
        item.setBackgroundColor(isActive ? COLOR_ACTIVE_BG : COLOR_INACTIVE_BG);

        // ── Status dot ──────────────────────────────────────────────────
        View dot = item.findViewById(R.id.status_dot);
        if (dot != null) {
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(isActive ? COLOR_DOT_ACTIVE : COLOR_DOT_INACTIVE);
            circle.setSize(10, 10);
            dot.setBackground(circle);
        }

        // ── Name and description ────────────────────────────────────────
        TextView tvName = item.findViewById(R.id.source_name);
        if (tvName != null) {
            String label = source.getDisplayName();
            if (isActive) label += "  [" + context.getString(R.string.src_mgr_active) + "]";
            tvName.setText(label);
        }

        TextView tvDesc = item.findViewById(R.id.source_description);
        if (tvDesc != null && def != null && def.description != null && !def.description.isEmpty()) {
            tvDesc.setText(def.description);
            tvDesc.setVisibility(View.VISIBLE);
        }

        // ── Metadata row ────────────────────────────────────────────────
        TextView tvMeta = item.findViewById(R.id.source_metadata);
        if (tvMeta != null && def != null) {
            String meta = buildMetadataLine(def);
            if (!meta.isEmpty()) {
                tvMeta.setText(meta);
                tvMeta.setVisibility(View.VISIBLE);
            }
        }

        // ── Toggle (radio-style: activates this source) ─────────────────
        Switch toggle = item.findViewById(R.id.source_toggle);
        if (toggle != null) {
            toggle.setChecked(isActive);
            toggle.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) {
                    sourceManager.setActiveSourceId(sourceId);
                    refreshSourceList();
                } else {
                    // Don't allow turning off the active source without selecting another
                    if (sourceId.equals(sourceManager.getActiveSourceId())) {
                        toggle.setChecked(true);
                    }
                }
            });
        }

        // ── Tap item to set as active ───────────────────────────────────
        item.setOnClickListener(v -> {
            sourceManager.setActiveSourceId(sourceId);
            refreshSourceList();
        });

        // ── API key row ─────────────────────────────────────────────────
        LinearLayout apiRow = item.findViewById(R.id.api_key_row);
        if (apiRow != null && def != null && def.requiresApiKey) {
            apiRow.setVisibility(View.VISIBLE);
            EditText etKey = item.findViewById(R.id.api_key_input);
            Button btnSave = item.findViewById(R.id.btn_save_key);

            // Pre-fill saved key (masked)
            String savedKey = prefs.getString(KEY_API_PREFIX + sourceId, "");
            if (etKey != null && !savedKey.isEmpty()) {
                etKey.setText(savedKey);
            }

            if (btnSave != null) {
                btnSave.setOnClickListener(v -> {
                    if (etKey == null) return;
                    String key = etKey.getText().toString().trim();
                    prefs.edit().putString(KEY_API_PREFIX + sourceId, key).apply();
                    Toast.makeText(context, "API key saved", Toast.LENGTH_SHORT).show();
                });
            }
        }

        // ── Test button ─────────────────────────────────────────────────
        Button btnTest = item.findViewById(R.id.btn_test_source);
        TextView tvResult = item.findViewById(R.id.test_result);
        if (btnTest != null) {
            btnTest.setOnClickListener(v -> testSource(source, tvResult, dot));
        }

        // ── Info button ─────────────────────────────────────────────────
        Button btnInfo = item.findViewById(R.id.btn_source_info);
        if (btnInfo != null) {
            btnInfo.setOnClickListener(v -> showSourceInfo(source, def));
        }

        return item;
    }

    /**
     * Build a compact metadata string from the definition.
     * e.g. "Hourly: 28 params | Daily: 14 params | Current: 12 params"
     */
    private String buildMetadataLine(WeatherSourceDefinition def) {
        StringBuilder sb = new StringBuilder();
        if (def.hourlyParams != null && !def.hourlyParams.isEmpty()) {
            sb.append("Hourly: ").append(def.hourlyParams.size()).append(" params");
        }
        if (def.dailyParams != null && !def.dailyParams.isEmpty()) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append("Daily: ").append(def.dailyParams.size()).append(" params");
        }
        if (def.currentParams != null && !def.currentParams.isEmpty()) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append("Current: ").append(def.currentParams.size()).append(" params");
        }
        if (def.requiresApiKey) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append("API key required");
        }
        return sb.toString();
    }

    /**
     * Test a source by calling fetchCurrentWeather with a known location.
     */
    private void testSource(IWeatherRemoteSource source, TextView resultView, View dot) {
        if (resultView != null) {
            resultView.setText(R.string.src_mgr_test_running);
            resultView.setTextColor(0xFF8b949e);
            resultView.setVisibility(View.VISIBLE);
        }

        try {
            source.fetchCurrentWeather(TEST_LAT, TEST_LON,
                new IWeatherRemoteSource.FetchCallback<WeatherModel>() {
                    @Override
                    public void onResult(WeatherModel data) {
                        mainHandler.post(() -> {
                            if (resultView != null) {
                                resultView.setText(R.string.src_mgr_test_success);
                                resultView.setTextColor(COLOR_TEXT_SUCCESS);
                            }
                            if (dot != null) {
                                GradientDrawable circle = new GradientDrawable();
                                circle.setShape(GradientDrawable.OVAL);
                                circle.setColor(COLOR_DOT_ACTIVE);
                                circle.setSize(10, 10);
                                dot.setBackground(circle);
                            }
                        });
                    }

                    @Override
                    public void onError(String message) {
                        mainHandler.post(() -> {
                            if (resultView != null) {
                                resultView.setText(context.getString(R.string.src_mgr_test_fail)
                                        + " " + message);
                                resultView.setTextColor(COLOR_TEXT_FAIL);
                            }
                            if (dot != null) {
                                GradientDrawable circle = new GradientDrawable();
                                circle.setShape(GradientDrawable.OVAL);
                                circle.setColor(COLOR_DOT_ERROR);
                                circle.setSize(10, 10);
                                dot.setBackground(circle);
                            }
                        });
                    }
                });
        } catch (Exception e) {
            Log.w(TAG, "testSource failed: " + e.getMessage());
            mainHandler.post(() -> {
                if (resultView != null) {
                    resultView.setText(context.getString(R.string.src_mgr_test_fail)
                            + " " + e.getMessage());
                    resultView.setTextColor(COLOR_TEXT_FAIL);
                }
            });
        }
    }

    /**
     * Scan /sdcard/atak/tools/weather_sources/ for new definitions.
     */
    private void scanExternalFolder() {
        SourceDefinitionLoader.clearCache();
        Map<String, WeatherSourceDefinition> allDefs = SourceDefinitionLoader.loadAll(context);
        refreshSourceList();
        Toast.makeText(context,
                context.getString(R.string.sources_refreshed, allDefs.size()),
                Toast.LENGTH_SHORT).show();
    }

    /**
     * Open ATAK's native file browser to select and import a weather source file.
     * Supports JSON and XML definitions. Files are copied to atak/tools/weather_sources/.
     */
    private void showAddSourceDialog() {
        java.io.File startDir = new java.io.File(
                android.os.Environment.getExternalStorageDirectory(),
                "atak/tools/weather_sources");
        if (!startDir.exists()) startDir.mkdirs();

        com.atakmap.android.gui.ImportFileBrowserDialog.show(
                "Import Weather Source (JSON / XML)",
                startDir.getAbsolutePath(),
                new String[] { "json", "xml" },
                new com.atakmap.android.gui.ImportFileBrowserDialog.DialogDismissed() {
                    @Override public void onFileSelected(java.io.File f) {
                        if (f == null) return;
                        try {
                            SourceDefinitionLoader.importFromFile(context, f);
                            SourceDefinitionLoader.clearCache();
                            refreshSourceList();
                            android.widget.Toast.makeText(context,
                                    "Imported: " + f.getName(),
                                    android.widget.Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            android.widget.Toast.makeText(context,
                                    "Import failed: " + e.getMessage(),
                                    android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }
                    @Override public void onDialogClosed() { /* no-op */ }
                },
                com.atakmap.android.maps.MapView.getMapView().getContext()
        );
    }

    /**
     * Show source info dialog with all available details.
     */
    private void showSourceInfo(IWeatherRemoteSource source, WeatherSourceDefinition def) {
        StringBuilder info = new StringBuilder();
        info.append("Source ID: ").append(source.getSourceId()).append("\n");
        info.append("Display Name: ").append(source.getDisplayName()).append("\n");

        if (def != null) {
            if (def.apiBaseUrl != null && !def.apiBaseUrl.isEmpty()) {
                info.append("API Base URL: ").append(def.apiBaseUrl).append("\n");
            }
            if (def.description != null && !def.description.isEmpty()) {
                info.append("\nDescription:\n").append(def.description).append("\n");
            }
            info.append("\nRequires API Key: ").append(def.requiresApiKey ? "Yes" : "No").append("\n");

            if (def.hourlyParams != null) {
                info.append("Hourly params: ").append(def.hourlyParams.size()).append("\n");
            }
            if (def.dailyParams != null) {
                info.append("Daily params: ").append(def.dailyParams.size()).append("\n");
            }
            if (def.currentParams != null) {
                info.append("Current params: ").append(def.currentParams.size()).append("\n");
            }
        }

        // Schema version hint
        String schemaLabel = (def != null && def.hourlyParams != null && !def.hourlyParams.isEmpty())
                ? context.getString(R.string.src_mgr_schema_v2)
                : context.getString(R.string.src_mgr_schema_v1);
        info.append("\nSchema: ").append(schemaLabel);

        new AlertDialog.Builder(com.atakmap.android.maps.MapView.getMapView().getContext())
                .setTitle(source.getDisplayName())
                .setMessage(info.toString())
                .setPositiveButton("OK", null)
                .show();
    }
}
