package org.dtakc.weather.atak.domain.repository;

import org.dtakc.weather.atak.domain.model.LocationSnapshot;
import org.dtakc.weather.atak.domain.model.LocationSource;

/**
 * Contract for reverse geocoding.
 *
 * Sprint 1 change: the callback now returns a full LocationSnapshot instead
 * of a bare String.  This ensures the ViewModel always has the resolved name,
 * the exact coordinates, and the LocationSource bundled together — eliminating
 * the "Unknown location" bug caused by passing a name string without the
 * context of which position it belongs to.
 */
public interface IGeocodingRepository {

    interface Callback {
        /**
         * Called on success with a fully resolved LocationSnapshot.
         * The snapshot's displayName is the Nominatim result if available,
         * or a formatted "lat, lon" string as a fallback — never null.
         */
        void onSuccess(LocationSnapshot snapshot);

        /** Called when the network request itself fails. */
        void onError(String message);
    }

    /**
     * Resolve a lat/lon pair to a LocationSnapshot.
     *
     * @param latitude   WGS-84 latitude
     * @param longitude  WGS-84 longitude
     * @param source     which map position this coordinate represents
     * @param callback   result or error
     */
    void reverseGeocode(double latitude, double longitude,
                        LocationSource source,
                        Callback callback);
}
