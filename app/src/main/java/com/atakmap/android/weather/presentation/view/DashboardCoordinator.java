package com.atakmap.android.weather.presentation.view;

import android.content.Context;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.atakmap.android.weather.data.cache.MissionPrepManager;
import com.atakmap.android.weather.domain.model.LocationSnapshot;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.plugin.R;
import com.atakmap.android.weather.util.AutoRefreshManager;

/**
 * Coordinator for the Dashboard panel state indicators.
 *
 * <p>Extracted from {@code WeatherDropDownReceiver} (Sprint 21 — S21.1).
 * Manages: staleness badge, offline badge, loading/error states,
 * flight-category badge, and marker status label.</p>
 */
public class DashboardCoordinator {

    private final View rootView;
    private final Context appContext;

    // Cached views (found once in constructor)
    private final TextView    lastUpdatedBadge;
    private final TextView    offlineBadge;
    private final ProgressBar loadingProgress;
    private final View        errorState;
    private final TextView    errorMessage;
    private final TextView    fltCatBadge;

    private long lastUpdateMs = 0;

    public DashboardCoordinator(View rootView, Context appContext) {
        this.rootView   = rootView;
        this.appContext  = appContext;

        lastUpdatedBadge = rootView.findViewById(R.id.last_updated_badge);
        offlineBadge     = rootView.findViewById(R.id.offline_badge);
        loadingProgress  = rootView.findViewById(R.id.loading_progress);
        errorState       = rootView.findViewById(R.id.error_state);
        errorMessage     = rootView.findViewById(R.id.error_message);
        fltCatBadge      = rootView.findViewById(R.id.badge_parm_flt_cat);
    }

    // ── Staleness ──────────────────────────────────────────────────────────

    public void setLastUpdateMs(long ms) {
        this.lastUpdateMs = ms;
    }

    public void updateStalenesssBadge() {
        if (lastUpdatedBadge == null) return;
        if (lastUpdateMs <= 0) {
            lastUpdatedBadge.setVisibility(View.GONE);
            return;
        }
        String level = AutoRefreshManager.getStalenessLevel(lastUpdateMs);
        String text  = AutoRefreshManager.formatTimeSince(lastUpdateMs);
        lastUpdatedBadge.setText(text);
        lastUpdatedBadge.setTextColor(stalenessColor(level));
        lastUpdatedBadge.setVisibility(View.VISIBLE);
    }

    /**
     * Staleness colour for the "last updated" badge.
     * Inlined from the removed ThemeManager — single dark palette.
     */
    private static int stalenessColor(String level) {
        if (level == null) return 0xFF8B949E; // muted
        switch (level) {
            case "fresh": return 0xFF3FB950; // green
            case "aging": return 0xFFD29922; // amber
            case "stale": return 0xFFF85149; // red
            default:      return 0xFF8B949E; // muted
        }
    }

    // ── Offline ────────────────────────────────────────────────────────────

    public void updateOfflineBadge() {
        if (offlineBadge == null) return;
        boolean online = MissionPrepManager.isOnline(appContext);
        offlineBadge.setVisibility(online ? View.GONE : View.VISIBLE);
    }

    // ── Loading / Error ────────────────────────────────────────────────────

    public void showLoadingState() {
        if (loadingProgress != null) loadingProgress.setVisibility(View.VISIBLE);
        hideErrorState();
    }

    public void hideLoadingState() {
        if (loadingProgress != null) loadingProgress.setVisibility(View.GONE);
    }

    public void showErrorState(String msg) {
        hideLoadingState();
        if (errorState != null) errorState.setVisibility(View.VISIBLE);
        if (errorMessage != null && msg != null) errorMessage.setText(msg);
    }

    public void hideErrorState() {
        if (errorState != null) errorState.setVisibility(View.GONE);
    }

    // ── Flight category badge ──────────────────────────────────────────────

    public void updateFltCatBadge(WeatherModel w) {
        if (fltCatBadge == null) return;
        if (w == null || !w.isMetarSource() || w.getFlightCategory().isEmpty()) {
            fltCatBadge.setVisibility(View.GONE);
            return;
        }
        String cat = w.getFlightCategory();
        int bg;
        switch (cat) {
            case "VFR":  bg = 0xFF00AA00; break;
            case "MVFR": bg = 0xFF0055FF; break;
            case "IFR":  bg = 0xFFCC0000; break;
            case "LIFR": bg = 0xFFAA00AA; break;
            default:     bg = 0xFF555555; break;
        }
        fltCatBadge.setBackgroundColor(bg);
        fltCatBadge.setText(cat);
        fltCatBadge.setVisibility(View.VISIBLE);
    }

}
