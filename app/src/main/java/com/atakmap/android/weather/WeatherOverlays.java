package com.atakmap.android.weather;

import com.atakmap.android.weather.overlay.aviation.SigmetOverlayManager;
import com.atakmap.android.weather.overlay.cbrn.CbrnOverlayManager;
import com.atakmap.android.weather.overlay.heatmap.HeatmapLegendWidget;
import com.atakmap.android.weather.overlay.heatmap.HeatmapOverlayManager;
import com.atakmap.android.weather.overlay.lightning.LightningOverlayManager;
import com.atakmap.android.weather.overlay.radar.RadarOverlayManager;
import com.atakmap.android.weather.overlay.wind.WindArrowOverlayView;
import com.atakmap.android.weather.overlay.wind.WindParticleBitmapView;
import com.atakmap.android.weather.overlay.wind.WindParticleLayer;

/**
 * The nine things that draw on the map, as one object.
 *
 * <p>The map-side counterpart to {@link WeatherDependencies}, which does the
 * same job for the data layer. Both exist so that "was this wired up?" stops
 * being a question anyone has to ask.</p>
 *
 * <h3>Why this exists (finding F25)</h3>
 * <p>These nine used to travel individually, twice over.
 * {@code WeatherMapComponent} passed one through the drop-down's constructor and
 * the other eight through setters; the drop-down held them as fields, used none
 * of them itself, and forwarded each to {@code OverlayTabCoordinator} through
 * eight more setters plus a ninth. Eighteen wiring points, every one of them
 * temporal coupling with no compile-time guard, for objects that were only ever
 * passing through.</p>
 *
 * <p>Each hop was wrapped in {@code if (x != null)} — not because any of them is
 * optional, but because a setter someone forgot to call is indistinguishable
 * from one that was called with null. Now the bundle either exists or does not,
 * and a tenth overlay cannot be added without every construction site failing to
 * compile until it is supplied.</p>
 *
 * <p>All nine are required. {@code WeatherMapComponent} creates every one
 * unconditionally before the drop-down is built, so "optional" was never true —
 * it was only unenforced.</p>
 */
public final class WeatherOverlays {

    private final RadarOverlayManager      radar;
    private final HeatmapOverlayManager    heatmap;
    private final SigmetOverlayManager     sigmet;
    private final LightningOverlayManager  lightning;
    private final CbrnOverlayManager       cbrn;
    private final HeatmapLegendWidget      heatmapLegend;
    private final WindArrowOverlayView     windArrows;
    private final WindParticleLayer        windParticleLayer;
    private final WindParticleBitmapView   windParticleView;

    private WeatherOverlays(Builder b) {
        this.radar             = b.radar;
        this.heatmap           = b.heatmap;
        this.sigmet            = b.sigmet;
        this.lightning         = b.lightning;
        this.cbrn              = b.cbrn;
        this.heatmapLegend     = b.heatmapLegend;
        this.windArrows        = b.windArrows;
        this.windParticleLayer = b.windParticleLayer;
        this.windParticleView  = b.windParticleView;
    }

    public RadarOverlayManager     radar()             { return radar; }
    public HeatmapOverlayManager   heatmap()           { return heatmap; }
    public SigmetOverlayManager    sigmet()            { return sigmet; }
    public LightningOverlayManager lightning()         { return lightning; }
    public CbrnOverlayManager      cbrn()              { return cbrn; }
    public HeatmapLegendWidget     heatmapLegend()     { return heatmapLegend; }
    public WindArrowOverlayView    windArrows()        { return windArrows; }
    public WindParticleLayer       windParticleLayer() { return windParticleLayer; }
    public WindParticleBitmapView  windParticleView()  { return windParticleView; }

    public static Builder builder() { return new Builder(); }

    /**
     * Collects the nine and refuses to produce a half-built bundle.
     *
     * <p>{@link #build()} throws rather than returning something with a null in
     * it. That is the whole point: the failure moves from "a tab is dead when a
     * user opens it, weeks later" to "the plugin does not start", which is loud,
     * immediate, and traceable to the line that forgot.</p>
     */
    public static final class Builder {
        private RadarOverlayManager      radar;
        private HeatmapOverlayManager    heatmap;
        private SigmetOverlayManager     sigmet;
        private LightningOverlayManager  lightning;
        private CbrnOverlayManager       cbrn;
        private HeatmapLegendWidget      heatmapLegend;
        private WindArrowOverlayView     windArrows;
        private WindParticleLayer        windParticleLayer;
        private WindParticleBitmapView   windParticleView;

        private Builder() { }

        public Builder radar(RadarOverlayManager v)             { this.radar = v;             return this; }
        public Builder heatmap(HeatmapOverlayManager v)         { this.heatmap = v;           return this; }
        public Builder sigmet(SigmetOverlayManager v)           { this.sigmet = v;            return this; }
        public Builder lightning(LightningOverlayManager v)     { this.lightning = v;         return this; }
        public Builder cbrn(CbrnOverlayManager v)               { this.cbrn = v;              return this; }
        public Builder heatmapLegend(HeatmapLegendWidget v)     { this.heatmapLegend = v;     return this; }
        public Builder windArrows(WindArrowOverlayView v)       { this.windArrows = v;        return this; }
        public Builder windParticleLayer(WindParticleLayer v)   { this.windParticleLayer = v; return this; }
        public Builder windParticleView(WindParticleBitmapView v){ this.windParticleView = v;  return this; }

        /**
         * @throws IllegalStateException naming every overlay that was not supplied
         */
        public WeatherOverlays build() {
            StringBuilder missing = new StringBuilder();
            require(missing, "radar", radar);
            require(missing, "heatmap", heatmap);
            require(missing, "sigmet", sigmet);
            require(missing, "lightning", lightning);
            require(missing, "cbrn", cbrn);
            require(missing, "heatmapLegend", heatmapLegend);
            require(missing, "windArrows", windArrows);
            require(missing, "windParticleLayer", windParticleLayer);
            require(missing, "windParticleView", windParticleView);

            if (missing.length() > 0) {
                throw new IllegalStateException(
                        "WeatherOverlays is missing: " + missing
                                + ". Every overlay is required; see finding F25.");
            }
            return new WeatherOverlays(this);
        }

        private static void require(StringBuilder missing, String name, Object value) {
            if (value != null) return;
            if (missing.length() > 0) missing.append(", ");
            missing.append(name);
        }
    }
}
