package com.atakmap.android.weather.domain.repository;

import com.atakmap.android.weather.domain.model.WindProfileModel;

import java.util.List;

/**
 * Interface Segregation (Sprint 24 — S24.2): source for wind profile data.
 *
 * <p>Consumers that only need wind profiles (e.g., WindTabCoordinator, CBRN)
 * depend on this narrow interface instead of the full {@link IWeatherRepository}.</p>
 */
public interface IWindProfileSource {

    /** Callback for wind profile result. */
    interface WindProfileCallback {
        void onResult(List<WindProfileModel> profiles);
        void onError(String message);
    }

    /**
     * Fetch wind profile (multi-altitude) for the given coordinates.
     */
    void fetchWindProfile(double lat, double lon, WindProfileCallback callback);
}
