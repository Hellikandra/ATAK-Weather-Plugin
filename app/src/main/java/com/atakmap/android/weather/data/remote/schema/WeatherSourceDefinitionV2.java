package com.atakmap.android.weather.data.remote.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Complete v2 weather source definition parsed from an executable JSON schema.
 * Supports both weather data sources (current/hourly/daily/wind endpoints) and
 * radar tile sources (manifest + tile URL template), or a combined "both" type.
 *
 * <p>Instances are immutable once built. Use {@link Builder} to construct.</p>
 */
public class WeatherSourceDefinitionV2 {

    // ── Identity ─────────────────────────────────────────────────────────────
    private final String schemaVersion;
    private final String sourceId;
    private final String radarSourceId;
    private final String displayName;
    private final String description;
    private final String type;
    private final String provider;

    // ── Model / Auth / Rate Limit ────────────────────────────────────────────
    private final ModelMetadata model;
    private final AuthConfig auth;
    private final RateLimitConfig rateLimit;

    // ── Endpoints & Parameters (weather sources) ─────────────────────────────
    private final Map<String, EndpointDef> endpoints;
    private final Map<String, UnitConversionDef> unitConversions;
    private final Map<String, Map<String, String>> serverSideUnits;
    private final Map<String, Integer> weatherCodeMapping;
    private final BatchConfig batch;
    private final Map<String, List<ParameterDef>> parameters;

    // ── Radar-specific ───────────────────────────────────────────────────────
    private final String manifestUrl;
    private final String manifestFormat;
    private final ManifestParsingConfig manifestParsing;
    private final String tileUrlTemplate;
    private final int tileSize;
    private final int maxZoom;
    private final int defaultZoom;
    private final TileOptionsConfig tileOptions;

    // ── Metadata ─────────────────────────────────────────────────────────────
    private final String attribution;
    private final String license;

    private WeatherSourceDefinitionV2(Builder b) {
        this.schemaVersion = b.schemaVersion;
        this.sourceId = b.sourceId;
        this.radarSourceId = b.radarSourceId;
        this.displayName = b.displayName;
        this.description = b.description;
        this.type = b.type;
        this.provider = b.provider;
        this.model = b.model;
        this.auth = b.auth;
        this.rateLimit = b.rateLimit;

        this.endpoints = Collections.unmodifiableMap(new LinkedHashMap<>(b.endpoints));

        LinkedHashMap<String, UnitConversionDef> ucCopy = new LinkedHashMap<>(b.unitConversions);
        this.unitConversions = Collections.unmodifiableMap(ucCopy);

        LinkedHashMap<String, Map<String, String>> ssuCopy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> e : b.serverSideUnits.entrySet()) {
            ssuCopy.put(e.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(e.getValue())));
        }
        this.serverSideUnits = Collections.unmodifiableMap(ssuCopy);

        this.weatherCodeMapping = b.weatherCodeMapping != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(b.weatherCodeMapping))
                : Collections.<String, Integer>emptyMap();

        this.batch = b.batch;

        LinkedHashMap<String, List<ParameterDef>> pCopy = new LinkedHashMap<>();
        for (Map.Entry<String, List<ParameterDef>> e : b.parameters.entrySet()) {
            pCopy.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
        }
        this.parameters = Collections.unmodifiableMap(pCopy);

        this.manifestUrl = b.manifestUrl;
        this.manifestFormat = b.manifestFormat;
        this.manifestParsing = b.manifestParsing;
        this.tileUrlTemplate = b.tileUrlTemplate;
        this.tileSize = b.tileSize;
        this.maxZoom = b.maxZoom;
        this.defaultZoom = b.defaultZoom;
        this.tileOptions = b.tileOptions;
        this.attribution = b.attribution;
        this.license = b.license;
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public String getSchemaVersion() { return schemaVersion; }
    public String getSourceId() { return sourceId; }
    public String getRadarSourceId() { return radarSourceId; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public String getType() { return type; }
    public String getProvider() { return provider; }
    public ModelMetadata getModel() { return model; }
    public AuthConfig getAuth() { return auth; }
    public RateLimitConfig getRateLimit() { return rateLimit; }
    public Map<String, EndpointDef> getEndpoints() { return endpoints; }
    public Map<String, UnitConversionDef> getUnitConversions() { return unitConversions; }
    public Map<String, Map<String, String>> getServerSideUnits() { return serverSideUnits; }
    public Map<String, Integer> getWeatherCodeMapping() { return weatherCodeMapping; }
    public BatchConfig getBatch() { return batch; }
    public Map<String, List<ParameterDef>> getParameters() { return parameters; }
    public String getManifestUrl() { return manifestUrl; }
    public String getManifestFormat() { return manifestFormat; }
    public ManifestParsingConfig getManifestParsing() { return manifestParsing; }
    public String getTileUrlTemplate() { return tileUrlTemplate; }
    public int getTileSize() { return tileSize; }
    public int getMaxZoom() { return maxZoom; }
    public int getDefaultZoom() { return defaultZoom; }
    public TileOptionsConfig getTileOptions() { return tileOptions; }
    public String getAttribution() { return attribution; }
    public String getLicense() { return license; }

    // ── Convenience ──────────────────────────────────────────────────────────

    /**
     * Returns true if this definition provides weather data (type is "weather" or "both").
     */
    public boolean isWeatherSource() {
        return "weather".equalsIgnoreCase(type) || "both".equalsIgnoreCase(type);
    }

    /**
     * Returns true if this definition provides radar tile data (type is "radar" or "both").
     */
    public boolean isRadarSource() {
        return "radar".equalsIgnoreCase(type) || "both".equalsIgnoreCase(type);
    }

    /**
     * Look up an endpoint definition by its role key (e.g., "current", "hourly",
     * "daily", "windProfile", "windProfilePressure", "historical").
     *
     * @param role the endpoint role key
     * @return the endpoint definition, or null if not present
     */
    public EndpointDef getEndpoint(String role) {
        return endpoints.get(role);
    }

    /**
     * Look up selectable parameters for a category (e.g., "hourly", "daily", "current").
     *
     * @param category the parameter category key
     * @return an unmodifiable list of parameters, or an empty list if the category is absent
     */
    public List<ParameterDef> getParameters(String category) {
        List<ParameterDef> list = parameters.get(category);
        return list != null ? list : Collections.<ParameterDef>emptyList();
    }

    // ── Builder ──────────────────────────────────────────────────────────────

    public static class Builder {
        private String schemaVersion = "2.0";
        private String sourceId;
        private String radarSourceId;
        private String displayName = "";
        private String description = "";
        private String type = "weather";
        private String provider = "";
        private ModelMetadata model;
        private AuthConfig auth;
        private RateLimitConfig rateLimit;
        private Map<String, EndpointDef> endpoints = new LinkedHashMap<>();
        private Map<String, UnitConversionDef> unitConversions = new LinkedHashMap<>();
        private Map<String, Map<String, String>> serverSideUnits = new LinkedHashMap<>();
        private Map<String, Integer> weatherCodeMapping;
        private BatchConfig batch;
        private Map<String, List<ParameterDef>> parameters = new LinkedHashMap<>();
        private String manifestUrl;
        private String manifestFormat;
        private ManifestParsingConfig manifestParsing;
        private String tileUrlTemplate;
        private int tileSize = 256;
        private int maxZoom = 7;
        private int defaultZoom = 5;
        private TileOptionsConfig tileOptions;
        private String attribution = "";
        private String license = "";

        public Builder schemaVersion(String v) { this.schemaVersion = v; return this; }
        public Builder sourceId(String v) { this.sourceId = v; return this; }
        public Builder radarSourceId(String v) { this.radarSourceId = v; return this; }
        public Builder displayName(String v) { this.displayName = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder type(String v) { this.type = v; return this; }
        public Builder provider(String v) { this.provider = v; return this; }
        public Builder model(ModelMetadata v) { this.model = v; return this; }
        public Builder auth(AuthConfig v) { this.auth = v; return this; }
        public Builder rateLimit(RateLimitConfig v) { this.rateLimit = v; return this; }
        public Builder endpoints(Map<String, EndpointDef> v) { if (v != null) this.endpoints = v; return this; }
        public Builder unitConversions(Map<String, UnitConversionDef> v) { if (v != null) this.unitConversions = v; return this; }
        public Builder serverSideUnits(Map<String, Map<String, String>> v) { if (v != null) this.serverSideUnits = v; return this; }
        public Builder weatherCodeMapping(Map<String, Integer> v) { this.weatherCodeMapping = v; return this; }
        public Builder batch(BatchConfig v) { this.batch = v; return this; }
        public Builder parameters(Map<String, List<ParameterDef>> v) { if (v != null) this.parameters = v; return this; }
        public Builder manifestUrl(String v) { this.manifestUrl = v; return this; }
        public Builder manifestFormat(String v) { this.manifestFormat = v; return this; }
        public Builder manifestParsing(ManifestParsingConfig v) { this.manifestParsing = v; return this; }
        public Builder tileUrlTemplate(String v) { this.tileUrlTemplate = v; return this; }
        public Builder tileSize(int v) { this.tileSize = v; return this; }
        public Builder maxZoom(int v) { this.maxZoom = v; return this; }
        public Builder defaultZoom(int v) { this.defaultZoom = v; return this; }
        public Builder tileOptions(TileOptionsConfig v) { this.tileOptions = v; return this; }
        public Builder attribution(String v) { this.attribution = v; return this; }
        public Builder license(String v) { this.license = v; return this; }

        public WeatherSourceDefinitionV2 build() { return new WeatherSourceDefinitionV2(this); }
    }
}
