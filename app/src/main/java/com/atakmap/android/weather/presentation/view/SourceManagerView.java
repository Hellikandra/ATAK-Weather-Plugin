package com.atakmap.android.weather.presentation.view;

import android.app.AlertDialog;
import android.content.Context;
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

import com.atakmap.android.weather.domain.model.SourceDescriptor;
import com.atakmap.android.weather.domain.repository.ApiKeyStore;
import com.atakmap.android.weather.domain.repository.SourceCatalog;
import com.atakmap.android.weather.plugin.R;

import java.util.List;

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
    /** Plugin APK context — used for LayoutInflater + string/drawable resources. */
    private final Context context;
    /** Host activity context — used for SharedPreferences (plugin context has no data dir). */
    private final Context appContext;
    private final LinearLayout sourceListContainer;
    private final TextView emptyLabel;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Where API keys are read and written (finding F35). This screen used to keep
     * its own preferences file and key prefix, which is why a key typed here was
     * never seen by the code that makes the requests.
     */
    private ApiKeyStore apiKeyStore;

    /**
     * What sources exist, which is active, and what each offers (finding F22).
     *
     * <p>This screen used to hold {@code WeatherSourceManager} and call
     * {@code SourceDefinitionLoader.loadAll} itself, then render a mixture of
     * fields from a live source and a parsed definition -- 55 of the 98
     * presentation-to-data.remote dependencies came from this one file. The
     * catalog performs that join in the layer that owns it and hands back a
     * single {@link SourceDescriptor} per source.</p>
     */
    private SourceCatalog sourceCatalog;

    // ── Constructor ─────────────────────────────────────────────────────────────

    /**
     * @param rootView      pre-inflated container view
     * @param pluginContext plugin APK context — used for inflate/string resources
     * @param appContext    host activity context — used for SharedPreferences
     */
    public SourceManagerView(View rootView, Context pluginContext, Context appContext) {
        this.rootView    = rootView;
        this.context     = pluginContext;   // resources live here
        this.appContext  = appContext;      // disk-backed prefs live here

        sourceListContainer = rootView.findViewById(R.id.source_list_container);
        emptyLabel          = rootView.findViewById(R.id.src_mgr_empty_label);
    }

    /** Inject the source catalog. Required -- {@link #init()} does nothing without it. */
    public void setSourceCatalog(SourceCatalog catalog) { this.sourceCatalog = catalog; }

    /** Inject the API key store. Call before {@link #init()}. */
    public void setApiKeyStore(ApiKeyStore store) { this.apiKeyStore = store; }

    /**
     * Backward-compat constructor — kept so existing callers compile.
     * Uses pluginContext for both resources AND prefs (will fail with mkdir
     * ENOENT for prefs — see issue #18). New callers should pass both.
     *
     * @deprecated use {@link #SourceManagerView(View, Context, Context)} instead.
     */
    @Deprecated
    public SourceManagerView(View rootView, Context pluginContext) {
        this(rootView, pluginContext, pluginContext);
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
                if (sourceCatalog != null) sourceCatalog.refresh();
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

        List<SourceDescriptor> sources = sourceCatalog == null
                ? java.util.Collections.<SourceDescriptor>emptyList()
                : sourceCatalog.sources();

        if (sources.isEmpty()) {
            if (emptyLabel != null) emptyLabel.setVisibility(View.VISIBLE);
            return;
        }
        if (emptyLabel != null) emptyLabel.setVisibility(View.GONE);

        for (SourceDescriptor source : sources) {
            sourceListContainer.addView(createSourceEntry(source));
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────────────

    /**
     * Create a single source entry view from item_source_entry.xml.
     */
    private View createSourceEntry(final SourceDescriptor source) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View item = inflater.inflate(R.layout.item_source_entry, sourceListContainer, false);

        final String sourceId = source.id();
        final boolean isActive = source.active();

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
            String label = source.displayName();
            if (isActive) label += "  [" + context.getString(R.string.src_mgr_active) + "]";
            tvName.setText(label);
        }

        TextView tvDesc = item.findViewById(R.id.source_description);
        if (tvDesc != null && !source.description().isEmpty()) {
            tvDesc.setText(source.description());
            tvDesc.setVisibility(View.VISIBLE);
        }

        // ── Metadata row ────────────────────────────────────────────────
        TextView tvMeta = item.findViewById(R.id.source_metadata);
        if (tvMeta != null && source.hasDefinition()) {
            String meta = buildMetadataLine(source);
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
                    setActive(sourceId);
                } else if (isActive) {
                    // Don't allow turning off the active source without selecting another
                    toggle.setChecked(true);
                }
            });
        }

        // ── Tap item to set as active ───────────────────────────────────
        item.setOnClickListener(v -> setActive(sourceId));

        // ── API key row ─────────────────────────────────────────────────
        LinearLayout apiRow = item.findViewById(R.id.api_key_row);
        if (apiRow != null && source.requiresApiKey()) {
            apiRow.setVisibility(View.VISIBLE);
            EditText etKey = item.findViewById(R.id.api_key_input);
            Button btnSave = item.findViewById(R.id.btn_save_key);

            // Read and write through AuthProvider — the one place that resolves
            // keys at request time. This screen used to keep its own prefs file
            // and its own key prefix, so a key typed here was stored where
            // nothing looked for it (finding F35).
            String savedKey = apiKeyStore != null ? apiKeyStore.get(sourceId) : null;
            if (etKey != null && savedKey != null && !savedKey.isEmpty()) {
                etKey.setText(savedKey);
            }

            if (btnSave != null) {
                btnSave.setOnClickListener(v -> {
                    if (etKey == null || apiKeyStore == null) return;
                    String key = etKey.getText().toString().trim();
                    if (key.isEmpty()) {
                        apiKeyStore.remove(sourceId);
                        Toast.makeText(context, "API key cleared", Toast.LENGTH_SHORT).show();
                    } else {
                        apiKeyStore.put(sourceId, key);
                        Toast.makeText(context, "API key saved", Toast.LENGTH_SHORT).show();
                    }
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
            btnInfo.setOnClickListener(v -> showSourceInfo(source));
        }

        return item;
    }

    /**
     * Build a compact metadata string from the definition.
     * e.g. "Hourly: 28 params | Daily: 14 params | Current: 12 params"
     */
    private String buildMetadataLine(SourceDescriptor source) {
        StringBuilder sb = new StringBuilder();
        appendCount(sb, "Hourly", source.hourlyParameters().size());
        appendCount(sb, "Daily", source.dailyParameters().size());
        appendCount(sb, "Current", source.currentParameters().size());
        if (source.requiresApiKey()) {
            appendSeparator(sb);
            sb.append("API key required");
        }
        return sb.toString();
    }

    private static void appendCount(StringBuilder sb, String label, int count) {
        if (count == 0) return;
        appendSeparator(sb);
        sb.append(label).append(": ").append(count).append(" params");
    }

    private static void appendSeparator(StringBuilder sb) {
        if (sb.length() > 0) sb.append(" | ");
    }

    /** Activate a source and redraw, so the highlight and toggles stay in step. */
    private void setActive(String sourceId) {
        if (sourceCatalog == null) return;
        sourceCatalog.setActiveSourceId(sourceId);
        refreshSourceList();
    }

    /**
     * Test a source by calling fetchCurrentWeather with a known location.
     */
    /**
     * Ask one source for current conditions, to prove it answers.
     *
     * <p>Routed through {@link SourceCatalog#probe} rather than the repository:
     * the point is to exercise <em>this</em> source rather than whichever is
     * active, and to bypass the cache. The callback arrives on whatever thread
     * the fetch completed on, so every view touch is posted to the main one.</p>
     */
    private void testSource(SourceDescriptor source, TextView resultView, View dot) {
        if (resultView != null) {
            resultView.setText(R.string.src_mgr_test_running);
            resultView.setTextColor(0xFF8b949e);
            resultView.setVisibility(View.VISIBLE);
        }
        if (sourceCatalog == null) return;

        sourceCatalog.probe(source.id(), TEST_LAT, TEST_LON, new SourceCatalog.ProbeCallback() {
            @Override
            public void onReachable(String summary) {
                mainHandler.post(() -> {
                    if (resultView != null) {
                        resultView.setText(context.getString(R.string.src_mgr_test_success)
                                + (summary == null || summary.isEmpty() ? "" : " \u2014 " + summary));
                        resultView.setTextColor(COLOR_TEXT_SUCCESS);
                    }
                    paintDot(dot, COLOR_DOT_ACTIVE);
                });
            }

            @Override
            public void onUnreachable(String message) {
                mainHandler.post(() -> {
                    if (resultView != null) {
                        resultView.setText(context.getString(R.string.src_mgr_test_fail)
                                + " " + message);
                        resultView.setTextColor(COLOR_TEXT_FAIL);
                    }
                    paintDot(dot, COLOR_DOT_ERROR);
                });
            }
        });
    }

    /** The status dot is drawn in three places; draw it in one. */
    private static void paintDot(View dot, int colour) {
        if (dot == null) return;
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(colour);
        circle.setSize(10, 10);
        dot.setBackground(circle);
    }

    /**
     * Scan /sdcard/atak/tools/weather_sources/ for new definitions.
     */
    private void scanExternalFolder() {
        if (sourceCatalog == null) return;
        sourceCatalog.refresh();
        refreshSourceList();
        Toast.makeText(context,
                context.getString(R.string.sources_refreshed, sourceCatalog.sources().size()),
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
                        if (f == null || sourceCatalog == null) return;
                        SourceCatalog.ImportOutcome outcome =
                                sourceCatalog.importWeatherDefinition(f);
                        if (outcome.succeeded()) refreshSourceList();
                        android.widget.Toast.makeText(context, outcome.message(),
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                    @Override public void onDialogClosed() { /* no-op */ }
                },
                com.atakmap.android.maps.MapView.getMapView().getContext()
        );
    }

    /**
     * Show source info dialog with all available details.
     */
    private void showSourceInfo(SourceDescriptor source) {
        StringBuilder info = new StringBuilder();
        info.append("Source ID: ").append(source.id()).append("\n");
        info.append("Display Name: ").append(source.displayName()).append("\n");

        if (!source.apiBaseUrl().isEmpty()) {
            info.append("API Base URL: ").append(source.apiBaseUrl()).append("\n");
        }
        if (!source.description().isEmpty()) {
            info.append("\nDescription:\n").append(source.description()).append("\n");
        }
        info.append("\nRequires API Key: ")
            .append(source.requiresApiKey() ? "Yes" : "No").append("\n");
        info.append("Hourly params: ").append(source.hourlyParameters().size()).append("\n");
        info.append("Daily params: ").append(source.dailyParameters().size()).append("\n");
        info.append("Current params: ").append(source.currentParameters().size()).append("\n");

        // A source with hourly parameters came from a v2 definition; one without
        // is either v1 or a built-in Java source with no definition behind it.
        String schemaLabel = source.hourlyParameters().isEmpty()
                ? context.getString(R.string.src_mgr_schema_v1)
                : context.getString(R.string.src_mgr_schema_v2);
        info.append("\nSchema: ").append(schemaLabel);

        new AlertDialog.Builder(com.atakmap.android.maps.MapView.getMapView().getContext())
                .setTitle(source.displayName())
                .setMessage(info.toString())
                .setPositiveButton("OK", null)
                .show();
    }
}
