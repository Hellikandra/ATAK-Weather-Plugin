package com.atakmap.android.weather.data.remote.schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates provider-specific weather codes to WMO standard codes.
 *
 * <p>If the provider already uses WMO codes (e.g., Open-Meteo), the mapping
 * is null or empty and this translator acts as a pass-through.</p>
 *
 * <p>For providers that use their own code systems, the mapping converts
 * provider codes to the nearest WMO equivalent so that downstream UI code
 * (icons, descriptions, tactical conditions) can work uniformly.</p>
 */
public class WeatherCodeTranslator {

    private final Map<String, Integer> mapping; // providerCode (as string) -> WMO code

    /**
     * Create a translator.
     *
     * @param weatherCodeMapping provider code string -> WMO int mapping.
     *                           Pass null for pass-through (codes are already WMO).
     */
    public WeatherCodeTranslator(Map<String, Integer> weatherCodeMapping) {
        if (weatherCodeMapping != null && !weatherCodeMapping.isEmpty()) {
            this.mapping = Collections.unmodifiableMap(
                    new LinkedHashMap<>(weatherCodeMapping));
        } else {
            this.mapping = null;
        }
    }

    /**
     * Translate a provider weather code to WMO.
     *
     * @param providerCode the code from the API response
     * @return the WMO equivalent, or the original code if no mapping is defined
     */
    public int translate(int providerCode) {
        if (mapping == null) {
            return providerCode;
        }
        Integer wmo = mapping.get(String.valueOf(providerCode));
        return wmo != null ? wmo : providerCode;
    }

    /**
     * Returns {@code true} if codes are already WMO (no translation needed).
     */
    public boolean isPassThrough() {
        return mapping == null;
    }
}
