package com.atakmap.android.weather.data.cache;

/**
 * Abstraction that allows consumers (e.g. WeatherViewModel) to subscribe to
 * cache-status events without depending on the concrete CachingWeatherRepository.
 *
 * <p>Refactoring note: WeatherViewModel previously contained an
 * {@code instanceof CachingWeatherRepository} check to wire the cache badge.
 * WeatherViewModel now checks {@code instanceof CacheStatusProvider} instead,
 * keeping it decoupled from the concrete caching implementation.</p>
 *
 * Usage:
 * <pre>
 *   if (repository instanceof CacheStatusProvider) {
 *       ((CacheStatusProvider) repository)
 *           .setCacheStatusListener((status, label) -> cacheBadge.setValue(label));
 *   }
 * </pre>
 */
public interface CacheStatusProvider {

    /**
     * Register a listener that will be notified on the main thread whenever
     * the cache status changes for any in-flight request.
     *
     * @param listener the listener to register; {@code null} removes any existing listener.
     */
    void setCacheStatusListener(CachingWeatherRepository.CacheStatusListener listener);
}
