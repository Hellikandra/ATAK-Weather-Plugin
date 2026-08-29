package com.atakmap.android.weather.domain.model;

/**
 * What the radar source list needs to know about one tile provider.
 *
 * <p>Separate from {@link SourceDescriptor} because radar sources genuinely are
 * a different thing: they have no parameter lists and no API base URL, and they
 * are selected independently of the weather source. Folding them into one type
 * would mean a descriptor where half the fields are always empty depending on
 * which kind you happen to be holding.</p>
 */
public final class RadarSourceDescriptor {

    private final String  id;
    private final String  displayName;
    private final String  provider;
    private final boolean requiresApiKey;

    public RadarSourceDescriptor(String id, String displayName, String provider,
                                 boolean requiresApiKey) {
        this.id             = id;
        this.displayName    = displayName == null || displayName.isEmpty() ? id : displayName;
        this.provider       = provider == null ? "" : provider;
        this.requiresApiKey = requiresApiKey;
    }

    /** The {@code radarSourceId}, falling back to {@code sourceId}. Also the API key's key. */
    public String  id()             { return id; }
    public String  displayName()    { return displayName; }
    /** May be empty. */
    public String  provider()       { return provider; }
    /** True when the tile URL cannot be built without a key — see finding F35. */
    public boolean requiresApiKey() { return requiresApiKey; }

    @Override
    public String toString() { return displayName; }
}
