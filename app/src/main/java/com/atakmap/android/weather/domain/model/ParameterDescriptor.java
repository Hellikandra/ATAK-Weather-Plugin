package com.atakmap.android.weather.domain.model;

/**
 * One selectable weather parameter, as the settings screens need to show it.
 *
 * <p>The presentation layer used to pass {@code WeatherSourceDefinition.ParamEntry}
 * around — a nested class of a JSON-parsing type in {@code data.remote}. That
 * meant the parameter list could not be rendered without the layer that knows
 * how definition files are laid out on disk, and it is a large part of finding
 * F22.</p>
 *
 * <p>Deliberately three fields. The JSON entry may grow; what a checkbox needs
 * is a key to store, a label to show, and whether it starts ticked.</p>
 */
public final class ParameterDescriptor {

    private final String  key;
    private final String  label;
    private final boolean defaultOn;

    public ParameterDescriptor(String key, String label, boolean defaultOn) {
        this.key       = key;
        this.label     = label;
        this.defaultOn = defaultOn;
    }

    /** The provider's parameter name, e.g. {@code temperature_2m}. Also the preference key. */
    public String key() { return key; }

    /** Human-readable name for the checkbox. */
    public String label() { return label; }

    /** Whether this parameter is selected when the user has expressed no preference. */
    public boolean defaultOn() { return defaultOn; }

    @Override
    public String toString() { return label != null ? label : key; }
}
