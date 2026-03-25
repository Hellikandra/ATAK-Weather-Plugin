package com.atakmap.android.weather.overlay.radar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Data class holding parsed radar manifest results.
 *
 * <p>Encapsulates the host URL, past observation frames, future/nowcast frames,
 * and the manifest generation timestamp. Used by {@link IRadarManifestParser}
 * implementations to return a provider-agnostic representation of available
 * radar frames.</p>
 *
 * <p>Instances are immutable once built.</p>
 */
public class RadarManifest {

    private final String host;
    private final List<RadarFrame> past;
    private final List<RadarFrame> future;
    private final long generatedTime;

    private RadarManifest(Builder builder) {
        this.host = builder.host;
        this.past = Collections.unmodifiableList(new ArrayList<>(builder.past));
        this.future = Collections.unmodifiableList(new ArrayList<>(builder.future));
        this.generatedTime = builder.generatedTime;
    }

    /** Tile server base URL (e.g., "https://tilecache.rainviewer.com"). May be null for static sources. */
    public String getHost() { return host; }

    /** Past observation frames (ordered oldest → newest). */
    public List<RadarFrame> getPast() { return past; }

    /** Future/nowcast frames (may be empty). */
    public List<RadarFrame> getFuture() { return future; }

    /** When the manifest was generated (Unix epoch seconds), or 0 if unknown. */
    public long getGeneratedTime() { return generatedTime; }

    /** All frames: past + future, in chronological order. */
    public List<RadarFrame> getAllFrames() {
        List<RadarFrame> all = new ArrayList<>(past.size() + future.size());
        all.addAll(past);
        all.addAll(future);
        return Collections.unmodifiableList(all);
    }

    /** Total frame count (past + future). */
    public int getFrameCount() { return past.size() + future.size(); }

    // ── RadarFrame ───────────────────────────────────────────────────────────

    /**
     * A single radar frame within a manifest.
     */
    public static class RadarFrame {
        private final long timestamp;
        private final String path;

        public RadarFrame(long timestamp, String path) {
            this.timestamp = timestamp;
            this.path = path;
        }

        /** Unix epoch seconds for this frame. */
        public long getTimestamp() { return timestamp; }

        /** Tile path from the manifest (e.g., "/v5/weather-maps/..."). May be null for static sources. */
        public String getPath() { return path; }
    }

    // ── Builder ──────────────────────────────────────────────────────────────

    public static class Builder {
        private String host;
        private List<RadarFrame> past = new ArrayList<>();
        private List<RadarFrame> future = new ArrayList<>();
        private long generatedTime;

        public Builder host(String host) { this.host = host; return this; }
        public Builder past(List<RadarFrame> past) { if (past != null) this.past = past; return this; }
        public Builder future(List<RadarFrame> future) { if (future != null) this.future = future; return this; }
        public Builder generatedTime(long generatedTime) { this.generatedTime = generatedTime; return this; }

        public Builder addPastFrame(long timestamp, String path) {
            this.past.add(new RadarFrame(timestamp, path));
            return this;
        }

        public Builder addFutureFrame(long timestamp, String path) {
            this.future.add(new RadarFrame(timestamp, path));
            return this;
        }

        public RadarManifest build() { return new RadarManifest(this); }
    }
}
