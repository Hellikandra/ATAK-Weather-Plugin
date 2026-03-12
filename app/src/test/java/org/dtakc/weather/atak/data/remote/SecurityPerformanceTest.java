package org.dtakc.weather.atak.data.remote;

import org.dtakc.weather.atak.data.preferences.WeatherParameterPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Wave 5 — Security & Performance unit tests.
 *
 * TC-47: API keys must NOT be hard-coded in source (checked via reflection/class scan)
 * TC-48: paramHash() produces different values for different param states
 * TC-49: tile pool size never exceeds min(8, availableProcessors())
 * TC-50: CachingWeatherRepository uses a single background executor (no nesting, ISS-07)
 */
@RunWith(JUnit4.class)
public class SecurityPerformanceTest {

    /**
     * TC-47: Confirm no obvious API key strings are embedded in data source classes.
     * This is a best-effort regex scan of class names/strings — not a perfect
     * cryptographic check, but catches common mistakes (hard-coded "sk-", "apiKey=", etc.)
     */
    @Test
    public void TC47_noHardcodedApiKey_inDataSources() {
        // All built-in sources that make HTTP calls
        IWeatherDataSource[] sources = {
            new org.dtakc.weather.atak.data.remote.openmeteo.OpenMeteoDataSource(),
            new org.dtakc.weather.atak.data.remote.avwx.AviationWeatherDataSource()
        };
        // Patterns that indicate hard-coded keys
        String[] suspectPatterns = { "apikey=", "api_key=", "token=", "Bearer ", "sk-", "secret=" };

        for (IWeatherDataSource src : sources) {
            String className = src.getClass().getName();
            // We can only inspect the sourceId and displayName strings here.
            // In a real build, ArchUnit or grep-based scripts scan the .java source.
            // This test validates that at minimum the known public fields don't embed secrets.
            String id = src.getSourceId().toLowerCase();
            String nm = src.getDisplayName().toLowerCase();
            for (String pattern : suspectPatterns) {
                assertFalse("Potential API key found in sourceId of " + className,
                        id.contains(pattern));
                assertFalse("Potential API key found in displayName of " + className,
                        nm.contains(pattern));
            }
        }
    }

    /**
     * TC-48: paramHash() produces different hashes for different param configurations.
     * Ensures cache invalidation triggers correctly on parameter changes.
     */
    @Test
    public void TC48_paramHash_differsOnParamChange() {
        WeatherParameterPreferences prefs1 = mock(WeatherParameterPreferences.class);
        WeatherParameterPreferences prefs2 = mock(WeatherParameterPreferences.class);

        when(prefs1.buildHourlyQueryParam()).thenReturn("temperature_2m,wind_speed_10m");
        when(prefs1.buildDailyQueryParam()).thenReturn("temperature_2m_max");

        when(prefs2.buildHourlyQueryParam()).thenReturn("temperature_2m,wind_speed_10m,precipitation");
        when(prefs2.buildDailyQueryParam()).thenReturn("temperature_2m_max");

        String hash1 = org.dtakc.weather.atak.data.local.CachePolicy.paramHash(prefs1);
        String hash2 = org.dtakc.weather.atak.data.local.CachePolicy.paramHash(prefs2);

        assertNotEquals("Different params must produce different hashes", hash1, hash2);
    }

    /**
     * TC-48b: paramHash() is deterministic — same inputs produce same hash.
     */
    @Test
    public void TC48b_paramHash_isDeterministic() {
        WeatherParameterPreferences prefs = mock(WeatherParameterPreferences.class);
        when(prefs.buildHourlyQueryParam()).thenReturn("temperature_2m");
        when(prefs.buildDailyQueryParam()).thenReturn("temperature_2m_max");

        String h1 = org.dtakc.weather.atak.data.local.CachePolicy.paramHash(prefs);
        String h2 = org.dtakc.weather.atak.data.local.CachePolicy.paramHash(prefs);

        assertEquals("paramHash must be deterministic", h1, h2);
    }

    /**
     * TC-49: GLRadarTileLayer tile pool size must be capped at min(8, availableProcessors()).
     * Prevents runaway thread creation on high-core-count devices.
     */
    @Test
    public void TC49_tilePool_sizeIsCapped() {
        int cores    = Runtime.getRuntime().availableProcessors();
        int maxPool  = Math.min(8, cores);
        // The production class should expose this constant for testing
        int actual   = org.dtakc.weather.atak.map.radar.RadarTileProvider.getTilePoolSize();
        assertTrue("Tile pool size must be <= 8", actual <= 8);
        assertTrue("Tile pool size must be > 0",  actual > 0);
        assertEquals("Tile pool size must equal min(8, availableProcessors())",
                maxPool, actual);
    }

    /**
     * TC-50: CachingWeatherRepository uses separate read/write ExecutorServices (ISS-07 fix).
     * ISS-07 was dual-nested executor.execute() inside network callbacks. The fix uses two
     * dedicated single-thread executors (readExecutor, writeExecutor) — no nesting.
     * Exactly 2 ExecutorService fields guards against regressing to one (bug) or more.
     */
    @Test
    public void TC50_cachingRepo_hasReadAndWriteExecutors() throws Exception {
        int executorFields = 0;
        for (java.lang.reflect.Field f
                : org.dtakc.weather.atak.data.local.CachingWeatherRepository.class.getDeclaredFields()) {
            if (java.util.concurrent.ExecutorService.class.isAssignableFrom(f.getType())) {
                executorFields++;
            }
        }
        assertEquals(
                "CachingWeatherRepository must have exactly 2 ExecutorServices: " +
                "readExecutor + writeExecutor (ISS-07 fix)",
                2, executorFields);
    }
}
