package com.atakmap.android.weather.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.atakmap.coremap.log.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * ThemeManager — Dark / Light / NVG theme support for the WeatherTool plugin.
 *
 * <h3>Sprint 13 — S13.3 (F19)</h3>
 * Provides three visual themes:
 * <ul>
 *   <li><b>DARK</b>  — current default (blue-grey tones, white text)</li>
 *   <li><b>LIGHT</b> — white background, dark text (for daytime use)</li>
 *   <li><b>NVG</b>   — pure green-on-black for night vision goggle compatibility</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>
 *   ThemeManager.setTheme(ThemeManager.Theme.NVG);
 *   ThemeManager.applyToView(rootView);
 *   int bg = ThemeManager.getColor("background");
 * </pre>
 *
 * <h3>Element names</h3>
 * <table>
 *   <tr><th>Element</th><th>Description</th></tr>
 *   <tr><td>background</td><td>Main background colour</td></tr>
 *   <tr><td>surface</td><td>Card / panel background</td></tr>
 *   <tr><td>text</td><td>Primary text colour</td></tr>
 *   <tr><td>muted</td><td>Secondary / dimmed text</td></tr>
 *   <tr><td>accent</td><td>Links, highlights, active elements</td></tr>
 *   <tr><td>green</td><td>Tactical GREEN indicator</td></tr>
 *   <tr><td>amber</td><td>Tactical AMBER indicator</td></tr>
 *   <tr><td>red</td><td>Tactical RED indicator</td></tr>
 *   <tr><td>border</td><td>Divider / border colour</td></tr>
 *   <tr><td>chart_bg</td><td>Chart background</td></tr>
 *   <tr><td>chart_line</td><td>Chart default line colour</td></tr>
 * </table>
 */
public class ThemeManager {

    private static final String TAG = "ThemeManager";

    /** SharedPreferences key for theme selection. */
    public static final String PREF_KEY = "wx_theme";

    /** Preference file name (shared with other weather prefs). */
    private static final String PREFS_NAME = "WeatherToolPrefs";

    // ── Theme enum ────────────────────────────────────────────────────────────

    public enum Theme {
        DARK("Dark"),
        LIGHT("Light"),
        NVG("NVG");

        public final String label;
        Theme(String label) { this.label = label; }

        @Override
        public String toString() { return label; }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private static Theme currentTheme = Theme.DARK;

    // ── Colour maps ───────────────────────────────────────────────────────────

    private static final Map<String, Integer> DARK_COLORS  = new HashMap<>();
    private static final Map<String, Integer> LIGHT_COLORS = new HashMap<>();
    private static final Map<String, Integer> NVG_COLORS   = new HashMap<>();

    static {
        // ── DARK theme (current default — blue-grey) ──────────────────────
        DARK_COLORS.put("background",  Color.parseColor("#0d1117"));
        DARK_COLORS.put("surface",     Color.parseColor("#161b22"));
        DARK_COLORS.put("text",        Color.parseColor("#c9d1d9"));
        DARK_COLORS.put("muted",       Color.parseColor("#8b949e"));
        DARK_COLORS.put("accent",      Color.parseColor("#58a6ff"));
        DARK_COLORS.put("green",       Color.parseColor("#3fb950"));
        DARK_COLORS.put("amber",       Color.parseColor("#d29922"));
        DARK_COLORS.put("red",         Color.parseColor("#f85149"));
        DARK_COLORS.put("border",      Color.parseColor("#30363d"));
        DARK_COLORS.put("chart_bg",    Color.parseColor("#0d1117"));
        DARK_COLORS.put("chart_line",  Color.parseColor("#58a6ff"));

        // ── LIGHT theme ───────────────────────────────────────────────────
        LIGHT_COLORS.put("background", Color.parseColor("#ffffff"));
        LIGHT_COLORS.put("surface",    Color.parseColor("#f6f8fa"));
        LIGHT_COLORS.put("text",       Color.parseColor("#24292f"));
        LIGHT_COLORS.put("muted",      Color.parseColor("#57606a"));
        LIGHT_COLORS.put("accent",     Color.parseColor("#0969da"));
        LIGHT_COLORS.put("green",      Color.parseColor("#1a7f37"));
        LIGHT_COLORS.put("amber",      Color.parseColor("#9a6700"));
        LIGHT_COLORS.put("red",        Color.parseColor("#cf222e"));
        LIGHT_COLORS.put("border",     Color.parseColor("#d0d7de"));
        LIGHT_COLORS.put("chart_bg",   Color.parseColor("#ffffff"));
        LIGHT_COLORS.put("chart_line", Color.parseColor("#0969da"));

        // ── NVG theme (green-on-black) ────────────────────────────────────
        NVG_COLORS.put("background",   Color.parseColor("#000000"));
        NVG_COLORS.put("surface",      Color.parseColor("#001100"));
        NVG_COLORS.put("text",         Color.parseColor("#00ff00"));
        NVG_COLORS.put("muted",        Color.parseColor("#009900"));
        NVG_COLORS.put("accent",       Color.parseColor("#00cc00"));
        NVG_COLORS.put("green",        Color.parseColor("#00ff00"));
        NVG_COLORS.put("amber",        Color.parseColor("#88cc00"));
        NVG_COLORS.put("red",          Color.parseColor("#ff3300"));
        NVG_COLORS.put("border",       Color.parseColor("#003300"));
        NVG_COLORS.put("chart_bg",     Color.parseColor("#000000"));
        NVG_COLORS.put("chart_line",   Color.parseColor("#00ff00"));
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Get the current theme. */
    public static Theme getTheme() {
        return currentTheme;
    }

    /** Set the current theme globally. */
    public static void setTheme(Theme theme) {
        if (theme != null) {
            currentTheme = theme;
            Log.d(TAG, "Theme set to: " + theme.label);
        }
    }

    /**
     * Detect ATAK theme preference and set accordingly.
     * Falls back to DARK if detection fails.
     *
     * @param context Android context
     * @return the detected/set theme
     */
    public static Theme detectAtakTheme(Context context) {
        if (context == null) return currentTheme;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME,
                    Context.MODE_PRIVATE);
            String saved = prefs.getString(PREF_KEY, null);
            if (saved != null) {
                try {
                    Theme t = Theme.valueOf(saved);
                    setTheme(t);
                    return t;
                } catch (IllegalArgumentException ignored) {
                    // fall through to default
                }
            }

            // Check ATAK global preferences for night vision mode
            SharedPreferences atakPrefs = context.getSharedPreferences(
                    "cot_preference", Context.MODE_PRIVATE);
            if (atakPrefs != null) {
                boolean nvg = atakPrefs.getBoolean("nvg_mode", false);
                if (nvg) {
                    setTheme(Theme.NVG);
                    return Theme.NVG;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "detectAtakTheme failed: " + e.getMessage());
        }
        // Default to DARK
        return currentTheme;
    }

    /**
     * Save the theme preference.
     *
     * @param context Android context
     * @param theme theme to save
     */
    public static void saveTheme(Context context, Theme theme) {
        if (context == null || theme == null) return;
        setTheme(theme);
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_KEY, theme.name())
                .apply();
    }

    /**
     * Get the colour for a named element in the current theme.
     *
     * @param element one of: background, surface, text, muted, accent,
     *                green, amber, red, border, chart_bg, chart_line
     * @return ARGB colour int
     */
    public static int getColor(String element) {
        Map<String, Integer> map;
        switch (currentTheme) {
            case LIGHT: map = LIGHT_COLORS; break;
            case NVG:   map = NVG_COLORS;   break;
            case DARK:
            default:    map = DARK_COLORS;  break;
        }
        Integer c = map.get(element);
        return c != null ? c : Color.MAGENTA; // magenta = debug fallback
    }

    /**
     * Apply the current theme to a view hierarchy.
     * Sets background colours on ViewGroups and text colours on TextViews.
     *
     * @param root the root view to theme
     */
    public static void applyToView(View root) {
        if (root == null) return;

        int bgColor = getColor("background");
        int surfaceColor = getColor("surface");
        int textColor = getColor("text");
        int mutedColor = getColor("muted");

        applyRecursive(root, bgColor, surfaceColor, textColor, mutedColor);
    }

    private static void applyRecursive(View view, int bg, int surface, int text, int muted) {
        if (view == null) return;

        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            android.graphics.drawable.Drawable d = vg.getBackground();
            // Set background on containers that have no custom drawable OR
            // already have a plain ColorDrawable (from a previous theme apply).
            // Skip views with @drawable/image_view_bg or other non-color drawables.
            if (d == null || d instanceof android.graphics.drawable.ColorDrawable) {
                vg.setBackgroundColor(bg);
            }
            for (int i = 0; i < vg.getChildCount(); i++) {
                applyRecursive(vg.getChildAt(i), bg, surface, text, muted);
            }
        }

        // Apply text colour
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            float alpha = tv.getAlpha();
            // Muted text typically has alpha < 1.0 in the layout
            if (alpha < 0.85f) {
                tv.setTextColor(muted);
            } else {
                tv.setTextColor(text);
            }
        }
    }

    // ── Convenience: staleness colour ─────────────────────────────────────────

    /**
     * Get the colour for a staleness level string.
     *
     * @param level "fresh", "aging", or "stale"
     * @return themed colour int
     */
    public static int getStalenessColor(String level) {
        if (level == null) return getColor("muted");
        switch (level) {
            case "fresh": return getColor("green");
            case "aging": return getColor("amber");
            case "stale": return getColor("red");
            default:      return getColor("muted");
        }
    }
}
