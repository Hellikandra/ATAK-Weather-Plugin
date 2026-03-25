package com.atakmap.android.weather.overlay.weather;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;

import com.atakmap.android.maps.MapTextFormat;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.util.ThemeManager;
import com.atakmap.android.weather.util.WeatherUnitConverter;
import com.atakmap.android.widgets.LayoutWidget;
import com.atakmap.android.widgets.LinearLayoutWidget;
import com.atakmap.android.widgets.TextWidget;
import com.atakmap.android.widgets.WidgetsLayer;
import com.atakmap.map.AtakMapView;
import com.atakmap.coremap.log.Log;

import java.util.List;

/**
 * WeatherBitmapWidget — advanced bitmap-rendered weather widget.
 *
 * <h3>Sprint 13 — S13.5 (F22-B)</h3>
 * Renders weather data as a circular bitmap using the Android Canvas API.
 * Displayed as a widget on the ATAK map.
 *
 * <h3>Layout</h3>
 * <pre>
 *   ┌─────────────────────────────────────────────────┐
 *   │               [outer ring: 12h forecast]         │
 *   │    ┌───────────────────────────────────────┐     │
 *   │    │   [inner ring: wind/vis]              │     │
 *   │    │     ┌─────────────────────┐           │     │
 *   │    │     │  23.4°C             │           │     │
 *   │    │     │  ☀ Clear            │           │     │
 *   │    │     │  GREEN              │           │     │
 *   │    │     └─────────────────────┘           │     │
 *   │    └───────────────────────────────────────┘     │
 *   │         [bottom strip: 24h temp sparkline]       │
 *   └─────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>Elements</h3>
 * <ol>
 *   <li>Background: semi-transparent dark circle</li>
 *   <li>Centre: current temp (large), weather description, tactical badge</li>
 *   <li>Inner ring: wind direction arrow, speed, visibility</li>
 *   <li>Outer ring: 12 forecast hour segments (colour-coded by tactical condition)</li>
 *   <li>Bottom strip: mini 24h temperature sparkline</li>
 * </ol>
 *
 * <h3>Lifecycle</h3>
 * Created in {@code WeatherMapComponent} when "wx_widget_style" preference is "bitmap".
 * Attached/detached alongside {@link WeatherHudWidget}.
 */
public class WeatherBitmapWidget implements AtakMapView.OnMapViewResizedListener {

    private static final String TAG = "WeatherBitmapWidget";
    public static final String ACTION_TOGGLE_BITMAP_HUD =
            "com.atakmap.android.weather.TOGGLE_BITMAP_HUD";

    // ── Constants ─────────────────────────────────────────────────────────────

    /** Widget size in density-independent pixels. */
    private static final int WIDGET_SIZE_DP = 200;

    /** Minimum paint stroke width. */
    private static final float STROKE_MIN = 2f;

    // ── Preference keys ───────────────────────────────────────────────────────

    public static final String PREF_KEY_STYLE = "wx_widget_style";
    public static final String STYLE_TEXT = "text";
    public static final String STYLE_BITMAP = "bitmap";

    private static final String PREF_FILE = "weather_bitmap_hud_prefs";
    private static final String PREF_VIS = "bitmap_hud_visible";

    // ── Fields ────────────────────────────────────────────────────────────────

    private final MapView mapView;
    private final Context context;
    private final SharedPreferences prefs;
    private final float density;
    private final int widgetSizePx;

    // ── HUD management prefs (written by DDR wireHudManagement) ──────────────
    private SharedPreferences hudPrefs;
    private SharedPreferences.OnSharedPreferenceChangeListener hudPrefListener;

    private LayoutWidget root;
    private LinearLayoutWidget panelStack;
    private TextWidget tempText;
    private TextWidget condText;
    private TextWidget tacText;
    private TextWidget windVisText;
    private TextWidget forecastRing;
    private TextWidget sparkText;
    private WidgetsLayer widgetsLayer;

    private boolean attached = false;
    private boolean visible;

    // Cached data
    private WeatherModel lastWeather;
    private List<HourlyEntryModel> lastHourly;

    // (Canvas-based rendering replaced by TextWidget approach for ATAK SDK compatibility)

    // ── Constructor ───────────────────────────────────────────────────────────

    public WeatherBitmapWidget(MapView mapView, Context context) {
        this.mapView = mapView;
        this.context = context;
        this.prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        this.visible = prefs.getBoolean(PREF_VIS, true);
        this.density = context.getResources().getDisplayMetrics().density;
        this.widgetSizePx = (int) (WIDGET_SIZE_DP * density);
    }

    // ── Public lifecycle ──────────────────────────────────────────────────────

    public void attach() {
        if (attached) return;
        attached = true;

        // ── Build widget tree (same pattern as WeatherHudWidget) ────
        root = new LayoutWidget();

        panelStack = new LinearLayoutWidget();
        panelStack.setOrientation(LinearLayoutWidget.VERTICAL);
        panelStack.setBackingColor(Color.argb(200, 18, 18, 22));
        panelStack.setWidth(220f);

        MapTextFormat monoS = new MapTextFormat(Typeface.MONOSPACE, 16);
        MapTextFormat monoM = new MapTextFormat(Typeface.MONOSPACE, 18);
        MapTextFormat monoL = new MapTextFormat(Typeface.MONOSPACE, 24);

        // Row 1: forecast ring summary (12h colour bar as text)
        forecastRing = new TextWidget("", monoS, true);
        forecastRing.setColor(Color.argb(180, 150, 150, 160));
        panelStack.addWidget(forecastRing);

        // Row 2: temperature (large, centre)
        tempText = new TextWidget("--.-", monoL, true);
        tempText.setColor(Color.argb(230, 200, 200, 210));
        panelStack.addWidget(tempText);

        // Row 3: condition + direction
        condText = new TextWidget("", monoS, true);
        condText.setColor(Color.argb(180, 150, 150, 160));
        panelStack.addWidget(condText);

        // Row 4: tactical badge
        tacText = new TextWidget("---", monoM, true);
        tacText.setColor(Color.GRAY);
        panelStack.addWidget(tacText);

        // Row 5: wind + visibility
        windVisText = new TextWidget("", monoS, true);
        windVisText.setColor(Color.argb(230, 200, 200, 210));
        panelStack.addWidget(windVisText);

        // Row 6: sparkline approximation
        sparkText = new TextWidget("", monoS, true);
        sparkText.setColor(Color.argb(200, 88, 166, 255));
        panelStack.addWidget(sparkText);

        root.addWidget(panelStack);
        widgetsLayer = new WidgetsLayer("WeatherBitmapHUD", root);

        // Position in top-right area (offset from WeatherHudWidget which is top-left)
        float margin = 16 * density;
        root.setPoint(mapView.getWidth() - 240 * density, margin);

        mapView.addOnMapViewResizedListener(this);
        if (visible) {
            mapView.addLayer(MapView.RenderStack.WIDGETS, widgetsLayer);
        }

        // Render initial placeholder
        renderAndApply();

        // ── Register HUD management prefs listener ───────────────────────────
        hudPrefs = mapView.getContext().getSharedPreferences("WeatherToolPrefs", Context.MODE_PRIVATE);
        hudPrefListener = (sp, key) -> {
            if (key == null) return;
            switch (key) {
                case "wx_hud_bitmap_visible":
                    applyHudVisibility(sp.getBoolean(key, true));
                    break;
                case "wx_hud_bitmap_position":
                    applyHudPosition(sp.getString(key, "TR"));
                    break;
                case "wx_hud_bitmap_opacity":
                    applyHudOpacity(sp.getInt(key, 100));
                    break;
            }
        };
        hudPrefs.registerOnSharedPreferenceChangeListener(hudPrefListener);
        // Apply initial values from HUD management prefs
        applyHudVisibility(hudPrefs.getBoolean("wx_hud_bitmap_visible", true));
        applyHudPosition(hudPrefs.getString("wx_hud_bitmap_position", "TR"));
        applyHudOpacity(hudPrefs.getInt("wx_hud_bitmap_opacity", 100));

        Log.d(TAG, "WeatherBitmapWidget attached");
    }

    public void detach() {
        if (!attached) return;
        attached = false;

        if (hudPrefs != null && hudPrefListener != null) {
            hudPrefs.unregisterOnSharedPreferenceChangeListener(hudPrefListener);
        }

        mapView.removeOnMapViewResizedListener(this);
        mapView.removeLayer(MapView.RenderStack.WIDGETS, widgetsLayer);

        Log.d(TAG, "WeatherBitmapWidget detached");
    }

    // ── Visibility ────────────────────────────────────────────────────────────

    public void setVisible(boolean show) {
        visible = show;
        try {
            if (widgetsLayer == null) {
                Log.w(TAG, "widgetsLayer is null — cannot toggle visibility");
                return;
            }
            if (show) {
                mapView.addLayer(MapView.RenderStack.WIDGETS, widgetsLayer);
            } else {
                mapView.removeLayer(MapView.RenderStack.WIDGETS, widgetsLayer);
            }
        } catch (Exception e) {
            Log.e(TAG, "BitmapWidget visibility toggle failed: " + e.getMessage());
            visible = false;
        }
        prefs.edit().putBoolean(PREF_VIS, show).apply();
    }

    public boolean isVisible() { return visible; }

    // ── Data binding ──────────────────────────────────────────────────────────

    /**
     * Update the widget with fresh weather data.  Thread-safe (posts to main thread).
     *
     * @param current current weather model
     * @param hourly  hourly forecast entries (first 24 used for ring + sparkline)
     */
    public void updateWeather(final WeatherModel current,
                              final List<HourlyEntryModel> hourly) {
        this.lastWeather = current;
        this.lastHourly = hourly;
        mapView.post(this::renderAndApply);
    }

    /** Re-render with cached data (called when unit/theme changes). */
    public void refreshDisplay() {
        if (lastWeather != null) {
            mapView.post(this::renderAndApply);
        }
    }

    // ── AtakMapView.OnMapViewResizedListener ──────────────────────────────────

    @Override
    public void onMapViewResized(AtakMapView view) {
        if (root != null) {
            float x = root.getPointX();
            float y = root.getPointY();
            float maxX = view.getWidth() - 240 * density;
            float maxY = view.getHeight() - 200 * density;
            if (x > maxX || y > maxY) {
                root.setPoint(Math.min(x, Math.max(0, maxX)),
                        Math.min(y, Math.max(0, maxY)));
            }
        }
    }

    // ── HUD management controls (driven by SharedPreferences) ─────────────────

    private void applyHudVisibility(boolean show) {
        setVisible(show);
    }

    private void applyHudPosition(String pos) {
        if (pos == null) pos = "TR";
        float margin = 16 * density;
        float w = mapView.getWidth();
        float h = mapView.getHeight();
        float panelW = 240 * density;
        float panelH = 200 * density;
        float x, y;
        switch (pos) {
            case "TL": x = margin; y = margin; break;
            case "BL": x = margin; y = h - panelH - margin; break;
            case "BR": x = w - panelW; y = h - panelH - margin; break;
            default: /* TR */ x = w - panelW; y = margin; break;
        }
        if (root != null) {
            root.setPoint(x, y);
        }
    }

    private void applyHudOpacity(int percent) {
        int alpha = (int) (percent * 2.55f); // 0–255
        if (panelStack != null) {
            panelStack.setBackingColor(Color.argb(alpha, 18, 18, 22));
        }
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private void renderAndApply() {
        if (tempText == null) return;

        if (lastWeather == null) {
            tempText.setText("--.-");
            condText.setText("");
            tacText.setText("---");
            tacText.setColor(Color.GRAY);
            windVisText.setText("");
            forecastRing.setText("No weather data");
            sparkText.setText("");
            return;
        }

        // Temperature (large display)
        double avgTemp = (lastWeather.getTemperatureMin() + lastWeather.getTemperatureMax()) / 2.0;
        tempText.setText(WeatherUnitConverter.fmtTemp(avgTemp));

        // Wind direction
        String cardinal = WeatherUnitConverter.degreesToCardinal(lastWeather.getWindDirection());
        condText.setText(cardinal);

        // Tactical badge
        String tac = lastWeather.tacticalCondition();
        tacText.setText(tac);
        tacText.setColor(getTacticalColor(tac));

        // Wind + Visibility
        String wind = WeatherUnitConverter.fmtWind(lastWeather.getWindSpeed());
        String vis = WeatherUnitConverter.fmtVisibility(lastWeather.getVisibility());
        windVisText.setText(wind + "  |  " + vis);

        // Forecast ring: text-based 12h tactical bar
        buildForecastRing();

        // Sparkline: text-based 24h temperature trend
        buildSparkline();
    }

    private void buildForecastRing() {
        if (lastHourly == null || lastHourly.isEmpty()) {
            forecastRing.setText("");
            return;
        }

        int count = Math.min(12, lastHourly.size());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            String tac = lastHourly.get(i).tacticalCondition();
            if ("GREEN".equals(tac))      sb.append("\u2588"); // full block
            else if ("AMBER".equals(tac)) sb.append("\u2593"); // dark shade
            else if ("RED".equals(tac))   sb.append("\u2591"); // light shade
            else                          sb.append("\u2592"); // medium shade
        }
        forecastRing.setText(sb.toString());
    }

    private void buildSparkline() {
        if (lastHourly == null || lastHourly.size() < 2) {
            sparkText.setText("");
            return;
        }

        int count = Math.min(24, lastHourly.size());
        // Unicode block sparkline characters (ascending height)
        char[] blocks = {'\u2581', '\u2582', '\u2583', '\u2584',
                         '\u2585', '\u2586', '\u2587', '\u2588'};

        double minTemp = Double.MAX_VALUE, maxTemp = -Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            double t = lastHourly.get(i).getTemperature();
            if (t < minTemp) minTemp = t;
            if (t > maxTemp) maxTemp = t;
        }

        double range = maxTemp - minTemp;
        if (range < 0.1) range = 1.0;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            double t = lastHourly.get(i).getTemperature();
            int idx = (int) (((t - minTemp) / range) * 7);
            idx = Math.max(0, Math.min(7, idx));
            sb.append(blocks[idx]);
        }
        sparkText.setText(sb.toString());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int getTacticalColor(String tac) {
        if (tac == null) return Color.GRAY;
        switch (tac) {
            case "GREEN": return ThemeManager.getColor("green");
            case "AMBER": return ThemeManager.getColor("amber");
            case "RED":   return ThemeManager.getColor("red");
            default:      return Color.GRAY;
        }
    }
}
