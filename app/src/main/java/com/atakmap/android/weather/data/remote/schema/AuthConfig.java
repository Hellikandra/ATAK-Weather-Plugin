package com.atakmap.android.weather.data.remote.schema;

/**
 * Authentication configuration for a weather source.
 * Supports none, API key (header or query param), bearer token,
 * basic auth, and custom header types.
 */
public class AuthConfig {

    private final String type;
    private final String headerName;
    private final String queryParam;
    private final String envVar;
    private final String value;

    private AuthConfig(Builder b) {
        this.type = b.type;
        this.headerName = b.headerName;
        this.queryParam = b.queryParam;
        this.envVar = b.envVar;
        this.value = b.value;
    }

    public String getType() { return type; }
    public String getHeaderName() { return headerName; }
    public String getQueryParam() { return queryParam; }
    public String getEnvVar() { return envVar; }
    public String getValue() { return value; }

    /**
     * Returns true if authentication is required (type is not "none").
     */
    public boolean isRequired() {
        return type != null && !"none".equalsIgnoreCase(type);
    }

    public static class Builder {
        private String type = "none";
        private String headerName;
        private String queryParam;
        private String envVar;
        private String value = "";

        public Builder type(String v) { this.type = v; return this; }
        public Builder headerName(String v) { this.headerName = v; return this; }
        public Builder queryParam(String v) { this.queryParam = v; return this; }
        public Builder envVar(String v) { this.envVar = v; return this; }
        public Builder value(String v) { this.value = v; return this; }

        public AuthConfig build() { return new AuthConfig(this); }
    }
}
