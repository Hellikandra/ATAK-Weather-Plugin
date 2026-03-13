package com.atakmap.android.weather.presentation.view;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.weather.WeatherDropDownReceiver;
import com.atakmap.android.weather.data.remote.WeatherSourceManager;
import com.atakmap.android.weather.domain.model.LocationSnapshot;
import com.atakmap.android.weather.domain.model.LocationSource;
import com.atakmap.android.weather.domain.model.WeatherModel;
import com.atakmap.android.weather.overlay.wind.WindEffectShape;
import com.atakmap.android.weather.overlay.wind.WindMarkerManager;
import com.atakmap.android.weather.plugin.R;
import com.atakmap.android.weather.presentation.viewmodel.WindProfileViewModel;
import com.atakmap.android.weather.util.WeatherPlaceTool;
import com.atakmap.android.weather.util.WeatherUiUtils;

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
    private int     windHourIndex     = 0;

    private double  lastWindLat    = Double.NaN;
    private double  lastWindLon    = Double.NaN;
    private boolean lastWindIsSelf = false;

    /** Profiles for the active slot — kept in sync from obsWindSlots / obsActiveSlot. */
    private List<com.atakmap.android.weather.domain.model.WindProfileModel>
            lastWindProfiles = null;

    /** Last weather for the marker-placement fallback. Set by DDR. */
    private WeatherModel lastWeather = null;

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

        wireRangeSeekBar();
        wireHeightSeekBar();
        wireDrawClearButtons();
        wireDropWindMarkerButton();
        wireHourSeekBar();
        wireWindSourceSpinner();
        updateWindEmptyState(windViewModel.getSlotList());
    }

    // ── Public state setters (called by DDR observer callbacks) ───────────────

    public void setLastWeather(WeatherModel weather) {
        this.lastWeather = weather;
    }

    public void onActiveSlotChanged(int activeIdx) {
        TextView coordLabel = rootView.findViewById(R.id.textview_wind_marker_coord);
        updateWindMarkerCoordLabel(coordLabel);
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
    }

    // ── Private wiring ────────────────────────────────────────────────────────

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
            Toast.makeText(pluginContext, R.string.wind_effect_cleared, Toast.LENGTH_SHORT).show();
        });
    }

    private void wireDropWindMarkerButton() {
        Button   btnDrop      = rootView.findViewById(R.id.btn_drop_wind_marker);
        TextView coordLabel   = rootView.findViewById(R.id.textview_wind_marker_coord);
        if (btnDrop == null) return;

        btnDrop.setOnClickListener(v -> {
            WindProfileViewModel.WindSlot activeSlot = windViewModel.getActiveWindSlot();
            if (activeSlot != null && windMarkerManager != null) {
                LocationSnapshot slotLocation = new LocationSnapshot(
                        activeSlot.lat, activeSlot.lon,
                        activeSlot.label, LocationSource.MAP_CENTRE);
                WeatherModel wx = lastWeather;
                if (wx == null && activeSlot.profiles != null && !activeSlot.profiles.isEmpty()) {
                    wx = buildWeatherModelFromProfile(activeSlot);
                }
                if (wx != null) {
                    windMarkerManager.placeMarker(slotLocation, wx);
                    lastWindLat    = activeSlot.lat;
                    lastWindLon    = activeSlot.lon;
                    lastWindIsSelf = false;
                    String suffix = WindEffectShape.uidSuffix(activeSlot.lat, activeSlot.lon, false);
                    placedWindSlots.put(suffix, activeSlot);
                    Toast.makeText(pluginContext,
                            String.format(Locale.US, "Wind marker dropped at %.4f°, %.4f°",
                                    activeSlot.lat, activeSlot.lon),
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(pluginContext, R.string.map_marker_no_data,
                            Toast.LENGTH_SHORT).show();
                }
            } else if (lastWeather != null && windMarkerManager != null) {
                // Fallback: no slot selected — use last known weather location
                Toast.makeText(pluginContext, R.string.map_marker_no_data,
                        Toast.LENGTH_SHORT).show();
            }
            updateWindMarkerCoordLabel(coordLabel);
        });
    }

    private void wireHourSeekBar() {
        SeekBar windHourSeek = rootView.findViewById(R.id.wind_seekbar);
        if (windHourSeek == null) return;
        windHourSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                windHourIndex = p;
                if (windProfileView != null) windProfileView.onHourChanged(p);
                if (fromUser) redrawIfActive();
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
                WeatherUiUtils.makeDarkSpinnerAdapter(rootView.getContext(), entries);
        spinner.setAdapter(adapter);
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
                    List<com.atakmap.android.weather.domain.model.WindProfileModel> frameList =
                            Collections.singletonList(live.profiles.get(idx));
                    double surfaceSpeed = 0, surfaceDir = 0;
                    if (!live.profiles.get(idx).getAltitudes().isEmpty()) {
                        com.atakmap.android.weather.domain.model.WindProfileModel.AltitudeEntry s =
                                live.profiles.get(idx).getAltitudes().get(0);
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
        return Collections.singletonList(lastWindProfiles.get(idx));
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
        View sourceRow    = rootView.findViewById(R.id.wind_source_row);
        View chartFrame   = rootView.findViewById(R.id.wind_chart_frame);
        if (emptyHint   != null) emptyHint.setVisibility(hasSlots ? View.GONE    : View.VISIBLE);
        if (sourceRow   != null) sourceRow.setVisibility(hasSlots ? View.VISIBLE : View.GONE);
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

    /** Call from DDR {@code disposeImpl()} to clean up map shapes. */
    public void clearWindShapes() {
        if (windEffectShape != null) windEffectShape.removeAll();
    }
}
