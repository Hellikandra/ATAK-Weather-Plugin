package com.atakmap.android.weather.overlay.weather;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.MotionEvent;

import com.atakmap.android.maps.MapTextFormat;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.model.LocationSnapshot;
import com.atakmap.android.weather.util.WeatherUnitConverter;
import com.atakmap.android.weather.util.WeatherConstants;
import com.atakmap.android.widgets.LayoutWidget;
import com.atakmap.android.widgets.LinearLayoutWidget;
import com.atakmap.android.widgets.MapWidget;
import com.atakmap.android.widgets.TextWidget;
import com.atakmap.android.widgets.WidgetsLayer;
import com.atakmap.map.AtakMapView;
import com.atakmap.coremap.log.Log;

/**
 * WeatherHudWidget — compact weather conditions HUD on the ATAK map.
 * Sprint 7 — S7.3 (replaces wind-only WindHudWidget with full weather summary).
 *
 * <h3>Layout</h3>
 * <pre>
 *   ┌──────────────────────────────────────────────────────────────┐
 *   │  [···drag···]  Weather HUD                           [×]    │
 *   │  12.3°C  |  6.2 m/s NW  |  12km  |  GREEN                 │
 *   │  Liège, Belgium • GFS • Updated 2min ago                    │
 *   └──────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>Interactions</h3>
 * <ul>
 *   <li>Drag header to reposition</li>
 *   <li>Tap anywhere opens the DropDown</li>
 *   <li>[×] hides the widget</li>
 * </ul>
 *
 * <h3>Lifecycle</h3>
 * Created and attached in {@code WeatherMapComponent.onCreate()}.
 * Detached in {@code WeatherMapComponent.onDestroyImpl()}.
 */
@SuppressWarnings("deprecation")
public class WeatherHudWidget implements AtakMapView.OnMapViewResizedListener {

    private static final String TAG = "WeatherHudWidget";
    public static final String ACTION_TOGGLE_WEATHER_HUD =
            "com.atakmap.android.weather.TOGGLE_WEATHER_HUD";

    // ── Constants ────────────────────────────────────────────────
    private static final float PANEL_W    = 380f;
    private static final float PANEL_H    = 72f;
    private static final float MARGIN     = 16f;
    private static final float ROW_H      = 22f;
    private static final float PAD        = 8f;
    private static final float TEXT_S     = 18f;
    private static final float TEXT_M     = 20f;

    private static final int COLOR_PANEL  = Color.argb(210, 18, 18, 22);
    private static final int COLOR_HEADER = Color.argb(230, 28, 28, 34);
    private static final int COLOR_CLOSE  = Color.argb(200, 220, 80, 60);
    private static final int COLOR_TEXT   = Color.argb(230, 200, 200, 210);
    private static final int COLOR_MUTED  = Color.argb(180, 150, 150, 160);
    private static final int COLOR_HANDLE = Color.argb(120, 140, 145, 160);
    private static final int COLOR_GREEN  = Color.argb(255, 0, 200, 0);
    private static final int COLOR_AMBER  = Color.argb(255, 255, 170, 0);
    private static final int COLOR_RED    = Color.argb(255, 255, 50, 50);

    private static final String PREF_FILE = "weather_hud_prefs";
    private static final String PREF_X    = "hud_x";
    private static final String PREF_Y    = "hud_y";
    private static final String PREF_VIS  = "hud_visible";

    // ── Fields ───────────────────────────────────────────────────
    private final MapView mapView;
    private final Context context;
    private final SharedPreferences prefs;

    // ── HUD management prefs (written by DDR wireHudManagement) ─
    private SharedPreferences hudPrefs;
    private SharedPreferences.OnSharedPreferenceChangeListener hudPrefListener;

    // ── Widget tree ──────────────────────────────────────────────
    private final LayoutWidget       root;
    private final LinearLayoutWidget panelStack;
    private final LinearLayoutWidget headerRow;
    private final TextWidget         conditionsText;
    private final TextWidget         locationText;
    private final WidgetsLayer       widgetsLayer;

    // ── Drag state ───────────────────────────────────────────────
    private float panelX;
    private float panelY;
    private float dragStartX;
    private float dragStartY;
    private float dragStartPanelX;
    private float dragStartPanelY;

    // ── State ────────────────────────────────────────────────────
    private boolean attached = false;
    private boolean visible;

    // Cached data for re-rendering on unit change
    private WeatherModel lastWeather;
    private LocationSnapshot lastLocation;

    // ── Constructor ──────────────────────────────────────────────

    public WeatherHudWidget(MapView mapView, Context context) {
        this.mapView = mapView;
        this.context = context;
        this.prefs   = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        this.visible = prefs.getBoolean(PREF_VIS, true);

        // ── Root container ───────────────────────────────────────
        root = new LayoutWidget();

        // ── Vertical stack inside root ───────────────────────────
        panelStack = new LinearLayoutWidget();
        panelStack.setOrientation(LinearLayoutWidget.VERTICAL);
        panelStack.setBackingColor(COLOR_PANEL);
        panelStack.setWidth(PANEL_W);

        // ── Header row: drag handle + title + close ──────────────
        headerRow = new LinearLayoutWidget();
        headerRow.setOrientation(LinearLayoutWidget.HORIZONTAL);
        headerRow.setBackingColor(COLOR_HEADER);
        headerRow.setWidth(PANEL_W);
        headerRow.setHeight(ROW_H + PAD);

        TextWidget handleDots = makeText("· · ·  ", TEXT_S, COLOR_HANDLE);
        headerRow.addWidget(handleDots);

        TextWidget headerTitle = makeText("Weather HUD", TEXT_S, COLOR_TEXT);
        headerRow.addWidget(headerTitle);

        // Close / hide button
        TextWidget closeBtn = makeText("  ×", TEXT_M, COLOR_CLOSE);
        closeBtn.addOnClickListener(new MapWidget.OnClickListener() {
            @Override
            public void onMapWidgetClick(MapWidget w, MotionEvent e) {
                setVisible(false);
            }
        });
        headerRow.addWidget(closeBtn);

        // Drag the panel by pressing on the header row
        headerRow.setDragEnabled(true);
        headerRow.addOnMoveListener(new MapWidget.OnMoveListener() {
            @Override
            public boolean onMapWidgetMove(MapWidget w, MotionEvent event) {
                handleDrag(event);
                return true;
            }
        });
        headerRow.addOnPressListener(new MapWidget.OnPressListener() {
            @Override
            public void onMapWidgetPress(MapWidget w, MotionEvent event) {
                dragStartX      = event.getX();
                dragStartY      = event.getY();
                dragStartPanelX = panelX;
                dragStartPanelY = panelY;
            }
        });

        // ── Conditions row ───────────────────────────────────────
        conditionsText = makeText("— | — | — | —", TEXT_S, COLOR_TEXT);

        // ── Location row ─────────────────────────────────────────
        locationText = makeText("Tap refresh to load weather", TEXT_S, COLOR_MUTED);

        // ── Tap anywhere to open dropdown ────────────────────────
        panelStack.addOnClickListener(new MapWidget.OnClickListener() {
            @Override
            public void onMapWidgetClick(MapWidget widget, MotionEvent event) {
                android.content.Intent intent = new android.content.Intent(
                        WeatherConstants.ACTION_SHOW_PLUGIN);
                com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(intent);
            }
        });

        // ── Assemble panel ───────────────────────────────────────
        panelStack.addWidget(headerRow);
        panelStack.addWidget(conditionsText);
        panelStack.addWidget(locationText);

        root.addWidget(panelStack);
        widgetsLayer = new WidgetsLayer("WeatherHUD", root);
    }

    // ── Public lifecycle ─────────────────────────────────────────

    public void attach() {
        if (attached) return;
        attached = true;

        positionPanel();
        mapView.addOnMapViewResizedListener(this);
        mapView.addLayer(MapView.RenderStack.WIDGETS, widgetsLayer);

        if (!visible) {
            mapView.removeLayer(MapView.RenderStack.WIDGETS, widgetsLayer);
        }

        // ── Register HUD management prefs listener ─────────────
        hudPrefs = mapView.getContext().getSharedPreferences("WeatherToolPrefs", Context.MODE_PRIVATE);
        hudPrefListener = (sp, key) -> {
            if (key == null) return;
            switch (key) {
                case "wx_hud_weather_visible":
                    applyHudVisibility(sp.getBoolean(key, true));
                    break;
                case "wx_hud_weather_position":
                    applyHudPosition(sp.getString(key, "TL"));
                    break;
                case "wx_hud_weather_opacity":
                    applyHudOpacity(sp.getInt(key, 100));
                    break;
            }
        };
        hudPrefs.registerOnSharedPreferenceChangeListener(hudPrefListener);
        // Apply initial values from HUD management prefs
        applyHudVisibility(hudPrefs.getBoolean("wx_hud_weather_visible", true));
        applyHudPosition(hudPrefs.getString("wx_hud_weather_position", "TL"));
        applyHudOpacity(hudPrefs.getInt("wx_hud_weather_opacity", 100));

        Log.d(TAG, "WeatherHudWidget attached");
    }

    public void detach() {
        if (!attached) return;
        attached = false;

        if (hudPrefs != null && hudPrefListener != null) {
            hudPrefs.unregisterOnSharedPreferenceChangeListener(hudPrefListener);
        }

        mapView.removeOnMapViewResizedListener(this);
        mapView.removeLayer(MapView.RenderStack.WIDGETS, widgetsLayer);

        Log.d(TAG, "WeatherHudWidget detached");
    }

    // ── Visibility ───────────────────────────────────────────────

    public void setVisible(boolean show) {
        visible = show;
        if (show) {
            mapView.addLayer(MapView.RenderStack.WIDGETS, widgetsLayer);
            positionPanel();
        } else {
            mapView.removeLayer(MapView.RenderStack.WIDGETS, widgetsLayer);
        }
        prefs.edit().putBoolean(PREF_VIS, show).apply();
    }

    public boolean isVisible() { return visible; }

    // ── Data binding ─────────────────────────────────────────────

    /**
     * Update the widget with fresh weather data. Thread-safe (posts to main thread).
     */
    public void updateWeather(final WeatherModel weather, final LocationSnapshot location) {
        this.lastWeather  = weather;
        this.lastLocation = location;

        mapView.post(new Runnable() {
            @Override
            public void run() {
                if (conditionsText == null) return;

                String temp = WeatherUnitConverter.fmtTemp(
                        (weather.getTemperatureMin() + weather.getTemperatureMax()) / 2.0);
                String wind = WeatherUnitConverter.fmtWind(weather.getWindSpeed())
                        + " " + WeatherUnitConverter.degreesToCardinal(weather.getWindDirection());
                String vis  = WeatherUnitConverter.fmtVisibility(weather.getVisibility());
                String tac  = weather.tacticalCondition();

                conditionsText.setText(temp + "  |  " + wind + "  |  " + vis + "  |  " + tac);

                // Color the entire conditions row based on tactical condition
                int tacColor = COLOR_TEXT;
                if ("GREEN".equals(tac))      tacColor = COLOR_GREEN;
                else if ("AMBER".equals(tac)) tacColor = COLOR_AMBER;
                else if ("RED".equals(tac))   tacColor = COLOR_RED;
                conditionsText.setColor(tacColor);

                if (location != null && locationText != null) {
                    locationText.setText(location.getDisplayName());
                }
            }
        });
    }

    /**
     * Re-render with cached data (called when unit system changes).
     */
    public void refreshDisplay() {
        if (lastWeather != null) {
            updateWeather(lastWeather, lastLocation);
        }
    }

    // ── AtakMapView.OnMapViewResizedListener ─────────────────────

    @Override
    public void onMapViewResized(AtakMapView view) {
        if (panelX == 0 && panelY == 0) positionPanel();
        else repositionIfOffScreen();
    }

    // ── Drag handling ────────────────────────────────────────────

    private void handleDrag(MotionEvent event) {
        float dx = event.getX() - dragStartX;
        float dy = event.getY() - dragStartY;
        float newX = dragStartPanelX + dx;
        float newY = dragStartPanelY + dy;

        // Clamp to screen bounds
        newX = Math.max(0, Math.min(mapView.getWidth()  - PANEL_W, newX));
        newY = Math.max(0, Math.min(mapView.getHeight() - PANEL_H, newY));

        panelX = newX;
        panelY = newY;
        root.setPoint(panelX, panelY);
        savePosition(panelX, panelY);
    }

    private void positionPanel() {
        float savedX = prefs.getFloat(PREF_X, -1f);
        float savedY = prefs.getFloat(PREF_Y, -1f);
        if (savedX >= 0 && savedY >= 0) {
            panelX = savedX;
            panelY = savedY;
        } else {
            panelX = MARGIN;
            panelY = MARGIN;
        }
        root.setPoint(panelX, panelY);
    }

    private void repositionIfOffScreen() {
        float maxX = mapView.getWidth()  - PANEL_W;
        float maxY = mapView.getHeight() - PANEL_H;
        if (panelX > maxX || panelY > maxY) {
            panelX = Math.min(panelX, maxX);
            panelY = Math.min(panelY, maxY);
            root.setPoint(panelX, panelY);
        }
    }

    // ── HUD management controls (driven by SharedPreferences) ───

    private void applyHudVisibility(boolean show) {
        setVisible(show);
    }

    private void applyHudPosition(String pos) {
        if (pos == null) pos = "TL";
        float w = mapView.getWidth();
        float h = mapView.getHeight();
        switch (pos) {
            case "TR": panelX = w - PANEL_W - MARGIN; panelY = MARGIN; break;
            case "BL": panelX = MARGIN; panelY = h - PANEL_H - MARGIN * 4; break;
            case "BR": panelX = w - PANEL_W - MARGIN; panelY = h - PANEL_H - MARGIN * 4; break;
            default: /* TL */ panelX = MARGIN; panelY = MARGIN; break;
        }
        root.setPoint(panelX, panelY);
        savePosition(panelX, panelY);
    }

    private void applyHudOpacity(int percent) {
        int alpha = (int) (percent * 2.55f); // 0–255
        if (panelStack != null) {
            panelStack.setBackingColor(Color.argb(alpha, 18, 18, 22));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    private void savePosition(float x, float y) {
        prefs.edit().putFloat(PREF_X, x).putFloat(PREF_Y, y).apply();
    }

    private static TextWidget makeText(String text, float size, int color) {
        TextWidget w = new TextWidget(text,
                new MapTextFormat(Typeface.MONOSPACE, (int) size), true);
        w.setColor(color);
        return w;
    }
}
