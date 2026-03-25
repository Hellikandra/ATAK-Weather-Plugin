package com.atakmap.android.weather.data.remote.schema;

/**
 * Defines a unit conversion as a linear transform: {@code result = value * factor + offset}.
 * Used as a client-side fallback when the API does not support server-side unit conversion.
 */
public class UnitConversionDef {

    private final String from;
    private final String to;
    private final double factor;
    private final double offset;

    public UnitConversionDef(String from, String to, double factor, double offset) {
        this.from = from;
        this.to = to;
        this.factor = factor;
        this.offset = offset;
    }

    public String getFrom() { return from; }
    public String getTo() { return to; }
    public double getFactor() { return factor; }
    public double getOffset() { return offset; }

    /**
     * Apply the linear conversion: {@code value * factor + offset}.
     *
     * @param value the input value in the {@link #getFrom()} unit
     * @return the converted value in the {@link #getTo()} unit
     */
    public double convert(double value) {
        return value * factor + offset;
    }
}
