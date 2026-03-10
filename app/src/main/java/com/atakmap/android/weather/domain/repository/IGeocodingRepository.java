package com.atakmap.android.weather.domain.repository;

/**
 * Contract for reverse geocoding — decouples UI from Nominatim specifics.
 */
public interface IGeocodingRepository {

    interface Callback {
        void onSuccess(String displayName);
        void onError(String message);
    }

    /**
     * Resolve a lat/lon pair to a human-readable location name.
     */
    void reverseGeocode(double latitude, double longitude, Callback callback);
}
