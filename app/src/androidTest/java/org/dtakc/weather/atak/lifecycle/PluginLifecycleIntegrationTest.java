package org.dtakc.weather.atak.lifecycle;

import android.content.Context;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.dtakc.weather.atak.data.remote.WeatherDataSourceRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/**
 * Wave 6 — Integration tests run on-device or emulator.
 *
 * These test the observable contract of the plugin lifecycle:
 *
 * TC-02: Plugin unload — destroyInstance() called → registry resets (ISS-02,03)
 * TC-03: Hot-swap — registry returns fresh instance after destroy
 * TC-33: WeatherDataSourceRegistry.destroyInstance() called on lifecycle destroy
 *
 * NOTE: These are shell tests — they validate wiring contracts without launching
 * a full ATAK session. In a complete CI environment they run against a test APK
 * with an instrumented WeatherLifecycle stub.
 */
@RunWith(AndroidJUnit4.class)
public class PluginLifecycleIntegrationTest {

    private Context appContext;

    @Before
    public void setUp() {
        appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Ensure clean state
        WeatherDataSourceRegistry.destroyInstance();
    }

    @After
    public void tearDown() {
        WeatherDataSourceRegistry.destroyInstance();
    }

    /**
     * TC-02 / TC-03: After destroyInstance() the next getInstance() returns a
     * new object with the same source count — simulating a hot-swap cycle.
     */
    @Test
    public void TC02_hotSwap_registryFreshAfterDestroy() {
        WeatherDataSourceRegistry first = WeatherDataSourceRegistry.getInstance(appContext);
        int sourceCountBefore = first.getSourceCount();
        assertTrue("Registry must have sources after first creation", sourceCountBefore > 0);

        // Simulate plugin unload
        WeatherDataSourceRegistry.destroyInstance();

        // Simulate plugin reload
        WeatherDataSourceRegistry second = WeatherDataSourceRegistry.getInstance(appContext);
        assertNotSame("Hot-swap must produce a new registry instance", first, second);
        assertEquals("Source count must be the same after hot-swap",
                sourceCountBefore, second.getSourceCount());
    }

    /**
     * TC-33: active source persists across a destroy/recreate cycle
     * (preference-backed selection should survive).
     */
    @Test
    public void TC33_activeSource_persistsAcrossRecreate() {
        WeatherDataSourceRegistry registry = WeatherDataSourceRegistry.getInstance(appContext);
        // Set a specific source
        String firstId = registry.getAvailableEntries().get(0).sourceId;
        registry.setActiveSourceId(firstId);

        // Simulate destroy + recreate
        WeatherDataSourceRegistry.destroyInstance();
        WeatherDataSourceRegistry reloaded = WeatherDataSourceRegistry.getInstance(appContext);

        assertEquals("Active source ID must persist across recreate",
                firstId, reloaded.getActiveSourceId());
    }
}
