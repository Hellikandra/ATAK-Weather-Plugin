package org.dtakc.weather.atak.ui;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.dtakc.weather.atak.domain.model.WindProfileModel;
import org.dtakc.weather.atak.domain.repository.IWeatherRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ViewModel for Tab 2 — Wind Profile.
 *
 * Sprint 26 additions:
 *   Multi-slot support: up to MAX_SLOTS independent wind profile fetches can
 *   coexist.  Each slot has its own lat/lon/label and its own profile list.
 *   The active slot index is also LiveData so the UI tab strip stays in sync.
 *
 *   Slot lifecycle:
 *     addSlot(lat, lon, label)  — appends; fires loadWindProfile for that slot
 *     removeSlot(index)         — removes; active slot clamped if needed
 *     setActiveSlot(index)      — switches which slot the chart shows
 *     clearSlots()              — removes all slots
 */
public class WindProfileViewModel extends ViewModel {

    public static final int MAX_SLOTS = 4;

    // ── Slot descriptor ───────────────────────────────────────────────────
    public static class WindSlot {
        public final int    index;
        public final double lat;
        public final double lon;
        public final String label;         // short coord label shown in tab
        public String sourceId;             // data source that produced this profile
        public double rangeM  = 2000.0;    // per-slot wind-effect range
        public double heightM =  500.0;    // per-slot wind-effect height ceiling
        /** null while loading, non-null on success, empty list on error */
        public List<WindProfileModel> profiles = null;
        public boolean loading = false;
        public String  error   = null;

        WindSlot(int index, double lat, double lon, String label, String sourceId) {
            this.index    = index;
            this.lat      = lat;
            this.lon      = lon;
            this.label    = label;
            this.sourceId = sourceId != null ? sourceId : "";
        }
    }

    // ── State ──────────────────────────────────────────────────────────────
    private final MutableLiveData<List<WindSlot>> slotsLive       = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Integer>        activeSlotLive  = new MutableLiveData<>(-1);

    /** Single-slot backward-compat LiveData (still observed by the old observer chain). */
    private final MutableLiveData<UiState<List<WindProfileModel>>> windProfile = new MutableLiveData<>();

    private final IWeatherRepository weatherRepository;
    private       int nextIndex = 0;

    public WindProfileViewModel(IWeatherRepository weatherRepository) {
        this.weatherRepository = weatherRepository;
    }

    // ── Legacy single-slot API (kept for backward compat) ─────────────────
    /** Replaces the current single-slot list (old usage: WIND tab Request button). */
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

    // ── Multi-slot API ─────────────────────────────────────────────────────

    public LiveData<List<WindSlot>> getSlots()      { return slotsLive; }
    public LiveData<Integer>        getActiveSlot() { return activeSlotLive; }

    /** Returns a snapshot (never null). */
    public List<WindSlot> getSlotList() {
        List<WindSlot> s = slotsLive.getValue();
        return s != null ? s : Collections.emptyList();
    }

    public int getActiveSlotIndex() {
        Integer i = activeSlotLive.getValue();
        return i != null ? i : -1;
    }

    /** Returns the active WindSlot, or null if none. */
    public WindSlot getActiveWindSlot() {
        List<WindSlot> slots = getSlotList();
        int idx = getActiveSlotIndex();
        if (idx < 0 || idx >= slots.size()) return null;
        return slots.get(idx);
    }

    /**
     * Add a new slot and begin fetching its wind profile.
     * If MAX_SLOTS is reached the oldest slot is replaced.
     * Returns the new slot index in the list (0-based).
     */
    public int addSlot(double lat, double lon) {
        return addSlot(lat, lon, "");
    }

    /**
     * Add a new wind-profile slot, tagged with the data source that fetched it.
     * If MAX_SLOTS is reached the oldest slot is replaced.
     */
    public int addSlot(double lat, double lon, String sourceId) {
        List<WindSlot> slots = new ArrayList<>(getSlotList());
        String label = String.format(java.util.Locale.US, "%.2f,%.2f", lat, lon);
        WindSlot slot = new WindSlot(nextIndex++, lat, lon, label, sourceId);
        slot.loading = true;

        if (slots.size() >= MAX_SLOTS) slots.remove(0);
        slots.add(slot);
        int newActiveIdx = slots.size() - 1;
        slotsLive.setValue(slots);
        activeSlotLive.setValue(newActiveIdx);

        // Fetch profile
        final WindSlot ref = slot;
        weatherRepository.getWindProfile(lat, lon,
                new IWeatherRepository.Callback<List<WindProfileModel>>() {
                    @Override public void onSuccess(List<WindProfileModel> result) {
                        ref.loading  = false;
                        ref.profiles = result;
                        ref.error    = null;
                        // Also push to legacy single-slot LiveData
                        windProfile.postValue(UiState.success(result));
                        notifySlotChanged();
                    }
                    @Override public void onError(String message) {
                        ref.loading  = false;
                        ref.profiles = Collections.emptyList();
                        ref.error    = message;
                        windProfile.postValue(UiState.error(message));
                        notifySlotChanged();
                    }
                });
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

    private void notifySlotChanged() {
        // Re-post the same list to trigger observers
        List<WindSlot> current = slotsLive.getValue();
        slotsLive.postValue(current != null ? current : new ArrayList<>());
    }

    /**
     * Re-fetch wind data for the slot at listIndex using the specified sourceId.
     * The slot's sourceId field is updated before the fetch so observers see the new source.
     * Used when the user changes the source via the WIND tab spinner.
     */
    public void refetchSlot(int listIndex, String newSourceId) {
        List<WindSlot> slots = new ArrayList<>(getSlotList());
        if (listIndex < 0 || listIndex >= slots.size()) return;
        WindSlot slot = slots.get(listIndex);
        slot.sourceId = newSourceId;
        slot.loading  = true;
        slot.profiles = null;
        slotsLive.setValue(slots);

        final WindSlot ref = slot;
        weatherRepository.getWindProfile(slot.lat, slot.lon,
                new IWeatherRepository.Callback<List<WindProfileModel>>() {
                    @Override public void onSuccess(List<WindProfileModel> result) {
                        ref.loading  = false;
                        ref.profiles = result;
                        ref.error    = null;
                        windProfile.postValue(UiState.success(result));
                        notifySlotChanged();
                    }
                    @Override public void onError(String message) {
                        ref.loading  = false;
                        ref.profiles = Collections.emptyList();
                        ref.error    = message;
                        windProfile.postValue(UiState.error(message));
                        notifySlotChanged();
                    }
                });
    }
}
