package com.atakmap.android.weather.domain.repository;

import com.atakmap.android.weather.domain.model.SourceDescriptor;

import java.io.File;
import java.util.List;

/**
 * The set of weather sources the plugin knows about, and which one is active.
 *
 * <p>This is the port finding F22 is about. The settings screens, the source
 * manager, the parameter list and two tab coordinators all needed to answer
 * questions like "what sources are there", "which is selected" and "what
 * parameters does this one offer". With no interface to ask, each of them
 * reached directly for {@code WeatherSourceManager.getInstance(context)} and
 * {@code SourceDefinitionLoader.loadAll(context)} — 98 dependencies from
 * presentation into {@code data.remote}, and five screens that could not be
 * reasoned about without knowing how definition JSON is parsed.</p>
 *
 * <p>Everything here is expressed in domain types. Implementations join the
 * live source registry to the parsed definition files; callers see one list of
 * {@link SourceDescriptor}.</p>
 */
public interface SourceCatalog {

    /** Every registered source, in display order, each flagged with whether it is active. */
    List<SourceDescriptor> sources();

    /** One source by id, or null if nothing is registered under it. */
    SourceDescriptor source(String sourceId);

    /** Id of the source currently being read from. */
    String activeSourceId();

    /** Switch the active source. No-op for an id that is not registered. */
    void setActiveSourceId(String sourceId);

    /**
     * Position of a source in {@link #sources()}, for driving a spinner.
     *
     * @return the index, or 0 when the id is unknown — callers are selecting a
     *         list position, and a missing source should land on the first
     *         entry rather than throw
     */
    int indexOf(String sourceId);

    /**
     * Re-read definitions from disk, discarding anything cached.
     *
     * <p>Call after the user adds or edits a definition file. Sources
     * registered in Java are unaffected.</p>
     */
    void refresh();

    /**
     * Copy a weather source definition into the plugin's definition folder.
     *
     * @return an outcome carrying the message to show the user; never null
     */
    ImportOutcome importWeatherDefinition(File file);

    /** As {@link #importWeatherDefinition}, for a radar tile source definition. */
    ImportOutcome importRadarDefinition(File file);

    /**
     * Ask one source for current conditions, to prove it responds.
     *
     * <p>Backs the source manager's Test button. Deliberately not routed
     * through the repository: the point is to exercise <em>this</em> source,
     * not whichever one is active, and to bypass the cache.</p>
     *
     * @param sourceId the source to probe, which need not be the active one
     * @param callback notified on an unspecified thread — implementations do
     *                 not marshal to the main thread, callers must
     */
    void probe(String sourceId, double latitude, double longitude, ProbeCallback callback);

    /** Result of a {@link #probe}. */
    interface ProbeCallback {
        /** @param summary short human-readable description of what came back */
        void onReachable(String summary);
        void onUnreachable(String message);
    }

    /** Result of an import attempt. */
    final class ImportOutcome {
        private final boolean ok;
        private final String  message;

        private ImportOutcome(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }

        public static ImportOutcome ok(String message)     { return new ImportOutcome(true, message); }
        public static ImportOutcome failed(String message) { return new ImportOutcome(false, message); }

        public boolean succeeded() { return ok; }
        /** Ready to show the user as-is. */
        public String  message()   { return message; }
    }
}
