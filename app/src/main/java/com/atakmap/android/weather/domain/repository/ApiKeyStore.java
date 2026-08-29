package com.atakmap.android.weather.domain.repository;

/**
 * Where per-source API keys are kept.
 *
 * <p>The settings screens need to read and write credentials; they do not need
 * to know that credentials live in SharedPreferences, nor which file, nor under
 * which prefix. This port is the whole of what presentation is entitled to know
 * about the subject.</p>
 *
 * <p>It exists because of finding F35. Keys were being written by the settings
 * screen into one preferences file under one prefix, and read back at request
 * time from a different file under a different prefix — so a key the user
 * carefully typed in was stored somewhere nothing ever looked. Two call sites
 * had each invented their own storage because there was no agreed place to put
 * it. There is now exactly one implementation, and it is reached only through
 * this interface.</p>
 *
 * <p>Implementations must tolerate a null or empty {@code sourceId} by treating
 * it as "no key", rather than throwing.</p>
 */
public interface ApiKeyStore {

    /**
     * @param sourceId the source's id ({@code radarSourceId} for radar sources)
     * @return the stored key, or null when none is configured
     */
    String get(String sourceId);

    /** Store a key, replacing any previous one for the same source. */
    void put(String sourceId, String apiKey);

    /** Forget the key for a source. Safe to call when none is stored. */
    void remove(String sourceId);

    /** @return true when {@link #get} would return a non-empty key. */
    boolean has(String sourceId);
}
