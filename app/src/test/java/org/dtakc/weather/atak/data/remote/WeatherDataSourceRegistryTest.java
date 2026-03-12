package org.dtakc.weather.atak.data.remote;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Wave 5 — WeatherDataSourceRegistry unit tests.
 *
 * TC-44: destroyInstance() resets singleton (hot-swap fix, ISS-02)
 * TC-44b: new instance after destroy re-registers built-in sources
 * TC-44c: ConcurrentHashMap — 50 concurrent reads while writing (ISS-15)
 */
class WeatherDataSourceRegistryTest {

    @AfterEach
    void tearDown() {
        WeatherDataSourceRegistry.destroyInstance();
    }

    private Context mockContext() {
        Context ctx = mock(Context.class);
        Context appCtx = mock(Context.class);
        when(ctx.getApplicationContext()).thenReturn(appCtx);
        SharedPreferences prefs = mock(SharedPreferences.class);
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        when(prefs.getString(anyString(), any())).thenReturn(null);
        when(prefs.edit()).thenReturn(editor);
        when(editor.putString(anyString(), anyString())).thenReturn(editor);
        when(appCtx.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);
        return ctx;
    }

    // TC-44: destroyInstance() makes getInstance() return a new object
    @Test
    void TC44_destroyInstance_resetsSingleton() {
        Context ctx = mockContext();
        WeatherDataSourceRegistry first = WeatherDataSourceRegistry.getInstance(ctx);
        WeatherDataSourceRegistry.destroyInstance();
        WeatherDataSourceRegistry second = WeatherDataSourceRegistry.getInstance(ctx);
        assertNotSame(first, second, "After destroyInstance(), a new registry must be created");
    }

    // TC-44b: new registry still has all built-in sources
    @Test
    void TC44b_newInstance_hasBuiltinSources() {
        Context ctx = mockContext();
        WeatherDataSourceRegistry.destroyInstance();
        WeatherDataSourceRegistry registry = WeatherDataSourceRegistry.getInstance(ctx);
        assertTrue(registry.getSourceCount() >= 4,
                "Registry must have at least 4 built-in sources after fresh creation");
    }

    // TC-44c: 50 threads read getAvailableEntries() while 5 threads register new sources
    @Test
    void TC44c_concurrentReadWrite_noException() throws InterruptedException {
        Context ctx = mockContext();
        WeatherDataSourceRegistry registry = WeatherDataSourceRegistry.getInstance(ctx);

        int readers = 50, writers = 5;
        CountDownLatch start  = new CountDownLatch(1);
        CountDownLatch done   = new CountDownLatch(readers + writers);
        AtomicInteger errors  = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(readers + writers);

        for (int i = 0; i < readers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    List<WeatherDataSourceRegistry.SourceEntry> entries =
                            registry.getAvailableEntries();
                    // Just iterate — should never throw
                    for (WeatherDataSourceRegistry.SourceEntry e : entries) {
                        assertNotNull(e.sourceId);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        for (int i = 0; i < writers; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // Register a mock source
                    IWeatherDataSource mockSrc = mock(IWeatherDataSource.class);
                    when(mockSrc.getSourceId()).thenReturn("test_source_" + idx);
                    when(mockSrc.getDisplayName()).thenReturn("Test " + idx);
                    when(mockSrc.getSupportedParameters())
                            .thenReturn(java.util.Collections.emptyList());
                    registry.register(mockSrc);
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown(); // release all threads simultaneously
        assertTrue(done.await(10, TimeUnit.SECONDS),
                "All threads should complete within 10s");
        pool.shutdownNow();

        assertEquals(0, errors.get(),
                "No exceptions should occur during concurrent read/write");
    }
}
