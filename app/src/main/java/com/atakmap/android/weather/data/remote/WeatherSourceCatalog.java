package com.atakmap.android.weather.data.remote;

import android.content.Context;

import com.atakmap.android.weather.domain.model.ParameterDescriptor;
import com.atakmap.android.weather.domain.model.SourceDescriptor;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.repository.FetchCallback;
import com.atakmap.android.weather.domain.repository.SourceCatalog;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The one implementation of {@link SourceCatalog}.
 *
 * <p>Joins the two things that between them describe a source: the live
 * registry in {@link WeatherSourceManager}, which knows what can answer a
 * request, and the definition files read by {@link SourceDefinitionLoader},
 * which know what each one offers. Presentation used to do this join itself, in
 * five places, which is finding F22.</p>
 *
 * <p>Holds the host context so callers do not have to supply one per call. That
 * must be the host activity context — {@code SourceDefinitionLoader} reads from
 * the plugin's asset folder and the host's external files directory, and a
 * plugin context has no data directory.</p>
 */
public final class WeatherSourceCatalog implements SourceCatalog {

    private static final String TAG = "WeatherSourceCatalog";

    private final Context              context;
    private final WeatherSourceManager manager;

    public WeatherSourceCatalog(Context hostContext, WeatherSourceManager manager) {
        this.context = hostContext;
        this.manager = manager;
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    @Override
    public List<SourceDescriptor> sources() {
        Map<String, WeatherSourceDefinition> defs = definitions();
        String activeId = manager.getActiveSourceId();

        List<SourceDescriptor> out = new ArrayList<>();
        for (WeatherSourceManager.SourceEntry entry : manager.getAvailableEntries()) {
            IWeatherRemoteSource source = manager.getSourceById(entry.sourceId);
            if (source == null) continue;   // registered but not resolvable — skip quietly
            out.add(describe(source, defs.get(entry.sourceId),
                    entry.sourceId.equals(activeId)));
        }
        return Collections.unmodifiableList(out);
    }

    @Override
    public SourceDescriptor source(String sourceId) {
        if (sourceId == null) return null;
        IWeatherRemoteSource source = manager.getSourceById(sourceId);
        if (source == null) return null;
        return describe(source, definitions().get(sourceId),
                sourceId.equals(manager.getActiveSourceId()));
    }

    @Override
    public String activeSourceId() { return manager.getActiveSourceId(); }

    @Override
    public void setActiveSourceId(String sourceId) {
        if (sourceId == null || sourceId.isEmpty()) return;
        manager.setActiveSourceId(sourceId);
    }

    @Override
    public int indexOf(String sourceId) {
        if (sourceId == null) return 0;
        int i = manager.getIndexForSourceId(sourceId);
        return Math.max(0, i);
    }

    // ── Writing ──────────────────────────────────────────────────────────────

    @Override
    public void refresh() {
        SourceDefinitionLoader.clearCache();
        SourceDefinitionLoader.loadAll(context);
    }

    @Override
    public ImportOutcome importWeatherDefinition(File file) {
        return doImport(file, false);
    }

    @Override
    public ImportOutcome importRadarDefinition(File file) {
        return doImport(file, true);
    }

    @Override
    public void probe(String sourceId, double latitude, double longitude,
                      final ProbeCallback callback) {
        if (callback == null) return;

        IWeatherRemoteSource source = manager.getSourceById(sourceId);
        if (source == null) {
            callback.onUnreachable("No source registered as '" + sourceId + "'");
            return;
        }

        source.fetchCurrentWeather(latitude, longitude, new FetchCallback<WeatherModel>() {
            @Override
            public void onResult(WeatherModel data) {
                if (data == null) {
                    callback.onUnreachable("Empty response");
                    return;
                }
                // Name the provider that answered when it says so (F21/F30),
                // because a source under test is exactly where a substitution
                // would matter most.
                callback.onReachable(data.hasProvenance()
                        ? "served by " + data.getServedBy() : "responded");
            }

            @Override
            public void onError(String msg) {
                callback.onUnreachable(msg == null ? "Request failed" : msg);
            }
        });
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private Map<String, WeatherSourceDefinition> definitions() {
        try {
            return SourceDefinitionLoader.loadAll(context);
        } catch (Exception e) {
            // A malformed user file must not take the settings screen down with
            // it; the screen degrades to sources with no definition detail.
            Log.w(TAG, "Could not load source definitions", e);
            return Collections.emptyMap();
        }
    }

    private ImportOutcome doImport(File file, boolean radar) {
        if (file == null) return ImportOutcome.failed("No file selected");
        try {
            if (radar) SourceDefinitionLoader.importTileSourceFromFile(context, file);
            else       SourceDefinitionLoader.importFromFile(context, file);
            refresh();
            return ImportOutcome.ok("Imported " + file.getName());
        } catch (Exception e) {
            Log.w(TAG, "Import failed for " + file.getName(), e);
            return ImportOutcome.failed("Import failed: " + e.getMessage());
        }
    }

    private static SourceDescriptor describe(IWeatherRemoteSource source,
                                             WeatherSourceDefinition def,
                                             boolean active) {
        SourceDescriptor.Builder b = SourceDescriptor.builder(source.getSourceId())
                .displayName(source.getDisplayName())
                .active(active);

        if (def != null) {
            b.description(def.description)
             .apiBaseUrl(def.apiBaseUrl)
             .requiresApiKey(def.requiresApiKey)
             .hourly(convert(def.hourlyParams))
             .daily(convert(def.dailyParams))
             .current(convert(def.currentParams));
        }
        return b.build();
    }

    private static List<ParameterDescriptor> convert(
            List<WeatherSourceDefinition.ParamEntry> entries) {
        if (entries == null || entries.isEmpty()) return Collections.emptyList();
        List<ParameterDescriptor> out = new ArrayList<>(entries.size());
        for (WeatherSourceDefinition.ParamEntry e : entries) {
            out.add(new ParameterDescriptor(e.key, e.label, e.defaultOn));
        }
        return out;
    }
}
