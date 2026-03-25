package com.atakmap.android.weather.overlay.wind;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.MotionEvent;

import androidx.lifecycle.Observer;

import com.atakmap.android.maps.MapTextFormat;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.weather.domain.model.WindProfileModel;
import com.atakmap.android.weather.presentation.viewmodel.UiState;
import com.atakmap.android.weather.presentation.viewmodel.WindProfileViewModel;
import com.atakmap.android.weather.util.WeatherUiUtils;
import com.atakmap.android.widgets.LayoutWidget;
import com.atakmap.android.widgets.LinearLayoutWidget;
import com.atakmap.android.widgets.MapWidget;
import com.atakmap.android.widgets.SeekBarControl;
import com.atakmap.android.widgets.TextWidget;
import com.atakmap.android.widgets.WidgetsLayer;
import com.atakmap.coremap.log.Log;
import com.atakmap.map.AtakMapView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * WindHudWidget — persistent wind information HUD on {@link MapView.RenderStack#WIDGETS}.
 *
 * <h3>Layout (top → bottom)</h3>
 * <pre>
 *   ┌─ drag handle ···  [48.3,2.1] [51.2,0.4] [+]        [x] ┐
 *   │  Hour  +6h  Fri 14:00   [scrub]                         │
 *   │  ──── seekbar progress track ────────────────           │
 *   │  10m: 12kt NW  |  80m: 18kt WNW  |  500m: 24kt W       │
 *   │  [Draw cones]  [Clear]                  [Hide HUD]      │
 *   └───────────────────────────────────────────────────────  ┘
 * </pre>
 *
 * <h3>1. Dragging</h3>
 * {@code panel.setDragEnabled(true)} + {@link MapWidget.OnMoveListener} tracks drag
 * and calls {@code panel.setPoint()} on every move event. A "···" handle row at
 * the top provides a visual grab target. Last position is remembered across
 * show/hide cycles.
 *
 * <h3>2. Slot tabs</h3>
 * Each slot gets an individual {@link TextWidget} with its own
 * {@link MapWidget.OnClickListener}. Active slot uses a highlighted backing color.
 *
 * <h3>3. Hour seekbar</h3>
 * Uses {@link SeekBarControl#show(SeekBarControl.Subject, long)} — ATAK's built-in
 * on-map seekbar (same as the 3D tilt control). A "scrub" {@link TextWidget} button
 * opens it. {@link SeekBarControl.Subject} maps 0–100 to 0–maxHour.
 *
 * <h3>4. Hide/Show</h3>
 * "Hide HUD" broadcasts {@code TOGGLE_WIND_HUD} (handled in WeatherMapComponent).
 * The WIND tab has a matching "Show Wind HUD" button using the same broadcast.
 *
 * <h3>Lifecycle</h3>
 * Attach from {@code WeatherMapComponent.onCreate}, detach from
 * {@code WeatherMapComponent.onDestroyImpl}.
 */
@SuppressWarnings("deprecation")
public class WindHudWidget implements AtakMapView.OnMapViewResizedListener {

    private static final String TAG = "WindHudWidget";

    public static final String ACTION_TOGGLE_HUD = com.atakmap.android.weather.util.WeatherConstants.ACTION_TOGGLE_HUD;

    private static final float PANEL_W  = 380f;
    private static final float PANEL_H  = 112f;
    private static final float MARGIN   = 16f;
    private static final float ROW_H    = 22f;
    private static final float PAD      = 8f;
    private static final float TEXT_S   = 18f;  // small text
    private static final float TEXT_M   = 20f;  // medium text

    private static final int   COLOR_PANEL   = Color.argb(210, 18, 18, 22);
    private static final int   COLOR_HEADER  = Color.argb(255, 28, 28, 34);
    private static final int   COLOR_ACTIVE  = Color.argb(255, 24, 95, 165);
    private static final int   COLOR_BTN     = Color.argb(200, 40, 40, 50);
    private static final int   COLOR_TEXT    = Color.WHITE;
    private static final int   COLOR_MUTED   = Color.argb(180, 160, 165, 180);
    private static final int   COLOR_HANDLE  = Color.argb(120, 140, 145, 160);
    private static final int   COLOR_CLOSE   = Color.argb(200, 220, 80, 60);
    private static final int   COLOR_DRAW    = Color.argb(230, 24, 95, 165);
    private static final int   COLOR_CLEAR   = Color.argb(200, 180, 60, 40);

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final MapView              mapView;
    private final Context              pluginContext;
    private final WindProfileViewModel windViewModel;
    private final WindEffectShape      windEffectShape;

    // ── Widget tree ───────────────────────────────────────────────────────────

    private final LayoutWidget       root;
    private final LayoutWidget       panel;
    private final LinearLayoutWidget headerRow;   // drag handle + slot tabs + close
    private final LinearLayoutWidget slotTabRow;  // slot TextWidgets (rebuilt on slot change)
    private final TextWidget         hourText;
    private final TextWidget         scrubBtn;    // opens SeekBarControl
    private final LinearLayoutWidget seekTrack;   // visual progress bar (two rects)
    private final LayoutWidget       seekFill;    // filled portion (width updated on hour change)
    private final LinearLayoutWidget windRow;     // altitude wind readout
    private final LinearLayoutWidget btnRow;      // Draw / Clear / Hide
    private final WidgetsLayer       widgetsLayer;

    // ── Drag state ────────────────────────────────────────────────────────────

    private float panelX;
    private float panelY;
    private float dragStartX;
    private float dragStartY;
    private float dragStartPanelX;
    private float dragStartPanelY;

    // ── LiveData observers ────────────────────────────────────────────────────

    private final Observer<List<WindProfileViewModel.WindSlot>> obsSlots;
    private final Observer<Integer>                              obsActiveSlot;
    private final Observer<Integer>                              obsHour;
    private final Observer<UiState<List<WindProfileModel>>>     obsProfile;

    // ── State ─────────────────────────────────────────────────────────────────

    private boolean            attached     = false;
    private boolean            visible      = true;
    private List<WindProfileModel> lastProfiles = null;
    private int                maxHourIndex = 167;

    // ── HUD management prefs ────────────────────────────────────────────────
    private SharedPreferences hudPrefs;
    private SharedPreferences.OnSharedPreferenceChangeListener hudPrefListener;

    // ── Constructor ───────────────────────────────────────────────────────────

    public WindHudWidget(MapView mapView,
                         Context pluginContext,
                         WindProfileViewModel windViewModel,
                         WindEffectShape windEffectShape) {
        this.mapView         = mapView;
        this.pluginContext   = pluginContext;
        this.windViewModel   = windViewModel;
        this.windEffectShape = windEffectShape;

        // ── Panel container ───────────────────────────────────────────────────
        root  = new LayoutWidget();
        panel = new LayoutWidget();
        panel.setBackingColor(COLOR_PANEL);
        panel.setWidth(PANEL_W);
        panel.setDragEnabled(true);

        // ── Header row: handle + slot tabs + close ────────────────────────────
        headerRow = new LinearLayoutWidget();
        headerRow.setOrientation(LinearLayoutWidget.HORIZONTAL);
        headerRow.setBackingColor(COLOR_HEADER);
        headerRow.setWidth(PANEL_W);
        headerRow.setHeight(ROW_H + PAD);

        TextWidget handleDots = makeText("· · ·  ", TEXT_S, COLOR_HANDLE);
        headerRow.addWidget(handleDots);

        // Slot tab sub-row (rebuilt in rebuildSlotTabs)
        slotTabRow = new LinearLayoutWidget();
        slotTabRow.setOrientation(LinearLayoutWidget.HORIZONTAL);
        headerRow.addWidget(slotTabRow);

        // "+" add-slot placeholder (informational — actual add is via DDR)
        TextWidget addBtn = makeText("  +  ", TEXT_S, COLOR_MUTED);
        headerRow.addWidget(addBtn);

        // Close / hide button (right-aligned)
        TextWidget closeBtn = makeText("  ×", TEXT_M, COLOR_CLOSE);
        closeBtn.addOnClickListener(new MapWidget.OnClickListener() {
            @Override
            public void onMapWidgetClick(MapWidget w, MotionEvent e) {
                broadcastToggle(false);
            }
        });
        headerRow.addWidget(closeBtn);

        // Drag the panel by pressing on the header row
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
                dragStartX       = event.getX();
                dragStartY       = event.getY();
                dragStartPanelX  = panelX;
                dragStartPanelY  = panelY;
            }
        });

        // ── Hour label row ────────────────────────────────────────────────────
        LinearLayoutWidget hourRow = new LinearLayoutWidget();
        hourRow.setOrientation(LinearLayoutWidget.HORIZONTAL);
        hourRow.setWidth(PANEL_W);

        TextWidget hourLbl = makeText(" Hour ", TEXT_S, COLOR_MUTED);
        hourText = makeText("+0 h  —", TEXT_S, COLOR_TEXT);

        // "Scrub" button opens the native SeekBarControl
        scrubBtn = makeText("  [scrub]", TEXT_S, COLOR_ACTIVE);
        scrubBtn.addOnClickListener(new MapWidget.OnClickListener() {
            @Override
            public void onMapWidgetClick(MapWidget w, MotionEvent e) {
                openSeekBarControl();
            }
        });

        hourRow.addWidget(hourLbl);
        hourRow.addWidget(hourText);
        hourRow.addWidget(scrubBtn);

        // ── Visual seekbar track (two overlapping rects simulating progress) ──
        seekTrack = new LinearLayoutWidget();
        seekTrack.setBackingColor(Color.argb(80, 60, 65, 80));
        seekTrack.setWidth(PANEL_W - 2 * PAD);
        seekTrack.setHeight(4);

        seekFill = new LayoutWidget();
        seekFill.setBackingColor(COLOR_ACTIVE);
        seekFill.setHeight(4);
        seekFill.setWidth(0);  // updated in updateSeekFill()

        seekTrack.addWidget(seekFill);

        // ── Wind readout row ──────────────────────────────────────────────────
        windRow = new LinearLayoutWidget();
        windRow.setOrientation(LinearLayoutWidget.HORIZONTAL);
        windRow.setWidth(PANEL_W);

        // Populated in updateWindReadout()

        // ── Action button row ─────────────────────────────────────────────────
        btnRow = new LinearLayoutWidget();
        btnRow.setOrientation(LinearLayoutWidget.HORIZONTAL);
        btnRow.setWidth(PANEL_W);

        TextWidget btnDraw = makeText(" Draw cones ", TEXT_S, COLOR_TEXT);
        btnDraw.setBackground(COLOR_DRAW);
        btnDraw.addOnClickListener(new MapWidget.OnClickListener() {
            @Override public void onMapWidgetClick(MapWidget w, MotionEvent e) { drawWindEffect(); }
        });

        TextWidget btnClear = makeText("  Clear  ", TEXT_S, COLOR_TEXT);
        btnClear.setBackground(COLOR_CLEAR);
        btnClear.addOnClickListener(new MapWidget.OnClickListener() {
            @Override public void onMapWidgetClick(MapWidget w, MotionEvent e) {
                if (windEffectShape != null) windEffectShape.removeAll();
            }
        });

        TextWidget btnHide = makeText("   Hide HUD", TEXT_S, COLOR_MUTED);
        btnHide.addOnClickListener(new MapWidget.OnClickListener() {
            @Override public void onMapWidgetClick(MapWidget w, MotionEvent e) {
                broadcastToggle(false);
            }
        });

        btnRow.addWidget(btnDraw);
        btnRow.addWidget(btnClear);
        btnRow.addWidget(btnHide);

        // ── Assemble panel ────────────────────────────────────────────────────
        // We use a single LayoutWidget panel and position children manually
        // since LinearLayoutWidget stacks top-to-bottom automatically.
        LinearLayoutWidget panelStack = new LinearLayoutWidget();
        panelStack.setOrientation(LinearLayoutWidget.VERTICAL);
        panelStack.setWidth(PANEL_W);
        panelStack.addWidget(headerRow);
        panelStack.addWidget(hourRow);
        panelStack.addWidget(seekTrack);
        panelStack.addWidget(windRow);
        panelStack.addWidget(btnRow);

        root.addWidget(panelStack);
        widgetsLayer = new WidgetsLayer("WeatherWindHUD", root);

        // ── LiveData observers ─────────────────────────────────────────────────
        obsSlots = slots -> {
            rebuildSlotTabs(slots, windViewModel.getActiveSlotIndex());
        };

        obsActiveSlot = idx -> {
            rebuildSlotTabs(windViewModel.getSlotList(), idx != null ? idx : -1);
            syncProfiles();
        };

        obsHour = hourIdx -> {
            int h = hourIdx != null ? hourIdx : 0;
            updateHourLabel(h);
            updateSeekFill(h);
        };

        obsProfile = state -> {
            if (state.isSuccess() && state.getData() != null) {
                lastProfiles = state.getData();
                maxHourIndex = Math.max(0, lastProfiles.size() - 1);
                updateWindReadout(windViewModel.getCurrentHourIndex());
            }
        };
    }

    // ── Public lifecycle ──────────────────────────────────────────────────────

    public void attach() {
        if (attached) return;
        attached = true;

        positionPanel();
        mapView.addOnMapViewResizedListener(this);
        mapView.addLayer(MapView.RenderStack.WIDGETS, widgetsLayer);

        windViewModel.getSlots().observeForever(obsSlots);
        windViewModel.getActiveSlot().observeForever(obsActiveSlot);
        windViewModel.getSelectedHour().observeForever(obsHour);
        windViewModel.getWindProfile().observeForever(obsProfile);

        // ── HUD management prefs ────────────────────────────────────────
        hudPrefs = mapView.getContext().getSharedPreferences("WeatherToolPrefs", Context.MODE_PRIVATE);
        hudPrefListener = (sp, key) -> {
            if (key == null) return;
            switch (key) {
                case "wx_hud_wind_visible":
                    applyHudVisibility(sp.getBoolean(key, true));
                    break;
                case "wx_hud_wind_position":
                    applyHudPosition(sp.getString(key, "BL"));
                    break;
                case "wx_hud_wind_opacity":
                    applyHudOpacity(sp.getInt(key, 100));
                    break;
            }
        };
        hudPrefs.registerOnSharedPreferenceChangeListener(hudPrefListener);
        applyHudVisibility(hudPrefs.getBoolean("wx_hud_wind_visible", true));
        applyHudPosition(hudPrefs.getString("wx_hud_wind_position", "BL"));
        applyHudOpacity(hudPrefs.getInt("wx_hud_wind_opacity", 100));

        Log.d(TAG, "WindHudWidget attached");
    }

    public void detach() {
        if (!attached) return;
        attached = false;

        if (hudPrefs != null && hudPrefListener != null) {
            hudPrefs.unregisterOnSharedPreferenceChangeListener(hudPrefListener);
        }

        SeekBarControl.dismiss();
        windViewModel.getSlots().removeObserver(obsSlots);
        windViewModel.getActiveSlot().removeObserver(obsActiveSlot);
        windViewModel.getSelectedHour().removeObserver(obsHour);
        windViewModel.getWindProfile().removeObserver(obsProfile);

        mapView.removeOnMapViewResizedListener(this);
        mapView.removeLayer(MapView.RenderStack.WIDGETS, widgetsLayer);

        Log.d(TAG, "WindHudWidget detached");
    }

    public void setVisible(boolean show) {
        visible = show;
        // WidgetsLayer visibility is controlled via the layer's setVisible API.
        // The LayoutWidget API does not surface a clean show/hide for the root —
        // we add/remove the layer from the render stack instead.
        if (show) {
            mapView.addLayer(MapView.RenderStack.WIDGETS, widgetsLayer);
            positionPanel();  // restore last dragged position
        } else {
            mapView.removeLayer(MapView.RenderStack.WIDGETS, widgetsLayer);
            SeekBarControl.dismiss();
        }
    }

    public boolean isVisible() { return visible; }

    // ── AtakMapView.OnMapViewResizedListener ──────────────────────────────────

    @Override
    public void onMapViewResized(AtakMapView view) {
        // Only reset to default position if the user hasn't dragged the panel yet
        if (panelX == 0 && panelY == 0) positionPanel();
        else repositionIfOffScreen();
    }

    // ── Drag handling ─────────────────────────────────────────────────────────

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
    }

    private void positionPanel() {
        panelX = MARGIN;
        panelY = mapView.getHeight() - PANEL_H - MARGIN * 4;
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

    // ── Slot tabs (rebuilt on slot list / active index changes) ──────────────

    private void rebuildSlotTabs(List<WindProfileViewModel.WindSlot> slots, int activeIdx) {
        // Remove all current slot tab children
        while (slotTabRow.getChildWidgetCount() > 0) {
            slotTabRow.removeWidget(slotTabRow.getChildAt(0));
        }
        if (slots == null || slots.isEmpty()) {
            TextWidget empty = makeText(" — no slots — ", TEXT_S, COLOR_MUTED);
            slotTabRow.addWidget(empty);
            return;
        }
        for (int i = 0; i < slots.size(); i++) {
            final int slotIdx = i;
            WindProfileViewModel.WindSlot slot = slots.get(i);
            boolean isActive = (i == activeIdx);

            TextWidget tab = makeText(" " + slot.label + " ", TEXT_S,
                    isActive ? Color.argb(255, 180, 210, 255) : COLOR_MUTED);
            if (isActive) tab.setBackground(COLOR_ACTIVE);

            tab.addOnClickListener(new MapWidget.OnClickListener() {
                @Override
                public void onMapWidgetClick(MapWidget w, MotionEvent e) {
                    windViewModel.setActiveSlot(slotIdx);
                }
            });
            slotTabRow.addWidget(tab);
        }
    }

    // ── Hour label + seek fill ────────────────────────────────────────────────

    private void updateHourLabel(int hourIndex) {
        WindProfileViewModel.WindSlot slot = windViewModel.getActiveWindSlot();
        if (slot == null || slot.profiles == null || slot.profiles.isEmpty()) {
            hourText.setText("+0 h  —");
            return;
        }
        if (hourIndex < slot.profiles.size()) {
            String iso  = slot.profiles.get(hourIndex).getIsoTime();
            String day  = WeatherUiUtils.isoDayOfWeek(iso);
            String time = iso.length() >= 16 ? iso.substring(11, 16) : iso;
            hourText.setText(String.format(Locale.US, "+%d h  %s %s", hourIndex, day, time));
        } else {
            hourText.setText(String.format(Locale.US, "+%d h", hourIndex));
        }
        updateWindReadout(hourIndex);
    }

    private void updateSeekFill(int hourIndex) {
        if (maxHourIndex <= 0) return;
        float ratio = (float) hourIndex / maxHourIndex;
        float fillW = Math.max(4, ratio * (PANEL_W - 2 * PAD));
        seekFill.setWidth(fillW);
    }

    // ── Wind readout row (rebuilt on profile / hour change) ──────────────────

    private void updateWindReadout(int hourIndex) {
        while (windRow.getChildWidgetCount() > 0) {
            windRow.removeWidget(windRow.getChildAt(0));
        }
        if (lastProfiles == null || lastProfiles.isEmpty()) {
            windRow.addWidget(makeText(" Loading…", TEXT_S, COLOR_MUTED));
            return;
        }
        int idx = Math.min(hourIndex, lastProfiles.size() - 1);
        WindProfileModel frame = lastProfiles.get(idx);
        List<WindProfileModel.AltitudeEntry> alts =
                frame.getAltitudes() != null ? frame.getAltitudes() : Collections.emptyList();

        int show = Math.min(3, alts.size());
        if (show == 0) {
            windRow.addWidget(makeText(" No altitude data", TEXT_S, COLOR_MUTED));
            return;
        }
        for (int i = 0; i < show; i++) {
            if (i > 0) windRow.addWidget(makeText("  |  ", TEXT_S, COLOR_MUTED));
            WindProfileModel.AltitudeEntry e = alts.get(i);
            windRow.addWidget(makeText(e.altitudeMeters + "m", TEXT_S, COLOR_MUTED));
            windRow.addWidget(makeText(String.format(Locale.US, " %.0fkt %.0fdeg",
                    e.windSpeed, e.windDirection), TEXT_S, COLOR_TEXT));
        }
    }

    // ── SeekBarControl (ATAK native on-map seekbar) ───────────────────────────

    private void openSeekBarControl() {
        SeekBarControl.show(new SeekBarControl.Subject() {
            @Override
            public int getValue() {
                // Map hour index 0–maxHourIndex to 0–100
                if (maxHourIndex <= 0) return 0;
                return (int) (windViewModel.getCurrentHourIndex() * 100f / maxHourIndex);
            }
            @Override
            public void setValue(int value) {
                // Map 0–100 back to 0–maxHourIndex
                int hour = (int) Math.round(value * maxHourIndex / 100f);
                windViewModel.setHourIndex(hour);
            }
            @Override
            public void onControlDismissed() { /* no-op */ }
        }, 8_000L);  // auto-dismiss after 8 s of inactivity
    }

    // ── Wind effect drawing ───────────────────────────────────────────────────

    private void drawWindEffect() {
        if (windEffectShape == null) return;
        WindProfileViewModel.WindSlot slot = windViewModel.getActiveWindSlot();
        if (slot == null || slot.profiles == null || slot.profiles.isEmpty()) return;

        int hourIdx = windViewModel.getCurrentHourIndex();
        int idx     = Math.min(hourIdx, slot.profiles.size() - 1);
        WindProfileModel frame = slot.profiles.get(idx);

        double surfaceSpeed = 0, surfaceDir = 0;
        if (frame.getAltitudes() != null && !frame.getAltitudes().isEmpty()) {
            WindProfileModel.AltitudeEntry s = frame.getAltitudes().get(0);
            surfaceSpeed = s.windSpeed;
            surfaceDir   = s.windDirection;
        }
        windEffectShape.place(
                slot.lat, slot.lon,
                surfaceSpeed, surfaceDir,
                slot.getRangeM(), slot.getHeightM(),
                WindEffectShape.uidSuffix(slot.lat, slot.lon, false),
                Collections.singletonList(frame));
    }

    // ── HUD management controls (driven by SharedPreferences) ─────────────

    private void applyHudVisibility(boolean show) {
        setVisible(show);
    }

    private void applyHudPosition(String pos) {
        if (pos == null) pos = "BL";
        float w = mapView.getWidth();
        float h = mapView.getHeight();
        switch (pos) {
            case "TL": panelX = MARGIN; panelY = MARGIN; break;
            case "TR": panelX = w - PANEL_W - MARGIN; panelY = MARGIN; break;
            case "BR": panelX = w - PANEL_W - MARGIN; panelY = h - PANEL_H - MARGIN * 4; break;
            default: /* BL */ panelX = MARGIN; panelY = h - PANEL_H - MARGIN * 4; break;
        }
        root.setPoint(panelX, panelY);
    }

    private void applyHudOpacity(int percent) {
        int alpha = (int) (percent * 2.55f); // 0–255
        panel.setBackingColor(Color.argb(alpha, 18, 18, 22));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void syncProfiles() {
        WindProfileViewModel.WindSlot slot = windViewModel.getActiveWindSlot();
        if (slot != null && slot.profiles != null) {
            lastProfiles = slot.profiles;
            maxHourIndex = Math.max(0, lastProfiles.size() - 1);
            updateWindReadout(windViewModel.getCurrentHourIndex());
        }
    }

    private void broadcastToggle(boolean show) {
        android.content.Intent i = new android.content.Intent(ACTION_TOGGLE_HUD);
        i.putExtra("visible", show);
        com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(i);
    }

    private static TextWidget makeText(String text, float size, int color) {
        TextWidget w = new TextWidget(text,
                new MapTextFormat(Typeface.MONOSPACE, (int) size), true);
        w.setColor(color);
        return w;
    }
}
