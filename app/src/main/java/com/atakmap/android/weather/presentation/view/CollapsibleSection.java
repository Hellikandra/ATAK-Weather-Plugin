package com.atakmap.android.weather.presentation.view;

import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.atakmap.coremap.log.Log;

/**
 * CollapsibleSection — makes dashboard sections collapsible with chevron toggle.
 *
 * <h3>Sprint 13 — S13.4</h3>
 * Adds expand/collapse behaviour to section headers on the dashboard.
 * The collapsed/expanded state is persisted per section via SharedPreferences.
 *
 * <h3>Chevron indicators</h3>
 * <ul>
 *   <li>Expanded: prepends "&#x25BC; " (down-pointing triangle)</li>
 *   <li>Collapsed: prepends "&#x25B6; " (right-pointing triangle)</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>
 *   CollapsibleSection.setup(headerView, contentView, "metar_card", prefs);
 * </pre>
 */
public class CollapsibleSection {

    private static final String TAG = "CollapsibleSection";
    private static final String PREFIX_EXPANDED  = "\u25BC ";  // ▼
    private static final String PREFIX_COLLAPSED = "\u25B6 ";  // ▶
    private static final String PREF_PREFIX = "wx_section_collapsed_";

    private CollapsibleSection() { /* utility class */ }

    /**
     * Make a section header collapsible with chevron toggle.
     *
     * @param header  the clickable header view (typically a TextView)
     * @param content the content view to show/hide
     * @param prefKey unique key for persisting state (e.g. "metar_card")
     * @param prefs   SharedPreferences for state persistence
     */
    public static void setup(final View header, final View content,
                             final String prefKey, final SharedPreferences prefs) {
        if (header == null || content == null) return;

        final String fullPrefKey = PREF_PREFIX + prefKey;

        // Restore saved state (default = expanded)
        boolean collapsed = prefs != null && prefs.getBoolean(fullPrefKey, false);
        applyState(header, content, collapsed);

        // Toggle on click
        header.setOnClickListener(new View.OnClickListener() {
            private boolean isCollapsed = collapsed;

            @Override
            public void onClick(View v) {
                isCollapsed = !isCollapsed;
                applyState(header, content, isCollapsed);
                if (prefs != null) {
                    prefs.edit().putBoolean(fullPrefKey, isCollapsed).apply();
                }
            }
        });

        Log.d(TAG, "Collapsible section set up: " + prefKey
                + " (collapsed=" + collapsed + ")");
    }

    /**
     * Apply the collapsed/expanded visual state.
     */
    private static void applyState(View header, View content, boolean collapsed) {
        content.setVisibility(collapsed ? View.GONE : View.VISIBLE);

        // Update chevron on the header if it's a TextView
        if (header instanceof TextView) {
            TextView tv = (TextView) header;
            String text = tv.getText().toString();
            // Strip any existing chevron prefix
            text = stripChevron(text);
            String prefix = collapsed ? PREFIX_COLLAPSED : PREFIX_EXPANDED;
            tv.setText(prefix + text);
        }
    }

    /**
     * Strip the chevron prefix from a text string.
     */
    private static String stripChevron(String text) {
        if (text == null) return "";
        if (text.startsWith(PREFIX_EXPANDED))  return text.substring(PREFIX_EXPANDED.length());
        if (text.startsWith(PREFIX_COLLAPSED)) return text.substring(PREFIX_COLLAPSED.length());
        // Also handle Unicode chars without trailing space
        if (text.length() > 0 && (text.charAt(0) == '\u25BC' || text.charAt(0) == '\u25B6')) {
            return text.substring(1).trim();
        }
        return text;
    }

    /**
     * Setup a section with a title string.  Creates the chevron-prefixed text
     * on the header view and wires the toggle.
     *
     * @param header     the clickable header view (must be a TextView)
     * @param content    the content view to show/hide
     * @param title      the section title (without chevron)
     * @param prefKey    unique key for persisting state
     * @param prefs      SharedPreferences for state persistence
     */
    public static void setup(final View header, final View content,
                             final String title, final String prefKey,
                             final SharedPreferences prefs) {
        if (header instanceof TextView) {
            ((TextView) header).setText(title);
        }
        setup(header, content, prefKey, prefs);
    }
}
