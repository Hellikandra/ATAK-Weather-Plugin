package org.dtakc.weather.atak.ui.viewmodel;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.Observer;

import org.dtakc.weather.atak.data.local.CachingWeatherRepository;
import org.dtakc.weather.atak.domain.model.LocationSource;
import org.dtakc.weather.atak.domain.model.LocationSnapshot;
import org.dtakc.weather.atak.domain.model.WeatherModel;
import org.dtakc.weather.atak.domain.repository.IGeocodingRepository;
import org.dtakc.weather.atak.domain.repository.IWeatherRepository;
import org.dtakc.weather.atak.ui.UiState;
import org.dtakc.weather.atak.ui.WeatherViewModel;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Wave 3 — WeatherViewModel unit tests.
 *
 * InstantTaskExecutorRule swaps LiveData's background executor with a
 * synchronous one so assertions run immediately after postValue().
 *
 * TC-17: loadWeather success emits UiState.success on currentWeather
 * TC-18: loadWeather error emits UiState.error on currentWeather
 * TC-19: loadWeather emits activeLocation with correct coords
 * TC-20: loadWeatherWithFallback uses self when GPS available,
 *         falls back to map-centre when lat/lon = 0
 */
@RunWith(JUnit4.class)
public class WeatherViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantTaskRule = new InstantTaskExecutorRule();

    @Mock CachingWeatherRepository mockRepo;
    @Mock IGeocodingRepository      mockGeocoding;

    private WeatherViewModel sut;
    private static final double LAT = 48.85, LON = 2.35;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        sut = new WeatherViewModel(mockRepo, mockGeocoding);
    }

    // TC-17: successful load emits UiState.success with data
    @Test
    public void TC17_loadWeather_success_emitsSuccessState() {
        WeatherModel expected = new WeatherModel.Builder(LAT, LON)
                .temperatureMax(25.0).build();

        doAnswer(inv -> {
            IWeatherRepository.Callback<WeatherModel> cb = inv.getArgument(2);
            cb.onSuccess(expected);
            return null;
        }).when(mockRepo).getCurrentWeather(anyDouble(), anyDouble(), any());

        // Stub geocoding to return a snapshot so the VM doesn't crash
        doAnswer(inv -> {
            IGeocodingRepository.Callback cb = inv.getArgument(2);
            cb.onSuccess(new LocationSnapshot(LAT, LON, "Paris", LocationSource.MAP_CENTRE));
            return null;
        }).when(mockGeocoding).reverseGeocode(anyDouble(), anyDouble(), any());

        List<UiState<WeatherModel>> states = new ArrayList<>();
        sut.getCurrentWeather().observeForever(states::add);

        sut.loadWeather(LAT, LON, LocationSource.MAP_CENTRE);

        // Should have: LOADING → SUCCESS
        assertTrue("At least 2 states (loading + result)", states.size() >= 2);
        UiState<WeatherModel> last = states.get(states.size() - 1);
        assertTrue("Last state should be success", last.isSuccess());
        assertEquals(expected, last.getData());
    }

    // TC-18: network error emits UiState.error
    @Test
    public void TC18_loadWeather_networkError_emitsErrorState() {
        doAnswer(inv -> {
            IWeatherRepository.Callback<WeatherModel> cb = inv.getArgument(2);
            cb.onError("Network timeout");
            return null;
        }).when(mockRepo).getCurrentWeather(anyDouble(), anyDouble(), any());

        List<UiState<WeatherModel>> states = new ArrayList<>();
        sut.getCurrentWeather().observeForever(states::add);

        sut.loadWeather(LAT, LON, LocationSource.MAP_CENTRE);

        UiState<WeatherModel> last = states.get(states.size() - 1);
        assertTrue("Error state expected", last.isError());
        assertNotNull("Error message must not be null", last.getErrorMessage());
    }

    // TC-19: activeLocation emitted with correct coordinates and source
    @Test
    public void TC19_loadWeather_emitsActiveLocation() {
        WeatherModel result = new WeatherModel.Builder(LAT, LON).build();
        doAnswer(inv -> {
            IWeatherRepository.Callback<WeatherModel> cb = inv.getArgument(2);
            cb.onSuccess(result);
            return null;
        }).when(mockRepo).getCurrentWeather(anyDouble(), anyDouble(), any());

        doAnswer(inv -> {
            IGeocodingRepository.Callback cb = inv.getArgument(2);
            cb.onSuccess(new LocationSnapshot(LAT, LON, "TestLoc", LocationSource.MAP_CENTRE));
            return null;
        }).when(mockGeocoding).reverseGeocode(anyDouble(), anyDouble(), any());

        List<LocationSnapshot> locations = new ArrayList<>();
        sut.getActiveLocation().observeForever(locations::add);

        sut.loadWeather(LAT, LON, LocationSource.MAP_CENTRE);

        assertFalse("activeLocation must emit at least once", locations.isEmpty());
        LocationSnapshot snap = locations.get(locations.size() - 1);
        assertEquals(LocationSource.MAP_CENTRE, snap.getSource());
        assertEquals(LAT, snap.getLatitude(), 1e-6);
        assertEquals(LON, snap.getLongitude(), 1e-6);
    }

    // TC-20: loadWeatherWithFallback — uses self when GPS valid, map-centre when 0,0
    @Test
    public void TC20_fallback_usesSelfWhenGpsAvailable() {
        double selfLat = 51.5, selfLon = -0.12;
        double cenLat  = 48.85, cenLon = 2.35;

        doAnswer(inv -> {
            IWeatherRepository.Callback<WeatherModel> cb = inv.getArgument(2);
            cb.onSuccess(new WeatherModel.Builder(inv.getArgument(0), inv.getArgument(1)).build());
            return null;
        }).when(mockRepo).getCurrentWeather(anyDouble(), anyDouble(), any());

        doAnswer(inv -> {
            IGeocodingRepository.Callback cb = inv.getArgument(2);
            cb.onSuccess(new LocationSnapshot((double)inv.getArgument(0),
                    (double)inv.getArgument(1), "loc", LocationSource.SELF_MARKER));
            return null;
        }).when(mockGeocoding).reverseGeocode(anyDouble(), anyDouble(), any());

        List<LocationSnapshot> locs = new ArrayList<>();
        sut.getActiveLocation().observeForever(locs::add);

        // GPS available → should use selfLat/selfLon
        sut.loadWeatherWithFallback(selfLat, selfLon, cenLat, cenLon);

        assertFalse(locs.isEmpty());
        ArgumentCaptor<Double> latCaptor = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Double> lonCaptor = ArgumentCaptor.forClass(Double.class);
        verify(mockRepo).getCurrentWeather(latCaptor.capture(), lonCaptor.capture(), any());
        assertEquals(selfLat, latCaptor.getValue(), 1e-6);
        assertEquals(selfLon, lonCaptor.getValue(), 1e-6);
    }

    @Test
    public void TC20b_fallback_usesMapCentreWhenNoGps() {
        double cenLat = 48.85, cenLon = 2.35;

        doAnswer(inv -> {
            IWeatherRepository.Callback<WeatherModel> cb = inv.getArgument(2);
            cb.onSuccess(new WeatherModel.Builder(cenLat, cenLon).build());
            return null;
        }).when(mockRepo).getCurrentWeather(anyDouble(), anyDouble(), any());

        doAnswer(inv -> {
            IGeocodingRepository.Callback cb = inv.getArgument(2);
            cb.onSuccess(new LocationSnapshot(cenLat, cenLon, "loc", LocationSource.MAP_CENTRE));
            return null;
        }).when(mockGeocoding).reverseGeocode(anyDouble(), anyDouble(), any());

        // self = 0,0 → fallback to map-centre
        sut.loadWeatherWithFallback(0.0, 0.0, cenLat, cenLon);

        ArgumentCaptor<Double> latCaptor = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Double> lonCaptor = ArgumentCaptor.forClass(Double.class);
        verify(mockRepo).getCurrentWeather(latCaptor.capture(), lonCaptor.capture(), any());
        assertEquals(cenLat, latCaptor.getValue(), 1e-6);
        assertEquals(cenLon, lonCaptor.getValue(), 1e-6);
    }
}
