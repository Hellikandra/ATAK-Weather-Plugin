package org.dtakc.weather.atak.data.cache;

import org.dtakc.weather.atak.data.local.CachePolicy;
import org.dtakc.weather.atak.data.local.CachingWeatherRepository;
import org.dtakc.weather.atak.data.local.WeatherDao;
import org.dtakc.weather.atak.data.local.entity.WeatherSnapshot;
import org.dtakc.weather.atak.data.preferences.WeatherParameterPreferences;
import org.dtakc.weather.atak.data.remote.NetworkWeatherRepository;
import org.dtakc.weather.atak.domain.model.LocationSource;
import org.dtakc.weather.atak.domain.model.WeatherModel;
import org.dtakc.weather.atak.domain.repository.IWeatherRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Wave 2 — CachingWeatherRepository unit tests.
 * Uses in-memory WeatherDao mock + fake network.
 *
 * TC-05: cache HIT → no network call
 * TC-06: cache MISS → network called → result written to DAO
 * TC-07: stale param hash → cache bypassed → network called
 * TC-08: wind TTL — second call within 30 min returns cached data (no network)
 * TC-09: wind fetch always calls callback on main thread (async)
 */
@ExtendWith(MockitoExtension.class)
class CachingWeatherRepositoryTest {

    @Mock NetworkWeatherRepository  mockNetwork;
    @Mock WeatherDao                mockDao;
    @Mock WeatherParameterPreferences mockPrefs;

    private CachingWeatherRepository sut;
    private static final double LAT = 48.8567, LON = 2.3508;

    @BeforeEach
    void setUp() {
        when(mockPrefs.buildHourlyQueryParam()).thenReturn("");
        when(mockPrefs.buildDailyQueryParam()).thenReturn("");
        sut = new CachingWeatherRepository(mockNetwork, mockDao, mockPrefs);
        sut.setCurrentSource(LocationSource.MAP_CENTRE);
    }

    // TC-05: fresh cache snapshot → network never called
    @Test
    void TC05_cacheHit_doesNotCallNetwork() throws InterruptedException {
        WeatherSnapshot snap = freshSnapshot();
        when(mockNetwork.isStaleForCurrentSource()).thenReturn(false);
        when(mockDao.findFreshSnapshot(anyDouble(), anyDouble(), anyString(),
                anyString(), anyLong())).thenReturn(snap);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<WeatherModel> result = new AtomicReference<>();
        sut.getCurrentWeather(LAT, LON, new IWeatherRepository.Callback<WeatherModel>() {
            @Override public void onSuccess(WeatherModel r) { result.set(r); latch.countDown(); }
            @Override public void onError(String msg)       { latch.countDown(); }
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Callback not fired within 2s");
        assertNotNull(result.get(), "Expected success result");
        verify(mockNetwork, never()).getCurrentWeather(anyDouble(), anyDouble(), any());
    }

    // TC-06: no cache entry → network called → DAO write executed
    @Test
    void TC06_cacheMiss_callsNetworkAndWritesDao() throws InterruptedException {
        when(mockNetwork.isStaleForCurrentSource()).thenReturn(false);
        when(mockDao.findFreshSnapshot(anyDouble(), anyDouble(), anyString(),
                anyString(), anyLong())).thenReturn(null);

        WeatherModel networkResult = new WeatherModel.Builder(LAT, LON)
                .temperatureMax(22.0).build();
        doAnswer(inv -> {
            IWeatherRepository.Callback<WeatherModel> cb = inv.getArgument(2);
            cb.onSuccess(networkResult);
            return null;
        }).when(mockNetwork).getCurrentWeather(anyDouble(), anyDouble(), any());

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<WeatherModel> result = new AtomicReference<>();
        sut.getCurrentWeather(LAT, LON, new IWeatherRepository.Callback<WeatherModel>() {
            @Override public void onSuccess(WeatherModel r) { result.set(r); latch.countDown(); }
            @Override public void onError(String msg)       { latch.countDown(); }
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(networkResult, result.get());
        // DAO write happens on writeExecutor — give it a moment
        Thread.sleep(100);
        verify(mockDao, atLeastOnce()).insertSnapshot(any());
    }

    // TC-07: source marked stale → cache bypassed → network called
    @Test
    void TC07_staleSource_bypassesCache() throws InterruptedException {
        when(mockNetwork.isStaleForCurrentSource()).thenReturn(true);

        WeatherModel networkResult = new WeatherModel.Builder(LAT, LON).build();
        doAnswer(inv -> {
            IWeatherRepository.Callback<WeatherModel> cb = inv.getArgument(2);
            cb.onSuccess(networkResult);
            return null;
        }).when(mockNetwork).getCurrentWeather(anyDouble(), anyDouble(), any());

        CountDownLatch latch = new CountDownLatch(1);
        sut.getCurrentWeather(LAT, LON, new IWeatherRepository.Callback<WeatherModel>() {
            @Override public void onSuccess(WeatherModel r) { latch.countDown(); }
            @Override public void onError(String msg)       { latch.countDown(); }
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        verify(mockDao, never()).findFreshSnapshot(anyDouble(), anyDouble(),
                anyString(), anyString(), anyLong());
        verify(mockNetwork).getCurrentWeather(anyDouble(), anyDouble(), any());
    }

    // TC-08: wind profile second call within TTL → network NOT called twice
    @Test
    void TC08_windCache_secondCallWithinTtl_noNetworkCall() throws InterruptedException {
        // First call populates in-memory cache
        doAnswer(inv -> {
            IWeatherRepository.Callback<java.util.List<org.dtakc.weather.atak.domain.model.WindProfileModel>> cb = inv.getArgument(2);
            cb.onSuccess(new java.util.ArrayList<>());
            return null;
        }).when(mockNetwork).getWindProfile(anyDouble(), anyDouble(), any());

        CountDownLatch l1 = new CountDownLatch(1);
        sut.getWindProfile(LAT, LON, new IWeatherRepository.Callback<java.util.List<org.dtakc.weather.atak.domain.model.WindProfileModel>>() {
            @Override public void onSuccess(java.util.List<org.dtakc.weather.atak.domain.model.WindProfileModel> r) { l1.countDown(); }
            @Override public void onError(String m) { l1.countDown(); }
        });
        assertTrue(l1.await(2, TimeUnit.SECONDS));

        // Second call within TTL
        CountDownLatch l2 = new CountDownLatch(1);
        sut.getWindProfile(LAT, LON, new IWeatherRepository.Callback<java.util.List<org.dtakc.weather.atak.domain.model.WindProfileModel>>() {
            @Override public void onSuccess(java.util.List<org.dtakc.weather.atak.domain.model.WindProfileModel> r) { l2.countDown(); }
            @Override public void onError(String m) { l2.countDown(); }
        });
        assertTrue(l2.await(2, TimeUnit.SECONDS));

        // Network should have been called exactly once
        verify(mockNetwork, times(1)).getWindProfile(anyDouble(), anyDouble(), any());
    }

    // TC-09: wind profile callback fires asynchronously (never synchronously on call thread)
    @Test
    void TC09_windFetch_callbackIsAsync() throws InterruptedException {
        Thread callThread = Thread.currentThread();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();

        doAnswer(inv -> {
            IWeatherRepository.Callback<java.util.List<org.dtakc.weather.atak.domain.model.WindProfileModel>> cb = inv.getArgument(2);
            cb.onSuccess(new java.util.ArrayList<>());
            return null;
        }).when(mockNetwork).getWindProfile(anyDouble(), anyDouble(), any());

        CountDownLatch latch = new CountDownLatch(1);
        sut.getWindProfile(LAT, LON, new IWeatherRepository.Callback<java.util.List<org.dtakc.weather.atak.domain.model.WindProfileModel>>() {
            @Override public void onSuccess(java.util.List<org.dtakc.weather.atak.domain.model.WindProfileModel> r) {
                callbackThread.set(Thread.currentThread());
                latch.countDown();
            }
            @Override public void onError(String m) { latch.countDown(); }
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertNotSame(callThread, callbackThread.get(),
                "Wind profile callback must not fire on the calling thread (ISS-04)");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private WeatherSnapshot freshSnapshot() {
        WeatherSnapshot s = new WeatherSnapshot();
        s.id = 1L;
        s.lat = CachePolicy.roundCoord(LAT);
        s.lon = CachePolicy.roundCoord(LON);
        s.source = LocationSource.MAP_CENTRE.name();
        s.paramHash = CachePolicy.paramHash(mockPrefs);
        s.fetchedAt = System.currentTimeMillis();
        s.expiresAt = s.fetchedAt + CachePolicy.TTL_MS;
        s.temperatureMax = 20.0;
        s.requestTimestamp = "2024-01-01 12:00";
        return s;
    }
}
