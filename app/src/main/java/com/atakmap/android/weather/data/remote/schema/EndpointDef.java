package com.atakmap.android.weather.data.remote.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Definition of a single API endpoint within a weather source.
 * Describes the URL, HTTP method, query parameters (which may contain
 * {@code {placeholder}} tokens), the JSON response path to extract data from,
 * and field mappings from internal names to API field names.
 */
public class EndpointDef {

    private final String url;
    private final String method;
    private final Map<String, String> queryParams;
    private final String responsePath;
    private final String timeField;
    private final Map<String, String> fieldMapping;
    private final List<Integer> windAltitudesM;
    private final List<Integer> windPressureLevelsHPa;
    private final Map<String, String> altitudeFieldPattern;

    private EndpointDef(Builder b) {
        this.url = b.url;
        this.method = b.method;
        this.queryParams = Collections.unmodifiableMap(new LinkedHashMap<>(b.queryParams));
        this.responsePath = b.responsePath;
        this.timeField = b.timeField;
        this.fieldMapping = Collections.unmodifiableMap(new LinkedHashMap<>(b.fieldMapping));
        this.windAltitudesM = Collections.unmodifiableList(new ArrayList<>(b.windAltitudesM));
        this.windPressureLevelsHPa = Collections.unmodifiableList(new ArrayList<>(b.windPressureLevelsHPa));
        this.altitudeFieldPattern = Collections.unmodifiableMap(new LinkedHashMap<>(b.altitudeFieldPattern));
    }

    public String getUrl() { return url; }
    public String getMethod() { return method; }
    public Map<String, String> getQueryParams() { return queryParams; }
    public String getResponsePath() { return responsePath; }
    public String getTimeField() { return timeField; }
    public Map<String, String> getFieldMapping() { return fieldMapping; }
    public List<Integer> getWindAltitudesM() { return windAltitudesM; }
    public List<Integer> getWindPressureLevelsHPa() { return windPressureLevelsHPa; }
    public Map<String, String> getAltitudeFieldPattern() { return altitudeFieldPattern; }

    public static class Builder {
        private String url = "";
        private String method = "GET";
        private Map<String, String> queryParams = new LinkedHashMap<>();
        private String responsePath = "";
        private String timeField;
        private Map<String, String> fieldMapping = new LinkedHashMap<>();
        private List<Integer> windAltitudesM = new ArrayList<>();
        private List<Integer> windPressureLevelsHPa = new ArrayList<>();
        private Map<String, String> altitudeFieldPattern = new LinkedHashMap<>();

        public Builder url(String v) { this.url = v; return this; }
        public Builder method(String v) { this.method = v; return this; }
        public Builder queryParams(Map<String, String> v) { if (v != null) this.queryParams = v; return this; }
        public Builder responsePath(String v) { this.responsePath = v; return this; }
        public Builder timeField(String v) { this.timeField = v; return this; }
        public Builder fieldMapping(Map<String, String> v) { if (v != null) this.fieldMapping = v; return this; }
        public Builder windAltitudesM(List<Integer> v) { if (v != null) this.windAltitudesM = v; return this; }
        public Builder windPressureLevelsHPa(List<Integer> v) { if (v != null) this.windPressureLevelsHPa = v; return this; }
        public Builder altitudeFieldPattern(Map<String, String> v) { if (v != null) this.altitudeFieldPattern = v; return this; }

        public EndpointDef build() { return new EndpointDef(this); }
    }
}
