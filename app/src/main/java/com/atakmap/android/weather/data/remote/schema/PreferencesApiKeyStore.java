package com.atakmap.android.weather.data.remote.schema;

import android.content.Context;

import com.atakmap.android.weather.domain.repository.ApiKeyStore;

/**
 * The one implementation of {@link ApiKeyStore}, backed by SharedPreferences
 * through {@link AuthProvider}.
 *
 * <p>{@code AuthProvider} stays static because the request paths call it from
 * deep inside fetch loops where threading an instance through would be worse
 * than the static. This class is the seam the <em>user interface</em> uses: it
 * holds the host context once, and hands presentation a four-method interface
 * that says nothing about Android.</p>
 *
 * <p>The context must be the host activity context. A plugin context has no
 * data directory, so preferences opened against it fail with
 * {@code mkdir ENOENT} — see the two-context rules in the project README.</p>
 */
public final class PreferencesApiKeyStore implements ApiKeyStore {

    private final Context appContext;

    /**
     * @param appContext host activity context ({@code mapView.getContext()}),
     *                   never the plugin context
     */
    public PreferencesApiKeyStore(Context appContext) {
        this.appContext = appContext;
    }

    @Override
    public String get(String sourceId) {
        if (sourceId == null || sourceId.isEmpty()) return null;
        return AuthProvider.getApiKey(appContext, sourceId, null);
    }

    @Override
    public void put(String sourceId, String apiKey) {
        if (sourceId == null || sourceId.isEmpty()) return;
        if (apiKey == null || apiKey.trim().isEmpty()) {
            remove(sourceId);
            return;
        }
        AuthProvider.storeApiKey(appContext, sourceId, apiKey.trim());
    }

    @Override
    public void remove(String sourceId) {
        if (sourceId == null || sourceId.isEmpty()) return;
        AuthProvider.removeApiKey(appContext, sourceId);
    }

    @Override
    public boolean has(String sourceId) {
        String key = get(sourceId);
        return key != null && !key.isEmpty();
    }
}
