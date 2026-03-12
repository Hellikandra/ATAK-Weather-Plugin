package org.dtakc.weather.atak.data.remote;

import java.util.Collections;
import java.util.List;

/**
 * Parsed representation of a weather_sources/*.json definition file.
 *
 * Schema:
 * <pre>
 * {
 *   "sourceId":       "open-meteo",
 *   "displayName":    "Open-Meteo (GFS Global)",
 *   "apiBaseUrl":     "https://api.open-meteo.com/v1/forecast",
 *   "requiresApiKey": false,
 *   "description":    "...",
 *   "parameters": {
 *     "hourly":  [ {"key":"temperature_2m","label":"Temperature (2 m)","defaultOn":true}, ... ],
 *     "daily":   [ ... ],
 *     "current": [ ... ]
 *   }
 * }
 * </pre>
 *
 * Radar sources use a different schema (radarSourceId instead of sourceId).
 *
 * Custom definitions can be placed by the user in:
 *   /sdcard/atak/tools/weather_sources/   (or Context.getExternalFilesDir path)
 * User files override bundled assets with the same sourceId.
 */
public class WeatherSourceDefinition {

    public final String            sourceId;
    public final String            displayName;
    public final String            apiBaseUrl;
    public final boolean           requiresApiKey;
    public final String            apiKey;          // nullable; loaded from SharedPrefs
    public final String            description;
    public final List<ParamEntry>  hourlyParams;
    public final List<ParamEntry>  dailyParams;
    public final List<ParamEntry>  currentParams;

    // ── Radar-specific (only set for radar source definitions) ────────────────
    public final String radarSourceId;
    public final String manifestUrl;
    public final String tileUrlTemplate;
    public final int    tileSize;
    public final int    defaultZoom;
    public final String attribution;

    /** A single parameter entry from the JSON "parameters" arrays. */
    public static class ParamEntry {
        public final String  key;
        public final String  label;
        public final boolean defaultOn;

        public ParamEntry(String key, String label, boolean defaultOn) {
            this.key       = key;
            this.label     = label;
            this.defaultOn = defaultOn;
        }
    }

    private WeatherSourceDefinition(Builder b) {
        this.sourceId       = b.sourceId;
        this.displayName    = b.displayName;
        this.apiBaseUrl     = b.apiBaseUrl;
        this.requiresApiKey = b.requiresApiKey;
        this.apiKey         = b.apiKey;
        this.description    = b.description;
        this.hourlyParams   = Collections.unmodifiableList(b.hourlyParams);
        this.dailyParams    = Collections.unmodifiableList(b.dailyParams);
        this.currentParams  = Collections.unmodifiableList(b.currentParams);
        this.radarSourceId  = b.radarSourceId;
        this.manifestUrl    = b.manifestUrl;
        this.tileUrlTemplate= b.tileUrlTemplate;
        this.tileSize       = b.tileSize;
        this.defaultZoom    = b.defaultZoom;
        this.attribution    = b.attribution;
    }

    public boolean isRadarDefinition() { return radarSourceId != null; }

    public static class Builder {
        String            sourceId       = "";
        String            displayName    = "";
        String            apiBaseUrl     = "";
        boolean           requiresApiKey = false;
        String            apiKey         = null;
        String            description    = "";
        List<ParamEntry>  hourlyParams   = new java.util.ArrayList<>();
        List<ParamEntry>  dailyParams    = new java.util.ArrayList<>();
        List<ParamEntry>  currentParams  = new java.util.ArrayList<>();
        String            radarSourceId  = null;
        String            manifestUrl    = null;
        String            tileUrlTemplate= null;
        int               tileSize       = 256;
        int               defaultZoom    = 5;
        String            attribution    = "";

        public Builder sourceId(String v)        { sourceId = v;        return this; }
        public Builder displayName(String v)     { displayName = v;     return this; }
        public Builder apiBaseUrl(String v)      { apiBaseUrl = v;      return this; }
        public Builder requiresApiKey(boolean v) { requiresApiKey = v;  return this; }
        public Builder apiKey(String v)          { apiKey = v;          return this; }
        public Builder description(String v)     { description = v;     return this; }
        public Builder hourlyParams(List<ParamEntry> v)  { if (v != null) hourlyParams = v;  return this; }
        public Builder dailyParams(List<ParamEntry> v)   { if (v != null) dailyParams = v;   return this; }
        public Builder currentParams(List<ParamEntry> v) { if (v != null) currentParams = v; return this; }
        public Builder radarSourceId(String v)   { radarSourceId = v;   return this; }
        public Builder manifestUrl(String v)     { manifestUrl = v;     return this; }
        public Builder tileUrlTemplate(String v) { tileUrlTemplate = v; return this; }
        public Builder tileSize(int v)           { tileSize = v;        return this; }
        public Builder defaultZoom(int v)        { defaultZoom = v;     return this; }
        public Builder attribution(String v)     { attribution = v;     return this; }

        public WeatherSourceDefinition build()   { return new WeatherSourceDefinition(this); }
    }
}
