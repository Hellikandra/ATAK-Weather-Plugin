package com.atakmap.android.weather.presentation.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.atakmap.android.weather.domain.model.WindProfileModel;
import com.atakmap.android.weather.domain.repository.IWeatherRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ViewModel for Tab 2 — Wind Profile.
 *
 * <h3>Multi-slot support</h3>
 * Up to {@link #MAX_SLOTS} independent wind profile fetches can coexist. Each
 * slot has its own lat/lon/label and its own profile list. The active slot index
 * is also LiveData so the UI tab strip stays in sync.
 *
 * <h3>Slot lifecycle</h3>
 * <ul>
 *   <li>{@link #addSlot(double, double, String)} — appends; fires wind profile fetch.</li>
 *   <li>{@link #removeSlot(int)} — removes; active slot clamped if needed.</li>
 *   <li>{@link #setActiveSlot(int)} — switches which slot the chart shows.</li>
 *   <li>{@link #clearSlots()} — removes all slots.</li>
 * </ul>
 *
 * <h3>Refactoring changes (vs original)</h3>
 * <ul>
 *   <li>{@link WindSlot} mutable fields ({@code rangeM}, {@code heightM},
 *       {@code sourceId}) are now package-private; DDR calls typed methods
 *       ({@link #updateSlotRange}, {@link #updateSlotHeight}, {@link #updateSlotSource})
 *       instead of mutating fields directly. This routes all state changes
 *       through LiveData, preventing the DDR from bypassing the observer chain.</li>
 *   <li>{@link #loadWindProfile(double, double)} legacy API marked
 *       {@code @Deprecated} — all new call-sites use {@link #addSlot}.</li>
 *   <li>{@link #addSlot(double, double, String)} accepts an optional
 *       {@code displayName} overload — generates the coord label internally
 *       when empty.</li>
 * </ul>
 */
public class WindProfileViewModel extends ViewModel {

    public static final int MAX_SLOTS = 4;

    // ── Slot descriptor ───────────────────────────────────────────────────────

    public static class WindSlot {
        public final int    index;
        public final double lat;
        public final double lon;
        public final String label;     // short coord label shown in tab

        /** Package-private — mutated only through ViewModel update methods. */
        String sourceId;
        double rangeM  = 2_000.0;  // per-slot wind-effect range
        double heightM =   500.0;  // per-slot wind-effect height ceiling

        /** {@code null} while loading, non-null on success, empty list on error. */
        public List<WindProfileModel> profiles = null;
        public boolean loading = false;
        public String  error   = null;

        // ── Read-only accessors ───────────────────────────────────────────────
        public String getSourceId() { return sourceId; }
        public double getRangeM()   { return rangeM;   }
        public double getHeightM()  { return heightM;  }

        WindSlot(int index, double lat, double lon, String label, String sourceId) {
            this.index    = index;
            this.lat      = lat;
            this.lon      = lon;
            this.label    = label;
            this.sourceId = sourceId != null ? sourceId : "";
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private final MutableLiveData<List<WindSlot>> slotsLive      = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Integer>        activeSlotLive = new MutableLiveData<>(-1);

    /** Single-slot backward-compat LiveData (observed by legacy observer chain). */
    private final MutableLiveData<UiState<List<WindProfileModel>>> windProfile =
            new MutableLiveData<>();

    private final IWeatherRepository weatherRepository;
    private       int                nextIndex = 0;

    public WindProfileViewModel(IWeatherRepository weatherRepository) {
        this.weatherRepository = weatherRepository;
    }

    // ── Legacy single-slot API ─────────────────────────────────────────────────

    /**
     * @deprecated Use {@link #addSlot(double, double, String)} for new call-sites.
     *             This method is retained for backward compatibility only.
     */
    @Deprecated
    public void loadWindProfile(double lat, double lon) {
        windProfile.setValue(UiState.loading());
        weatherRepository.getWindProfile(lat, lon,
                new IWeatherRepository.Callback<List<WindProfileModel>>() {
                    @Override public void onSuccess(List<WindProfileModel> result) {
                        windProfile.setValue(UiState.success(result));
                    }
                    @Override public void onError(String message) {
                        windProfile.setValue(UiState.error(message));
                    }
                });
    }

    public LiveData<UiState<List<WindProfileModel>>> getWindProfile() { return windProfile; }

    // ── Multi-slot API ────────────────────────────────────────────────────────

    public LiveData<List<WindSlot>> getSlots()      { return slotsLive; }
    public LiveData<Integer>        getActiveSlot() { return activeSlotLive; }

    /** Returns a snapshot of the current slot list (never null). */
    public List<WindSlot> getSlotList() {
        List<WindSlot> s = slotsLive.getValue();
        return s != null ? s : Collections.emptyList();
    }

    public int getActiveSlotIndex() {
        Integer i = activeSlotLive.getValue();
        return i != null ? i : -1;
    }

    /** Returns the active {@link WindSlot}, or {@code null} if none. */
    public WindSlot getActiveWindSlot() {
        List<WindSlot> slots = getSlotList();
        int idx = getActiveSlotIndex();
        if (idx < 0 || idx >= slots.size()) return null;
        return slots.get(idx);
    }

    /**
     * Add a new slot and begin fetching its wind profile.
     * If {@link #MAX_SLOTS} is reached the oldest slot is replaced.
     *
     * @return the new slot's list index (0-based)
     */
    public int addSlot(double lat, double lon) {
        return addSlot(lat, lon, "");
    }

    /**
     * Add a new wind-profile slot, tagged with the data source that will fetch it.
     * If {@link #MAX_SLOTS} is reached the oldest slot is replaced.
     *
     * @param sourceId    data source ID; used to tag the slot and route the fetch
     * @return the new slot's list index (0-based)
     */
    public int addSlot(double lat, double lon, String sourceId) {
        return addSlot(lat, lon, sourceId, null);
    }

    /**
     * Full overload — accepts an optional {@code displayName} shown in the tab strip.
     * If {@code displayName} is null or empty a coord label is generated automatically.
     *
     * @param sourceId    data source ID
     * @param displayName optional human-readable tab label; {@code null} = auto
     * @return the new slot's list index (0-based)
     */
    public int addSlot(double lat, double lon, String sourceId, String displayName) {
        List<WindSlot> slots = new ArrayList<>(getSlotList());

        String label = (displayName != null && !displayName.isEmpty())
                ? displayName
                : String.format(java.util.Locale.US, "%.2f,%.2f", lat, lon);

        WindSlot slot = new WindSlot(nextIndex++, lat, lon, label, sourceId);
        slot.loading = true;

        if (slots.size() >= MAX_SLOTS) slots.remove(0);
        slots.add(slot);
        int newActiveIdx = slots.size() - 1;
        slotsLive.setValue(slots);
        activeSlotLive.setValue(newActiveIdx);

        fetchForSlot(slot);
        return newActiveIdx;
    }

    public void removeSlot(int listIndex) {
        List<WindSlot> slots = new ArrayList<>(getSlotList());
        if (listIndex < 0 || listIndex >= slots.size()) return;
        slots.remove(listIndex);
        int active = getActiveSlotIndex();
        if (active >= slots.size()) active = slots.size() - 1;
        slotsLive.setValue(slots);
        activeSlotLive.setValue(active);
    }

    public void setActiveSlot(int listIndex) {
        List<WindSlot> slots = getSlotList();
        if (listIndex < 0 || listIndex >= slots.size()) return;
        activeSlotLive.setValue(listIndex);
        WindSlot s = slots.get(listIndex);
        if (s.profiles != null && !s.profiles.isEmpty()) {
            windProfile.setValue(UiState.success(s.profiles));
        }
    }

    public void clearSlots() {
        slotsLive.setValue(new ArrayList<>());
        activeSlotLive.setValue(-1);
    }

    // ── Typed slot-mutation methods ──────────────────────────────────────────
    // DDR calls these instead of mutating WindSlot fields directly.
    // Each method updates the field and re-posts slotsLive so all observers fire.

    /**
     * Update the wind-effect range for the slot at {@code listIndex} and notify
     * observers. No-op if the index is out of range.
     */
    public void updateSlotRange(int listIndex, double rangeM) {
        WindSlot slot = slotAt(listIndex);
        if (slot == null) return;
        slot.rangeM = rangeM;
        notifySlotChanged();
    }

    /**
     * Update the wind-effect height ceiling for the slot at {@code listIndex}
     * and notify observers. No-op if the index is out of range.
     */
    public void updateSlotHeight(int listIndex, double heightM) {
        WindSlot slot = slotAt(listIndex);
        if (slot == null) return;
        slot.heightM = heightM;
        notifySlotChanged();
    }

    /**
     * Change the data source for the slot at {@code listIndex} and re-fetch.
     * Equivalent to the old pattern of setting {@code slot.sourceId} directly
     * and calling {@link #refetchSlot}. Notifies observers before the network
     * call so the UI can immediately show a loading indicator for the slot.
     */
    public void updateSlotSource(int listIndex, String newSourceId) {
        WindSlot slot = slotAt(listIndex);
        if (slot == null) return;
        slot.sourceId = newSourceId;
        slot.loading  = true;
        slot.profiles = null;
        notifySlotChanged();
        fetchForSlot(slot);
    }

    /**
     * Re-fetch wind data for the slot at {@code listIndex} using the specified
     * source. The slot's sourceId is updated before the fetch so observers see
     * the new source. Used by the WIND tab spinner.
     */
    public void refetchSlot(int listIndex, String newSourceId) {
        updateSlotSource(listIndex, newSourceId);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private WindSlot slotAt(int listIndex) {
        List<WindSlot> slots = getSlotList();
        if (listIndex < 0 || listIndex >= slots.size()) return null;
        return slots.get(listIndex);
    }

    private void fetchForSlot(WindSlot slot) {
        weatherRepository.getWindProfile(slot.lat, slot.lon,
                new IWeatherRepository.Callback<List<WindProfileModel>>() {
                    @Override public void onSuccess(List<WindProfileModel> result) {
                        slot.loading  = false;
                        slot.profiles = result;
                        slot.error    = null;
                        windProfile.postValue(UiState.success(result));
                        notifySlotChanged();
                    }
                    @Override public void onError(String message) {
                        slot.loading  = false;
                        slot.profiles = Collections.emptyList();
                        slot.error    = message;
                        windProfile.postValue(UiState.error(message));
                        notifySlotChanged();
                    }
                });
    }

    private void notifySlotChanged() {
        // Re-post the same list reference to trigger all observers
        List<WindSlot> current = slotsLive.getValue();
        slotsLive.postValue(current != null ? current : new ArrayList<>());
    }
}
