package com.atakmap.android.weather.data.remote.schema;

/**
 * Radar tile rendering options.
 * Controls color scheme, smoothing, snow differentiation,
 * and the composite options string used in the tile URL.
 */
public class TileOptionsConfig {

    private final int color;
    private final int smooth;
    private final int snow;
    private final String optionsString;

    public TileOptionsConfig(int color, int smooth, int snow, String optionsString) {
        this.color = color;
        this.smooth = smooth;
        this.snow = snow;
        this.optionsString = optionsString;
    }

    public int getColor() { return color; }
    public int getSmooth() { return smooth; }
    public int getSnow() { return snow; }
    public String getOptionsString() { return optionsString; }
}
