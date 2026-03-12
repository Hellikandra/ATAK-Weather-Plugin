package org.dtakc.weather.atak.ui.viewmodel;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import org.dtakc.weather.atak.data.local.CachingWeatherRepository;
import org.dtakc.weather.atak.domain.model.WindProfileModel;
import org.dtakc.weather.atak.domain.repository.IWeatherRepository;
import org.dtakc.weather.atak.ui.WindProfileViewModel;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Wave 3 — WindProfileViewModel unit tests.
 *
 * TC-21a: addSlot() with unique lat/lon appends a new slot
 * TC-21b: setActiveSlot() updates active index LiveData
 * TC-21c: removeSlot() removes slot and adjusts active index
 * TC-21d: refetchSlot() calls repo.getWindProfile with new sourceId
 */
@RunWith(JUnit4.class)
public class WindProfileViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantTaskRule = new InstantTaskExecutorRule();

    @Mock CachingWeatherRepository mockRepo;

    private WindProfileViewModel sut;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        // Default: wind fetch returns empty list
        doAnswer(inv -> {
            IWeatherRepository.Callback<List<WindProfileModel>> cb = inv.getArgument(2);
            cb.onSuccess(Collections.emptyList());
            return null;
        }).when(mockRepo).getWindProfile(anyDouble(), anyDouble(), any());
        sut = new WindProfileViewModel(mockRepo);
    }

    // TC-21a: addSlot appends a new WindSlot
    @Test
    public void TC21a_addSlot_appendsEntry() {
        assertTrue("Initial slot list should be empty", sut.getSlotList().isEmpty());

        sut.addSlot(48.85, 2.35, "open_meteo");

        // Allow executor to run
        List<WindProfileViewModel.WindSlot> slots = sut.getSlotList();
        assertFalse("Slot list must not be empty after addSlot", slots.isEmpty());
        assertEquals(48.85, slots.get(0).lat, 1e-6);
        assertEquals(2.35,  slots.get(0).lon, 1e-6);
        assertEquals("open_meteo", slots.get(0).sourceId);
    }

    // TC-21b: setActiveSlot updates the activeSlot LiveData
    @Test
    public void TC21b_setActiveSlot_updatesLiveData() {
        sut.addSlot(48.85, 2.35, "open_meteo");
        sut.addSlot(51.50, -0.12, "open_meteo");

        List<Integer> activeValues = new ArrayList<>();
        sut.getActiveSlot().observeForever(activeValues::add);

        sut.setActiveSlot(1);

        // Check last emitted value is 1
        assertFalse(activeValues.isEmpty());
        assertEquals(Integer.valueOf(1), activeValues.get(activeValues.size() - 1));
    }

    // TC-21c: removeSlot removes the entry and adjusts active index
    @Test
    public void TC21c_removeSlot_removesEntry() {
        sut.addSlot(48.85, 2.35, "open_meteo");
        sut.addSlot(51.50, -0.12, "open_meteo");
        assertEquals(2, sut.getSlotList().size());

        sut.removeSlot(0);

        assertEquals(1, sut.getSlotList().size());
        assertEquals(51.50, sut.getSlotList().get(0).lat, 1e-6);
    }

    // TC-21d: refetchSlot calls getWindProfile with updated sourceId
    @Test
    public void TC21d_refetchSlot_callsRepoWithNewSource() {
        sut.addSlot(48.85, 2.35, "open_meteo");
        clearInvocations(mockRepo); // ignore addSlot's initial call

        sut.refetchSlot(0, "aviation_weather");

        verify(mockRepo).getWindProfile(eq(48.85), eq(2.35), any());
        assertEquals("aviation_weather", sut.getSlotList().get(0).sourceId);
    }
}
