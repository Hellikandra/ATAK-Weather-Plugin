package com.atakmap.android.weather.presentation.view;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.weather.WeatherDropDownReceiver;
import com.atakmap.android.weather.data.remote.WeatherSourceManager;
import com.atakmap.android.weather.domain.model.HourlyEntryModel;
import com.atakmap.android.weather.domain.model.LocationSnapshot;
import com.atakmap.android.weather.domain.model.LocationSource;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.domain.model.WindDataPoint;
import com.atakmap.android.weather.overlay.wind.WindEffectShape;
import com.atakmap.android.weather.overlay.wind.WindMarkerManager;
import com.atakmap.android.weather.plugin.R;
import com.atakmap.android.weather.presentation.viewmodel.WindProfileViewModel;
import com.atakmap.android.weather.util.WeatherPlaceTool;
import com.atakmap.android.weather.util.WeatherUiUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Coordinator for Tab 2 — Wind.
 *
 * <p>Extracted from {@code WeatherDropDownReceiver.initWindTab()}.
 * The DDR creates one instance in {@code initViewHelpers()} and delegates all
 * wind-tab interactions here.</p>
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Range and height SeekBars (write-back to ViewModel via typed methods).</li>
 *   <li>"Draw Wind Effect" and "Clear" buttons.</li>
 *   <li>"Drop Wind Marker" button.</li>
 *   <li>Hour seekbar → live prism redraw.</li>
 *   <li>Per-slot source spinner.</li>
 *   <li>Multi-slot empty-state visibility.</li>
 * </ul>
 */
public class WindTabCoordinator {

    // ── Dependencies ──────────────────────────────────────────────────────────
    private final View                 rootView;
    private final Context              pluginContext;
    private final MapView              mapView;
    private final WindProfileViewModel windViewModel;
    private final WindMarkerManager    windMarkerManager;
    private final WindEffectShape      windEffectShape;
    private final WindProfileView      windProfileView;

    // ── Mutable state ─────────────────────────────────────────────────────────
    /** Tracks all slots that have had a wind marker placed. Key = uidSuffix. */
    private final Map<String, WindProfileViewModel.WindSlot> placedWindSlots =
            new LinkedHashMap<>();

    private double  windEffectRangeM  = 2_000.0;
    private double  windEffectHeightM =   500.0;
    private boolean windEffectActive  = false;
    private int     windHourIndex     = 0;  // mirrors windHourIndex

    private double  lastWindLat    = Double.NaN;
    private double  lastWindLon    = Double.NaN;
    private boolean lastWindIsSelf = false;

    /** Profiles for the active slot — kept in sync from obsWindSlots / obsActiveSlot. */
    private List<com.atakmap.android.weather.domain.model.WindProfileModel>
            lastWindProfiles = null;

    /** Last weather for the marker-placement fallback. Set by DDR. */
    private WeatherModel lastWeather = null;

    // ── Altitude visibility ──────────────────────────────────────────────────
    /** Set of altitude labels currently hidden (e.g. "80m", "925 hPa"). */
    private final java.util.Set<String> hiddenAltitudes = new java.util.LinkedHashSet<>();

    // ── Wind Rose ────────────────────────────────────────────────────────────
    private WindRoseView windRoseView;
    private List<HourlyEntryModel> hourlyCache;
    /** Currently selected period: 24, 48, or Integer.MAX_VALUE (all/forecast). */
    private int windRosePeriodHours = 24;

    public WindTabCoordinator(MapView mapView,
                              View rootView,
                              Context pluginContext,
                              WindProfileViewModel windViewModel,
                              WindMarkerManager windMarkerManager,
                              WindEffectShape windEffectShape,
                              WindProfileView windProfileView) {
        this.mapView           = mapView;
        this.rootView          = rootView;
        this.pluginContext     = pluginContext;
        this.windViewModel     = windViewModel;
        this.windMarkerManager = windMarkerManager;
        this.windEffectShape   = windEffectShape;
        this.windProfileView   = windProfileView;

        wireMapCentreButton();
        wireRangeSeekBar();
        wireHeightSeekBar();
        wireDrawClearButtons();
        wireDropWindMarkerButton();
        wireHourSeekBar();
        wireWindSourceSpinner();
        wireWindRoseHelp();          // Sprint 18 — S18.2
        initWindRose();
        wireWindRoseButtons();
        wireWindRoseZoom();
        updateWindEmptyState(windViewModel.getSlotList());

        // Observe the shared hour index so the DDR seekbar tracks HUD scrubbing.
        windViewModel.getSelectedHour().observeForever(this::onSharedHourChanged);
    }

    /**
     * Called when the shared hour index changes — from either the DDR seekbar
     * or the HUD SliderWidget. Syncs the DDR SeekBar position without feedback loop.
     */
    private void onSharedHourChanged(Integer hourIndex) {
        if (hourIndex == null) return;
        windHourIndex = hourIndex;
        SeekBar windHourSeek = rootView.findViewById(R.id.wind_seekbar);
        if (windHourSeek != null && windHourSeek.getProgress() != hourIndex) {
            windHourSeek.setProgress(hourIndex);
        }
        if (windProfileView != null) windProfileView.onHourChanged(hourIndex);
        redrawIfActive();
    }

    // ── Public state setters (called by DDR observer callbacks) ───────────────

    public void setLastWeather(WeatherModel weather) {
        this.lastWeather = weather;
    }

    public void onActiveSlotChanged(int activeIdx) {
        TextView coordLabel = rootView.findViewById(R.id.textview_wind_marker_coord);
        updateWindMarkerCoordLabel(coordLabel);

        // Re-sync the DDR seekbar to the shared ViewModel hour index on slot switch
        SeekBar windHourSeek = rootView.findViewById(R.id.wind_seekbar);
        if (windHourSeek != null) {
            windHourSeek.setProgress(windHourIndex);
        }

        if (activeIdx < 0) return;

        List<WindProfileViewModel.WindSlot> slots = windViewModel.getSlotList();
        if (activeIdx >= slots.size()) return;
        WindProfileViewModel.WindSlot slot = slots.get(activeIdx);

        syncWindEffectSeekbarsToSlot(slot);
        lastWindLat    = slot.lat;
        lastWindLon    = slot.lon;
        lastWindIsSelf = false;

        try {
            mapView.getMapController().panTo(
                    new com.atakmap.coremap.maps.coords.GeoPoint(slot.lat, slot.lon), true);
        } catch (Exception ignored) {}

        syncWindSpinnerToSlot(slot);

        if (slot.profiles != null) {
            lastWindProfiles = slot.profiles;
        }
    }

    public void onSlotsChanged(List<WindProfileViewModel.WindSlot> slots) {
        int activeIdx = windViewModel.getActiveSlotIndex();
        updateWindEmptyState(slots);
        if (slots != null && activeIdx >= 0 && activeIdx < slots.size()) {
            syncWindSpinnerToSlot(slots.get(activeIdx));
        }
    }

    public void onWindProfilesUpdated(
            List<com.atakmap.android.weather.domain.model.WindProfileModel> profiles) {
        this.lastWindProfiles = profiles;
        populateWindRose();   // refresh rose with profile surface data
    }

    // ── Private wiring ────────────────────────────────────────────────────────

    /**
     * "Map Centre" button — immediately requests wind profile at the current
     * map centre without entering tap-map mode.
     */
    private void wireMapCentreButton() {
        Button btnMapCentre = rootView.findViewById(R.id.btn_wind_map_centre);
        if (btnMapCentre == null) return;
        btnMapCentre.setOnClickListener(v -> {
            double lat = mapView.getCenterPoint().get().getLatitude();
            double lon = mapView.getCenterPoint().get().getLongitude();
            String srcId = WeatherSourceManager.getInstance(rootView.getContext()).getActiveSourceId();
            windViewModel.addSlot(lat, lon, srcId);
            Toast.makeText(pluginContext,
                    String.format(Locale.US, "Wind profile at map centre (%.4f°, %.4f°)", lat, lon),
                    Toast.LENGTH_SHORT).show();
        });
    }

    private void wireRangeSeekBar() {
        final TextView rangeLabel = rootView.findViewById(R.id.textview_wind_range_value);
        final SeekBar  rangeSeek  = rootView.findViewById(R.id.seekbar_wind_range);
        if (rangeSeek == null) return;

        rangeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                windEffectRangeM = (p + 1) * 500.0;
                if (rangeLabel != null)
                    rangeLabel.setText(formatDistance(windEffectRangeM));
                int idx = windViewModel.getActiveSlotIndex();
                windViewModel.updateSlotRange(idx, windEffectRangeM);
                if (windEffectActive && windEffectShape != null && !Double.isNaN(lastWindLat)) {
                    String suffix = WindEffectShape.uidSuffix(lastWindLat, lastWindLon, lastWindIsSelf);
                    windEffectShape.updateRange(suffix, lastWindLat, lastWindLon,
                            windEffectRangeM, currentFrameList());
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) { redrawIfActive(); }
        });
    }

    private void wireHeightSeekBar() {
        final TextView heightLabel = rootView.findViewById(R.id.textview_wind_height_value);
        final SeekBar  heightSeek  = rootView.findViewById(R.id.seekbar_wind_height);
        if (heightSeek == null) return;

        heightSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                windEffectHeightM = (p + 1) * 50.0;
                if (heightLabel != null)
                    heightLabel.setText(String.format(Locale.US, "%.0f m", windEffectHeightM));
                int idx = windViewModel.getActiveSlotIndex();
                windViewModel.updateSlotHeight(idx, windEffectHeightM);
                if (windEffectActive && windEffectShape != null && !Double.isNaN(lastWindLat)) {
                    String suffix = WindEffectShape.uidSuffix(lastWindLat, lastWindLon, lastWindIsSelf);
                    windEffectShape.updateHeightCeiling(suffix, windEffectHeightM, currentFrameList());
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) { redrawIfActive(); }
        });
    }

    private void wireDrawClearButtons() {
        Button btnDraw  = rootView.findViewById(R.id.btn_draw_wind_effect);
        Button btnClear = rootView.findViewById(R.id.btn_clear_wind_effect);
        if (btnDraw  != null) btnDraw.setOnClickListener(v -> drawWindEffect());
        if (btnClear != null) btnClear.setOnClickListener(v -> {
            if (windEffectShape != null) windEffectShape.removeAll();
            windEffectActive = false;
            placedWindSlots.clear();
            Toast.makeText(pluginContext, R.string.wind_effect_cleared, Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Drop Wind Marker button removed — markers are managed via the Markers tab.
     * Method kept as no-op for call-site compatibility.
     */
    private void wireDropWindMarkerButton() {
        // btn_drop_wind_marker removed from layout — nothing to wire
    }

    private void wireHourSeekBar() {
        SeekBar windHourSeek = rootView.findViewById(R.id.wind_seekbar);
        if (windHourSeek == null) return;

        // Sync seekbar to the current ViewModel hour on first open
        windHourSeek.setProgress(windHourIndex);

        windHourSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) return;
                // setHourIndex() emits to getSelectedHour() LiveData which is observed
                // by BOTH this coordinator and WindHudWidget — both update from one call.
                windViewModel.setHourIndex(p);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb)  { redrawIfActive(); }
        });
    }

    private void wireWindSourceSpinner() {
        android.widget.Spinner spinner = rootView.findViewById(R.id.spinner_wind_source);
        if (spinner == null) return;

        WeatherSourceManager mgr     = WeatherSourceManager.getInstance(rootView.getContext());
        List<WeatherSourceManager.SourceEntry> entries = mgr.getAvailableEntries();

        ArrayAdapter<WeatherSourceManager.SourceEntry> adapter =
                WeatherUiUtils.makeDarkSpinnerAdapter(pluginContext, entries);
        spinner.setAdapter(adapter);
        WeatherUiUtils.styleSpinnerDark(spinner);
        spinner.setSelection(mgr.getActiveSourceIndex(), false);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent,
                                                 android.view.View v, int pos, long id) {
                WeatherSourceManager.SourceEntry entry = entries.get(pos);
                WindProfileViewModel.WindSlot activeSlot = windViewModel.getActiveWindSlot();
                if (activeSlot == null) {
                    mgr.setActiveSourceId(entry.sourceId);
                    return;
                }
                Toast.makeText(pluginContext,
                        "Re-fetching wind for slot using " + entry.displayName,
                        Toast.LENGTH_SHORT).show();
                windViewModel.updateSlotSource(windViewModel.getActiveSlotIndex(), entry.sourceId);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    /**
     * Wire the "?" button next to the Wind Rose title to show a help dialog.
     * Sprint 18 — S18.2
     */
    private void wireWindRoseHelp() {
        Button windRoseHelp = rootView.findViewById(R.id.btn_wind_rose_help);
        if (windRoseHelp != null) {
            windRoseHelp.setOnClickListener(v -> {
                new android.app.AlertDialog.Builder(mapView.getContext())
                    .setTitle("Wind Rose Guide")
                    .setMessage("The wind rose shows the frequency of wind from each of 16 compass directions.\n\n"
                        + "\u2022 Each sector = a compass direction (N, NNE, NE, etc.)\n"
                        + "\u2022 Sector length = frequency of wind from that direction\n"
                        + "\u2022 Colors indicate Beaufort wind force:\n"
                        + "  Green = Light (0\u20133 Bft)\n"
                        + "  Yellow = Moderate (4\u20135 Bft)\n"
                        + "  Orange = Strong (6\u20137 Bft)\n"
                        + "  Red = Gale+ (8+ Bft)\n\n"
                        + "Use the time period buttons to view historical vs forecast wind patterns.")
                    .setPositiveButton("OK", null)
                    .show();
            });
        }
    }

    // ── Wind Rose data feed (Sprint 9 — S9.3) ─────────────────────────────────

    /** Create the WindRoseView programmatically and add it to the wind_rose_frame. */
    private void initWindRose() {
        android.widget.FrameLayout frame = rootView.findViewById(R.id.wind_rose_frame);
        if (frame == null) return;
        windRoseView = new WindRoseView(pluginContext);
        frame.addView(windRoseView, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
    }

    /** Wire the 24h / 48h / Forecast period buttons. */
    private void wireWindRoseButtons() {
        Button btn24  = rootView.findViewById(R.id.wind_rose_24h);
        Button btn48  = rootView.findViewById(R.id.wind_rose_48h);
        Button btnAll = rootView.findViewById(R.id.wind_rose_forecast);

        View.OnClickListener listener = v -> {
            int id = v.getId();
            if (id == R.id.wind_rose_24h) {
                windRosePeriodHours = 24;
            } else if (id == R.id.wind_rose_48h) {
                windRosePeriodHours = 48;
            } else {
                windRosePeriodHours = Integer.MAX_VALUE;
            }
            populateWindRose();
        };

        if (btn24  != null) btn24.setOnClickListener(listener);
        if (btn48  != null) btn48.setOnClickListener(listener);
        if (btnAll != null) btnAll.setOnClickListener(listener);
    }

    /** Wire the +/- zoom buttons for the wind rose. */
    private void wireWindRoseZoom() {
        Button btnZoomIn  = rootView.findViewById(R.id.wind_rose_zoom_in);
        Button btnZoomOut = rootView.findViewById(R.id.wind_rose_zoom_out);

        if (btnZoomIn != null) {
            btnZoomIn.setOnClickListener(v -> {
                if (windRoseView != null) {
                    windRoseView.setScaleFactor(windRoseView.getScaleFactor() + 0.25f);
                }
            });
        }
        if (btnZoomOut != null) {
            btnZoomOut.setOnClickListener(v -> {
                if (windRoseView != null) {
                    windRoseView.setScaleFactor(windRoseView.getScaleFactor() - 0.25f);
                }
            });
        }
    }

    /**
     * Called by the DDR when the hourly forecast LiveData emits new data.
     * Caches the list and refreshes the wind rose.
     */
    public void setHourlyCache(List<HourlyEntryModel> entries) {
        this.hourlyCache = entries;
        populateWindRose();
    }

    /**
     * Build {@code WindDataPoint} list and push into the {@link WindRoseView}.
     *
     * <p>Prefers the active wind-profile slot's surface data (consistent with
     * the wind chart) so that the rose matches the chart when the user switches
     * to an ECMWF/DWD source.  Falls back to the generic hourly forecast when
     * no wind profile is loaded.</p>
     */
    private void populateWindRose() {
        if (windRoseView == null) return;

        List<WindDataPoint> points;
        String dataSourceStr;

        // Try to use wind profile surface data from active slot first
        if (lastWindProfiles != null && !lastWindProfiles.isEmpty()) {
            int limit = Math.min(lastWindProfiles.size(), windRosePeriodHours);
            points = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                com.atakmap.android.weather.domain.model.WindProfileModel frame =
                        lastWindProfiles.get(i);
                com.atakmap.android.weather.domain.model.WindProfileModel.AltitudeEntry surface =
                        frame.getSurface();
                if (surface == null) continue;
                long ts = System.currentTimeMillis() + (long) i * 3_600_000L;
                points.add(new WindDataPoint(surface.windDirection, surface.windSpeed, ts));
            }
            WindProfileViewModel.WindSlot slot = windViewModel.getActiveWindSlot();
            String srcName = slot != null ? slot.getSourceId() : "wind profile";
            dataSourceStr = "Wind profile (" + srcName + ") surface 10m";
        } else if (hourlyCache != null && !hourlyCache.isEmpty()) {
            // Fallback: generic hourly forecast
            int limit = Math.min(hourlyCache.size(), windRosePeriodHours);
            points = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                HourlyEntryModel h = hourlyCache.get(i);
                long ts = System.currentTimeMillis() + (long) i * 3_600_000L;
                points.add(new WindDataPoint(h.getWindDirection(), h.getWindSpeed(), ts));
            }
            dataSourceStr = "Hourly forecast surface 10m";
        } else {
            windRoseView.setWindData(null);
            return;
        }

        // Update title to reflect the selected period
        int limit = points.size();
        String label;
        if (windRosePeriodHours <= 24) {
            label = "Wind Rose \u2014 24 h";
        } else if (windRosePeriodHours <= 48) {
            label = "Wind Rose \u2014 48 h";
        } else {
            label = "Wind Rose \u2014 Forecast (" + limit + " h)";
        }
        windRoseView.setTitle(label);
        windRoseView.setWindData(points);

        // Update data info label
        TextView dataLabel = rootView.findViewById(R.id.wind_rose_data_label);
        if (dataLabel != null) {
            String periodStr = windRosePeriodHours <= 24 ? "24h" :
                    windRosePeriodHours <= 48 ? "48h" : "full forecast";
            dataLabel.setText(dataSourceStr + " \u2022 " + periodStr
                    + " \u2022 " + points.size() + " obs");
        }

        // Update summary readout: dominant direction, avg speed, max speed
        TextView summaryLabel = rootView.findViewById(R.id.wind_rose_summary);
        if (summaryLabel != null && !points.isEmpty()) {
            double sumSpeed = 0, maxSpeed = 0;
            int[] dirCounts = new int[16];
            for (WindDataPoint pt : points) {
                sumSpeed += pt.speed;
                if (pt.speed > maxSpeed) maxSpeed = pt.speed;
                if (pt.speed > 0.5) dirCounts[pt.getSectorIndex()]++;
            }
            double avgSpeed = sumSpeed / points.size();
            // Find dominant sector
            int domIdx = 0;
            for (int i = 1; i < 16; i++) {
                if (dirCounts[i] > dirCounts[domIdx]) domIdx = i;
            }
            String[] sectorNames = {"N","NNE","NE","ENE","E","ESE","SE","SSE",
                    "S","SSW","SW","WSW","W","WNW","NW","NNW"};
            float domPct = (float) dirCounts[domIdx] / points.size() * 100f;
            summaryLabel.setText(String.format(Locale.US,
                    "Dominant: %s (%.0f%%)  |  Avg: %.1f m/s  |  Max: %.1f m/s",
                    sectorNames[domIdx], domPct, avgSpeed, maxSpeed));
        } else if (summaryLabel != null) {
            summaryLabel.setText("");
        }
    }

    // ── Altitude visibility toggles ──────────────────────────────────────────

    /**
     * Rebuild the altitude toggle chip strip from the profile data.
     * Called when profiles are loaded or the active slot changes.
     */
    public void rebuildAltitudeToggles(
            List<com.atakmap.android.weather.domain.model.WindProfileModel> profiles) {
        LinearLayout strip = rootView.findViewById(R.id.wind_altitude_toggle_strip);
        if (strip == null || profiles == null || profiles.isEmpty()) return;
        strip.removeAllViews();

        // Collect unique altitude labels from the first profile frame
        com.atakmap.android.weather.domain.model.WindProfileModel first = profiles.get(0);
        if (first.getAltitudes() == null || first.getAltitudes().isEmpty()) return;

        float dp = rootView.getContext().getResources().getDisplayMetrics().density;
        int activeColor   = Color.parseColor("#2979FF");
        int inactiveColor = Color.parseColor("#333344");

        for (com.atakmap.android.weather.domain.model.WindProfileModel.AltitudeEntry alt
                : first.getAltitudes()) {
            String label = alt.isPressureLevel() && alt.pressureHPa != null
                    ? alt.pressureHPa + " hPa"
                    : alt.altitudeMeters + "m";

            Button chip = new Button(pluginContext);
            chip.setText(label);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
            chip.setAllCaps(false);
            chip.setTextColor(Color.WHITE);

            boolean visible = !hiddenAltitudes.contains(label);
            chip.setBackgroundColor(visible ? activeColor : inactiveColor);
            chip.setAlpha(visible ? 1.0f : 0.5f);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (int) (28 * dp));
            lp.setMarginEnd((int) (3 * dp));
            chip.setLayoutParams(lp);
            chip.setPadding((int) (8 * dp), 0, (int) (8 * dp), 0);
            chip.setMinWidth(0);
            chip.setMinimumWidth(0);

            chip.setOnClickListener(v -> {
                if (hiddenAltitudes.contains(label)) {
                    hiddenAltitudes.remove(label);
                    chip.setBackgroundColor(activeColor);
                    chip.setAlpha(1.0f);
                } else {
                    hiddenAltitudes.add(label);
                    chip.setBackgroundColor(inactiveColor);
                    chip.setAlpha(0.5f);
                }
                applyAltitudeVisibility();
            });

            strip.addView(chip);
        }
    }

    /**
     * Apply the current altitude visibility to the WindChartView and
     * trigger a wind effect redraw with filtered altitudes.
     */
    private void applyAltitudeVisibility() {
        if (windProfileView != null && windProfileView.getWindChart() != null) {
            windProfileView.getWindChart().setHiddenAltitudes(hiddenAltitudes);
        }
        redrawIfActive();
    }

    /** @return the set of altitude labels that are currently hidden. */
    public java.util.Set<String> getHiddenAltitudes() {
        return hiddenAltitudes;
    }

    // ── Wind effect drawing ───────────────────────────────────────────────────

    private void drawWindEffect() {
        if (windEffectShape == null || Double.isNaN(lastWindLat) || lastWeather == null) {
            Toast.makeText(pluginContext, R.string.wind_effect_no_marker, Toast.LENGTH_SHORT).show();
            return;
        }
        final String suffix = WindEffectShape.uidSuffix(lastWindLat, lastWindLon, lastWindIsSelf);
        double surfaceSpeed = lastWeather.getWindSpeed();
        double surfaceDir   = lastWeather.getWindDirection();

        List<com.atakmap.android.weather.domain.model.WindProfileModel> frameList = currentFrameList();
        if (frameList != null && !frameList.isEmpty()) {
            com.atakmap.android.weather.domain.model.WindProfileModel.AltitudeEntry surface =
                    frameList.get(0).getAltitudes() != null
                            && !frameList.get(0).getAltitudes().isEmpty()
                            ? frameList.get(0).getAltitudes().get(0) : null;
            if (surface != null) {
                surfaceSpeed = surface.windSpeed;
                surfaceDir   = surface.windDirection;
            }
        }

        windEffectShape.place(lastWindLat, lastWindLon,
                surfaceSpeed, surfaceDir,
                windEffectRangeM, windEffectHeightM,
                suffix, frameList);
        windEffectActive = true;

        // Track this wind effect so redrawIfActive() can sync ALL placed effects
        WindProfileViewModel.WindSlot activeSlot = windViewModel.getActiveWindSlot();
        if (activeSlot != null) {
            placedWindSlots.put(suffix, activeSlot);
        }

        String msg = (frameList != null)
                ? pluginContext.getString(R.string.wind_effect_drawn)
                : pluginContext.getString(R.string.wind_effect_drawn_no_profile);
        Toast.makeText(pluginContext, msg, Toast.LENGTH_SHORT).show();
    }

    private void redrawIfActive() {
        if (!windEffectActive || windEffectShape == null) return;
        if (!placedWindSlots.isEmpty()) {
            for (Map.Entry<String, WindProfileViewModel.WindSlot> entry : placedWindSlots.entrySet()) {
                String suffix = entry.getKey();
                WindProfileViewModel.WindSlot slot = entry.getValue();
                WindProfileViewModel.WindSlot live = findSlotByLatLon(slot.lat, slot.lon);
                if (live != null && live.profiles != null && !live.profiles.isEmpty()) {
                    int idx = Math.min(windHourIndex, live.profiles.size() - 1);
                    // Filter hidden altitudes before passing to wind effect
                    com.atakmap.android.weather.domain.model.WindProfileModel filtered =
                            filterFrame(live.profiles.get(idx));
                    List<com.atakmap.android.weather.domain.model.WindProfileModel> frameList =
                            Collections.singletonList(filtered);
                    double surfaceSpeed = 0, surfaceDir = 0;
                    if (filtered.getAltitudes() != null && !filtered.getAltitudes().isEmpty()) {
                        com.atakmap.android.weather.domain.model.WindProfileModel.AltitudeEntry s =
                                filtered.getAltitudes().get(0);
                        surfaceSpeed = s.windSpeed;
                        surfaceDir   = s.windDirection;
                    }
                    windEffectShape.place(live.lat, live.lon,
                            surfaceSpeed, surfaceDir,
                            windEffectRangeM, windEffectHeightM,
                            suffix, frameList);
                }
            }
        } else if (lastWeather != null && !Double.isNaN(lastWindLat)) {
            drawWindEffect();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<com.atakmap.android.weather.domain.model.WindProfileModel> currentFrameList() {
        if (lastWindProfiles == null || lastWindProfiles.isEmpty()) return null;
        int idx = Math.min(windHourIndex, lastWindProfiles.size() - 1);
        com.atakmap.android.weather.domain.model.WindProfileModel frame =
                lastWindProfiles.get(idx);
        return Collections.singletonList(filterFrame(frame));
    }

    /**
     * Return a copy of the frame with hidden altitude entries removed.
     * If nothing is hidden, returns the original frame unchanged.
     */
    private com.atakmap.android.weather.domain.model.WindProfileModel filterFrame(
            com.atakmap.android.weather.domain.model.WindProfileModel frame) {
        if (hiddenAltitudes.isEmpty() || frame == null) return frame;
        List<com.atakmap.android.weather.domain.model.WindProfileModel.AltitudeEntry> src =
                frame.getAltitudes();
        if (src == null || src.isEmpty()) return frame;

        List<com.atakmap.android.weather.domain.model.WindProfileModel.AltitudeEntry> filtered =
                new ArrayList<>();
        for (com.atakmap.android.weather.domain.model.WindProfileModel.AltitudeEntry alt : src) {
            String label = alt.isPressureLevel() && alt.pressureHPa != null
                    ? alt.pressureHPa + " hPa"
                    : alt.altitudeMeters + "m";
            if (!hiddenAltitudes.contains(label)) {
                filtered.add(alt);
            }
        }
        if (filtered.size() == src.size()) return frame;  // nothing hidden
        return new com.atakmap.android.weather.domain.model.WindProfileModel(
                frame.getIsoTime(), filtered);
    }

    private WindProfileViewModel.WindSlot findSlotByLatLon(double lat, double lon) {
        for (WindProfileViewModel.WindSlot s : windViewModel.getSlotList()) {
            if (Math.abs(s.lat - lat) < 1e-6 && Math.abs(s.lon - lon) < 1e-6) return s;
        }
        return null;
    }

    private void syncWindEffectSeekbarsToSlot(WindProfileViewModel.WindSlot slot) {
        windEffectRangeM  = slot.getRangeM();
        windEffectHeightM = slot.getHeightM();

        SeekBar  rangeSeek   = rootView.findViewById(R.id.seekbar_wind_range);
        SeekBar  heightSeek  = rootView.findViewById(R.id.seekbar_wind_height);
        TextView rangeLabel  = rootView.findViewById(R.id.textview_wind_range_value);
        TextView heightLabel = rootView.findViewById(R.id.textview_wind_height_value);

        if (rangeSeek != null) {
            int prog = (int) Math.round(slot.getRangeM() / 500.0) - 1;
            rangeSeek.setProgress(Math.max(0, Math.min(rangeSeek.getMax(), prog)));
        }
        if (rangeLabel  != null) rangeLabel.setText(formatDistance(windEffectRangeM));
        if (heightSeek  != null) {
            int prog = (int) Math.round(slot.getHeightM() / 50.0) - 1;
            heightSeek.setProgress(Math.max(0, Math.min(heightSeek.getMax(), prog)));
        }
        if (heightLabel != null)
            heightLabel.setText(String.format(Locale.US, "%.0f m", windEffectHeightM));
    }

    private void syncWindSpinnerToSlot(WindProfileViewModel.WindSlot slot) {
        android.widget.Spinner spinner = rootView.findViewById(R.id.spinner_wind_source);
        if (spinner == null || spinner.getAdapter() == null) return;
        int idx = WeatherSourceManager.getInstance(rootView.getContext())
                .getIndexForSourceId(slot.getSourceId());
        if (idx >= 0 && spinner.getSelectedItemPosition() != idx) {
            spinner.setSelection(idx, false);
        }
    }

    private void updateWindEmptyState(List<WindProfileViewModel.WindSlot> slots) {
        boolean hasSlots  = slots != null && !slots.isEmpty();
        View emptyHint    = rootView.findViewById(R.id.textview_wind_empty);
        // Source row is always visible (moved outside slot strip)
        View chartFrame   = rootView.findViewById(R.id.wind_chart_frame);
        if (emptyHint   != null) emptyHint.setVisibility(hasSlots ? View.GONE    : View.VISIBLE);
        if (chartFrame  != null) chartFrame.setVisibility(hasSlots ? View.VISIBLE : View.GONE);
    }

    private void updateWindMarkerCoordLabel(TextView label) {
        if (label == null) return;
        WindProfileViewModel.WindSlot slot = windViewModel.getActiveWindSlot();
        if (slot == null) {
            label.setText(R.string.wind_marker_no_slot);
        } else {
            label.setText(String.format(Locale.US,
                    "Active slot: %.4f°N  %.4f°E%s",
                    slot.lat, slot.lon,
                    slot.loading ? "  (loading…)" : slot.error != null ? "  (error)" : ""));
        }
    }

    private static String formatDistance(double metres) {
        if (metres >= 1000) return String.format(Locale.US, "%.1f km", metres / 1000.0);
        return String.format(Locale.US, "%.0f m", metres);
    }

    private static WeatherModel buildWeatherModelFromProfile(WindProfileViewModel.WindSlot slot) {
        if (slot.profiles == null || slot.profiles.isEmpty()) return null;
        com.atakmap.android.weather.domain.model.WindProfileModel frame = slot.profiles.get(0);
        if (frame.getAltitudes() == null || frame.getAltitudes().isEmpty()) return null;
        com.atakmap.android.weather.domain.model.WindProfileModel.AltitudeEntry surface =
                frame.getAltitudes().get(0);
        return new WeatherModel.Builder(slot.lat, slot.lon)
                .windSpeed(surface.windSpeed)
                .windDirection(surface.windDirection)
                .temperatureMin(surface.temperature)
                .temperatureMax(surface.temperature)
                .requestTimestamp(frame.getIsoTime())
                .build();
    }

    // ── Dispose ───────────────────────────────────────────────────────────────

    /** Call from DDR {@code disposeImpl()} to clean up map shapes and observers. */
    public void clearWindShapes() {
        if (windEffectShape != null) windEffectShape.removeAll();
    }

    /** Remove the shared-hour observer. Call from DDR disposeImpl. */
    public void dispose() {
        windViewModel.getSelectedHour().removeObserver(this::onSharedHourChanged);
    }
}
