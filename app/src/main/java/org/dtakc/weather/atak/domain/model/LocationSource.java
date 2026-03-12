package org.dtakc.weather.atak.domain.model;
public enum LocationSource {
    SELF_MARKER("Self marker"),
    MAP_CENTRE("Map centre");
    public final String label;
    LocationSource(String l) { label = l; }
}
