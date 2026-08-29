package com.atakmap.android.weather.domain.model;

import java.util.Collections;
import java.util.List;

/**
 * Everything the user interface needs to know about one weather source.
 *
 * <p>A source is two things at once in the layers below: a registered
 * {@code IWeatherRemoteSource} that can answer requests, and a
 * {@code WeatherSourceDefinition} parsed from a JSON file that describes it.
 * The settings screens needed a little of each, so they took both — which is
 * why five presentation classes reached into {@code data.remote} (finding F22).
 * This joins them once, in the layer that owns the join, and hands the screens
 * a single flat thing to render.</p>
 *
 * <p>Pure Java by rule: {@code domain.model} may not import Android.</p>
 */
public final class SourceDescriptor {

    private final String  id;
    private final String  displayName;
    private final String  description;
    private final String  apiBaseUrl;
    private final boolean requiresApiKey;
    private final boolean active;

    private final List<ParameterDescriptor> hourly;
    private final List<ParameterDescriptor> daily;
    private final List<ParameterDescriptor> current;

    private SourceDescriptor(Builder b) {
        this.id             = b.id;
        this.displayName    = b.displayName;
        this.description    = b.description;
        this.apiBaseUrl     = b.apiBaseUrl;
        this.requiresApiKey = b.requiresApiKey;
        this.active         = b.active;
        this.hourly         = Collections.unmodifiableList(b.hourly);
        this.daily          = Collections.unmodifiableList(b.daily);
        this.current        = Collections.unmodifiableList(b.current);
    }

    public String  id()             { return id; }
    public String  displayName()    { return displayName; }
    /** May be empty — not every definition carries one. */
    public String  description()    { return description; }
    /** May be empty — built-in sources do not all come from a definition file. */
    public String  apiBaseUrl()     { return apiBaseUrl; }
    public boolean requiresApiKey() { return requiresApiKey; }
    /** Whether this is the source the plugin is currently reading from. */
    public boolean active()         { return active; }

    public List<ParameterDescriptor> hourlyParameters()  { return hourly; }
    public List<ParameterDescriptor> dailyParameters()   { return daily; }
    public List<ParameterDescriptor> currentParameters() { return current; }

    /**
     * Whether a definition file was found for this source.
     *
     * <p>A source can be registered and usable with no definition behind it —
     * the built-in Java sources are. The screens show less detail in that case
     * rather than nothing, so they need to be able to ask.</p>
     */
    public boolean hasDefinition() {
        return !description.isEmpty() || !apiBaseUrl.isEmpty()
                || !hourly.isEmpty() || !daily.isEmpty() || !current.isEmpty();
    }

    /** Spinners render entries with {@code toString()}. */
    @Override
    public String toString() { return displayName; }

    public static Builder builder(String id) { return new Builder(id); }

    public static final class Builder {
        private final String id;
        private String  displayName    = "";
        private String  description    = "";
        private String  apiBaseUrl     = "";
        private boolean requiresApiKey = false;
        private boolean active         = false;
        private List<ParameterDescriptor> hourly  = Collections.emptyList();
        private List<ParameterDescriptor> daily   = Collections.emptyList();
        private List<ParameterDescriptor> current = Collections.emptyList();

        private Builder(String id) { this.id = id; }

        public Builder displayName(String v)    { this.displayName = orEmpty(v); return this; }
        public Builder description(String v)    { this.description = orEmpty(v); return this; }
        public Builder apiBaseUrl(String v)     { this.apiBaseUrl  = orEmpty(v); return this; }
        public Builder requiresApiKey(boolean v){ this.requiresApiKey = v;       return this; }
        public Builder active(boolean v)        { this.active = v;               return this; }

        public Builder hourly(List<ParameterDescriptor> v)  { this.hourly  = orEmpty(v); return this; }
        public Builder daily(List<ParameterDescriptor> v)   { this.daily   = orEmpty(v); return this; }
        public Builder current(List<ParameterDescriptor> v) { this.current = orEmpty(v); return this; }

        public SourceDescriptor build() {
            if (displayName.isEmpty()) displayName = id;
            return new SourceDescriptor(this);
        }

        private static String orEmpty(String v) { return v == null ? "" : v; }
        private static List<ParameterDescriptor> orEmpty(List<ParameterDescriptor> v) {
            return v == null ? Collections.<ParameterDescriptor>emptyList() : v;
        }
    }
}
