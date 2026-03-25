package com.atakmap.android.weather.overlay.heatmap;

import android.content.Context;
import android.graphics.Color;
import android.view.MotionEvent;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.widgets.LayoutWidget;
import com.atakmap.android.widgets.LinearLayoutWidget;
import com.atakmap.android.widgets.MapWidget;
import com.atakmap.android.widgets.TextWidget;
import com.atakmap.android.widgets.WidgetsLayer;
import com.atakmap.coremap.log.Log;

import java.util.Locale;

/**
 * Floating heatmap colour-scale legend on the WIDGETS RenderStack.
 *
 * <p>Follows the same pattern as {@code WindHudWidget} — uses ATAK's
 * {@link WidgetsLayer} with {@link LinearLayoutWidget} and {@link TextWidget}
 * children. Positioned at the bottom-left of the map view.</p>
 *
 * <h3>Layout</h3>
 * <pre>
 *   ┌─────────────────────────────┐
 *   │ Temperature (°C)          × │  ← header (draggable + close)
 *   │ -20  ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓  40 │  ← gradient bar (row of colored TextWidgets)
 *   └─────────────────────────────┘
 * </pre>
 *
 * <p>The gradient bar uses a row of narrow coloured {@link TextWidget}s
 * (each 1-character wide with a background colour) to approximate a
 * continuous gradient. This avoids custom GL rendering.</p>
 */
public class HeatmapLegendWidget {

    private static final String TAG = "HeatmapLegend";

    // ── Dimensions ────────────────────────────────────────────────────────────
    private static final int PANEL_W  = 260;
    private static final int ROW_H    = 18;
    private static final int BAR_COLS = 24;  // number of colour cells

    // ── Colours ───────────────────────────────────────────────────────────────
    private static final int COLOR_PANEL  = 0xDD161B22;
    private static final int COLOR_HEADER = 0xDD21262D;
    private static final int COLOR_TEXT   = 0xFFC9D1D9;
    private static final int COLOR_MUTED  = 0xFF6E7681;

    // ── ATAK widget tree ──────────────────────────────────────────────────────
    private final MapView     mapView;
    private final LayoutWidget root;
    private final LinearLayoutWidget headerRow;
    private final TextWidget   titleText;
    private final LinearLayoutWidget barRow;
    private final TextWidget   minLabel;
    private final TextWidget   maxLabel;
    private final LayoutWidget[] barCells = new LayoutWidget[BAR_COLS];
    private final WidgetsLayer widgetsLayer;

    // ── Drag state ────────────────────────────────────────────────────────────
    private float panelX, panelY;
    private float dragStartX, dragStartY, dragStartPanelX, dragStartPanelY;

    // ── State ─────────────────────────────────────────────────────────────────
    private boolean attached = false;
    private boolean visible  = false;
    private String  currentParam = "temperature_2m";

    // ═══════════════════════════════════════════════════════════════════════════

    public HeatmapLegendWidget(MapView mapView, Context pluginContext) {
        this.mapView = mapView;

        // ── Root container ─────────────────────────────────────────────────
        root = new LayoutWidget();

        LayoutWidget panel = new LayoutWidget();
        panel.setBackingColor(COLOR_PANEL);
        panel.setWidth(PANEL_W);

        // ── Header row: title + close ──────────────────────────────────────
        headerRow = new LinearLayoutWidget();
        headerRow.setOrientation(LinearLayoutWidget.HORIZONTAL);
        headerRow.setBackingColor(COLOR_HEADER);
        headerRow.setWidth(PANEL_W);
        headerRow.setHeight(ROW_H + 4);

        titleText = makeText("Temperature (\u00B0C)", 10, COLOR_TEXT);
        headerRow.addWidget(titleText);

        // Spacer
        TextWidget spacer = makeText("", 10, Color.TRANSPARENT);
        spacer.setWidth(PANEL_W - 160);
        headerRow.addWidget(spacer);

        // Close button
        TextWidget closeBtn = makeText(" \u00D7 ", 12, 0xFFFF6B6B);
        closeBtn.addOnClickListener(new MapWidget.OnClickListener() {
            @Override public void onMapWidgetClick(MapWidget w, MotionEvent e) {
                setVisible(false);
            }
        });
        headerRow.addWidget(closeBtn);

        // Drag
        headerRow.addOnMoveListener(new MapWidget.OnMoveListener() {
            @Override public boolean onMapWidgetMove(MapWidget w, MotionEvent event) {
                handleDrag(event);
                return true;
            }
        });
        headerRow.addOnPressListener(new MapWidget.OnPressListener() {
            @Override public void onMapWidgetPress(MapWidget w, MotionEvent event) {
                dragStartX = event.getX();
                dragStartY = event.getY();
                dragStartPanelX = panelX;
                dragStartPanelY = panelY;
            }
        });

        // ── Label + bar row ────────────────────────────────────────────────
        LinearLayoutWidget labelBarRow = new LinearLayoutWidget();
        labelBarRow.setOrientation(LinearLayoutWidget.HORIZONTAL);
        labelBarRow.setWidth(PANEL_W);
        labelBarRow.setHeight(ROW_H);

        minLabel = makeText("-20", 8, COLOR_MUTED);
        minLabel.setWidth(32);
        labelBarRow.addWidget(minLabel);

        // Colour bar cells
        barRow = new LinearLayoutWidget();
        barRow.setOrientation(LinearLayoutWidget.HORIZONTAL);
        int cellW = (PANEL_W - 70) / BAR_COLS;
        for (int i = 0; i < BAR_COLS; i++) {
            barCells[i] = new LayoutWidget();
            barCells[i].setWidth(cellW);
            barCells[i].setHeight(ROW_H - 4);
            barCells[i].setBackingColor(Color.GRAY);
            barRow.addWidget(barCells[i]);
        }
        labelBarRow.addWidget(barRow);

        maxLabel = makeText("40", 8, COLOR_MUTED);
        maxLabel.setWidth(38);
        labelBarRow.addWidget(maxLabel);

        // ── Assemble ───────────────────────────────────────────────────────
        LinearLayoutWidget stack = new LinearLayoutWidget();
        stack.setOrientation(LinearLayoutWidget.VERTICAL);
        stack.addWidget(headerRow);
        stack.addWidget(labelBarRow);

        panel.addWidget(stack);
        root.addWidget(panel);

        widgetsLayer = new WidgetsLayer("WeatherHeatmapLegend", root);

        // Initial gradient
        applyParameter("temperature_2m");
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    public void attach() {
        if (attached) return;
        attached = true;
        positionPanel();
        if (visible) {
            mapView.addLayer(MapView.RenderStack.WIDGETS, widgetsLayer);
        }
    }

    public void detach() {
        if (!attached) return;
        attached = false;
        try {
            mapView.removeLayer(MapView.RenderStack.WIDGETS, widgetsLayer);
        } catch (Exception ignored) {}
    }

    public void setVisible(boolean v) {
        if (this.visible == v) return;
        this.visible = v;
        if (!attached) return;
        if (v) {
            mapView.addLayer(MapView.RenderStack.WIDGETS, widgetsLayer);
        } else {
            try { mapView.removeLayer(MapView.RenderStack.WIDGETS, widgetsLayer); }
            catch (Exception ignored) {}
        }
    }

    public boolean isVisible() { return visible; }

    /**
     * Update the legend for a new parameter.
     */
    public void applyParameter(String paramKey) {
        this.currentParam = paramKey;
        ColourScale scale = ColourScale.forParameter(paramKey);
        double[] vals  = scale.getValues();
        int[]    cols  = scale.getColors();

        // Update title
        String unit;
        switch (paramKey) {
            case "temperature_2m":       unit = "\u00B0C"; break;
            case "wind_speed_10m":       unit = "m/s";     break;
            case "relative_humidity_2m": unit = "%";       break;
            case "surface_pressure":     unit = "hPa";     break;
            default:                     unit = "";        break;
        }
        String displayName = paramKey.replace("_2m", "").replace("_10m", "")
                .replace("surface_", "").replace("_", " ");
        displayName = Character.toUpperCase(displayName.charAt(0)) + displayName.substring(1);
        if (titleText != null) titleText.setText(displayName + " (" + unit + ")");

        // Update min/max labels
        if (minLabel != null) minLabel.setText(formatVal(vals[0]));
        if (maxLabel != null) maxLabel.setText(formatVal(vals[vals.length - 1]) + unit);

        // Update colour cells
        double minV = vals[0];
        double maxV = vals[vals.length - 1];
        for (int i = 0; i < BAR_COLS; i++) {
            double t = minV + (maxV - minV) * ((double) i / (BAR_COLS - 1));
            barCells[i].setBackingColor(scale.getColor(t));
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private void positionPanel() {
        panelX = 10;
        panelY = mapView.getHeight() - 80;
        root.setPoint(panelX, panelY);
    }

    private void handleDrag(MotionEvent event) {
        float dx = event.getX() - dragStartX;
        float dy = event.getY() - dragStartY;
        panelX = dragStartPanelX + dx;
        panelY = dragStartPanelY + dy;
        root.setPoint(panelX, panelY);
    }

    private static TextWidget makeText(String text, int sizeSp, int color) {
        TextWidget tw = new TextWidget(text, 2);
        tw.setColor(color);
        return tw;
    }

    private static String formatVal(double v) {
        if (Math.abs(v) >= 100) return String.format(Locale.US, "%.0f", v);
        if (Math.abs(v) >= 10)  return String.format(Locale.US, "%.0f", v);
        return String.format(Locale.US, "%.1f", v);
    }
}
