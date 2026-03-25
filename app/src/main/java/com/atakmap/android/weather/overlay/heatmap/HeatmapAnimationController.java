package com.atakmap.android.weather.overlay.heatmap;

import android.os.Handler;
import android.os.Looper;

import com.atakmap.coremap.log.Log;

/**
 * Controls animated playback of forecast heatmap frames.
 *
 * <p>Cycles through hour indices at a fixed frame rate, driving the
 * {@link HeatmapOverlayManager#setHourIndex(int)} method to animate
 * the heatmap overlay through the 48-hour forecast window.</p>
 *
 * <h3>Usage</h3>
 * <pre>
 *   controller.setOnFrameChangeListener((hourIndex, timeLabel) -> {
 *       seekBar.setProgress(hourIndex);
 *       timeLabel.setText(label);
 *   });
 *   controller.play();   // start animation
 *   controller.pause();  // pause
 *   controller.stepForward();  // single step
 * </pre>
 */
public class HeatmapAnimationController {

    private static final String TAG = "HeatmapAnimCtrl";

    /** Delay between frames in milliseconds. */
    private static final long FRAME_DELAY_MS = 500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HeatmapOverlayManager manager;

    private boolean playing = false;
    private int maxHour = 47;
    private OnFrameChangeListener frameListener;

    /** Runnable that advances the animation by one frame. */
    private final Runnable frameAdvancer = new Runnable() {
        @Override
        public void run() {
            if (!playing) return;

            int current = manager.getCurrentHourIndex();
            int next = current + 1;
            if (next > maxHour) {
                next = 0; // loop back to start
            }

            manager.setHourIndex(next);
            notifyFrame(next);

            // Schedule next frame
            handler.postDelayed(this, FRAME_DELAY_MS);
        }
    };

    public HeatmapAnimationController(HeatmapOverlayManager manager) {
        this.manager = manager;
    }

    // ── Listener ────────────────────────────────────────────────────────────

    /**
     * Listener notified each time the animation frame changes.
     */
    public interface OnFrameChangeListener {
        /**
         * @param hourIndex the current hour index
         * @param timeLabel human-readable time label for this hour
         */
        void onFrameChanged(int hourIndex, String timeLabel);
    }

    public void setOnFrameChangeListener(OnFrameChangeListener l) {
        this.frameListener = l;
    }

    // ── Playback controls ───────────────────────────────────────────────────

    /** Start or resume animation playback. */
    public void play() {
        if (playing) return;
        playing = true;

        // Update maxHour from current data set
        HeatmapDataSet ds = manager.getCurrentDataSet();
        if (ds != null) {
            maxHour = ds.getHoursCount() - 1;
        }

        Log.d(TAG, "Play: maxHour=" + maxHour);
        handler.post(frameAdvancer);
    }

    /** Pause animation playback. */
    public void pause() {
        playing = false;
        handler.removeCallbacks(frameAdvancer);
        Log.d(TAG, "Paused");
    }

    /** Step forward by one hour. Pauses if playing. */
    public void stepForward() {
        if (playing) pause();

        int current = manager.getCurrentHourIndex();
        int next = Math.min(current + 1, maxHour);
        manager.setHourIndex(next);
        notifyFrame(next);
    }

    /** Step backward by one hour. Pauses if playing. */
    public void stepBackward() {
        if (playing) pause();

        int current = manager.getCurrentHourIndex();
        int prev = Math.max(current - 1, 0);
        manager.setHourIndex(prev);
        notifyFrame(prev);
    }

    /** Returns true if animation is currently playing. */
    public boolean isPlaying() { return playing; }

    /**
     * Update the maximum hour index. Call when new data is loaded.
     */
    public void setMaxHour(int max) {
        this.maxHour = Math.max(0, max);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void notifyFrame(int hourIndex) {
        if (frameListener == null) return;

        String timeLabel = "";
        HeatmapDataSet ds = manager.getCurrentDataSet();
        if (ds != null) {
            String[] labels = ds.getTimeLabels();
            if (hourIndex >= 0 && hourIndex < labels.length) {
                timeLabel = labels[hourIndex];
            }
        }

        if (timeLabel.isEmpty()) {
            timeLabel = "+" + hourIndex + "h";
        }

        frameListener.onFrameChanged(hourIndex, timeLabel);
    }

    /** Stop and clean up. Call from dispose(). */
    public void dispose() {
        pause();
        handler.removeCallbacksAndMessages(null);
    }
}
