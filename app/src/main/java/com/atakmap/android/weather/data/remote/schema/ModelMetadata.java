package com.atakmap.android.weather.data.remote.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Metadata about the weather model behind a source definition.
 * Includes grid resolution, forecast range, update frequency, and
 * available pressure levels for upper-air data.
 */
public class ModelMetadata {

    private final String name;
    private final Double gridResolutionKm;
    private final Double accuracyRadiusKm;
    private final int forecastDaysMax;
    private final int updateFrequencyHours;
    private final String coverage;
    private final String temporalResolution;
    private final List<Integer> pressureLevels;
    private final String sourceUrl;

    private ModelMetadata(Builder b) {
        this.name = b.name;
        this.gridResolutionKm = b.gridResolutionKm;
        this.accuracyRadiusKm = b.accuracyRadiusKm;
        this.forecastDaysMax = b.forecastDaysMax;
        this.updateFrequencyHours = b.updateFrequencyHours;
        this.coverage = b.coverage;
        this.temporalResolution = b.temporalResolution;
        this.pressureLevels = Collections.unmodifiableList(new ArrayList<>(b.pressureLevels));
        this.sourceUrl = b.sourceUrl;
    }

    public String getName() { return name; }
    public Double getGridResolutionKm() { return gridResolutionKm; }
    public Double getAccuracyRadiusKm() { return accuracyRadiusKm; }
    public int getForecastDaysMax() { return forecastDaysMax; }
    public int getUpdateFrequencyHours() { return updateFrequencyHours; }
    public String getCoverage() { return coverage; }
    public String getTemporalResolution() { return temporalResolution; }
    public List<Integer> getPressureLevels() { return pressureLevels; }
    public String getSourceUrl() { return sourceUrl; }

    public static class Builder {
        private String name = "";
        private Double gridResolutionKm;
        private Double accuracyRadiusKm;
        private int forecastDaysMax;
        private int updateFrequencyHours;
        private String coverage = "";
        private String temporalResolution = "";
        private List<Integer> pressureLevels = new ArrayList<>();
        private String sourceUrl = "";

        public Builder name(String v) { this.name = v; return this; }
        public Builder gridResolutionKm(Double v) { this.gridResolutionKm = v; return this; }
        public Builder accuracyRadiusKm(Double v) { this.accuracyRadiusKm = v; return this; }
        public Builder forecastDaysMax(int v) { this.forecastDaysMax = v; return this; }
        public Builder updateFrequencyHours(int v) { this.updateFrequencyHours = v; return this; }
        public Builder coverage(String v) { this.coverage = v; return this; }
        public Builder temporalResolution(String v) { this.temporalResolution = v; return this; }
        public Builder pressureLevels(List<Integer> v) { if (v != null) this.pressureLevels = v; return this; }
        public Builder sourceUrl(String v) { this.sourceUrl = v; return this; }

        public ModelMetadata build() { return new ModelMetadata(this); }
    }
}
