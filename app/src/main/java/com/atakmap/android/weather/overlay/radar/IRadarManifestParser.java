package com.atakmap.android.weather.overlay.radar;

import com.atakmap.android.weather.data.remote.schema.ManifestParsingConfig;
import com.atakmap.android.weather.data.remote.schema.WeatherSourceDefinitionV2;

/**
 * Interface for parsing different radar manifest formats into a uniform
 * {@link RadarManifest} representation, and for building tile URLs from
 * parsed frame data.
 *
 * <p>Each radar provider (RainViewer, OpenWeatherMap, Iowa Mesonet, etc.)
 * implements this interface to handle its specific manifest JSON format
 * and tile URL construction.</p>
 *
 * @see RainViewerManifestParser
 * @see StaticTileParser
 * @see RadarManifestParserFactory
 */
public interface IRadarManifestParser {

    /**
     * Parse a manifest JSON response into a {@link RadarManifest}.
     *
     * @param manifestJson raw JSON string from the manifest endpoint
     * @param config       parsing configuration describing field names and paths;
     *                     may be null if the parser has hardcoded field names
     * @return parsed manifest with past and (optionally) future frames
     * @throws Exception if the JSON cannot be parsed or is malformed
     */
    RadarManifest parse(String manifestJson, ManifestParsingConfig config) throws Exception;

    /**
     * Build a tile URL for a specific frame and tile coordinates.
     *
     * <p>The implementation owns <em>all</em> placeholder substitution for its
     * template, {@code {apikey}} included — which is why the key is a parameter
     * rather than something the caller patches in afterwards. It used to be
     * blanked here with a comment saying it was "handled elsewhere"; it was not
     * handled anywhere, and every keyed radar source failed silently as a result
     * (finding F35). A placeholder is substituted by whoever substitutes the
     * others, or the template is a lie.</p>
     *
     * @param manifest the parsed manifest (provides host and other context)
     * @param frame    the specific frame to build a URL for
     * @param def      the v2 source definition (provides template, tile options, etc.)
     * @param z        tile zoom level
     * @param x        tile X coordinate (Web Mercator)
     * @param y        tile Y coordinate (Web Mercator)
     * @param apiKey   the resolved API key for this source, or null if the source
     *                 needs none. Implementations substitute it for
     *                 {@code {apikey}} and must not silently blank the
     *                 placeholder when the key is missing.
     * @return fully-formed tile URL ready for HTTP fetch
     */
    String buildTileUrl(RadarManifest manifest, RadarManifest.RadarFrame frame,
                        WeatherSourceDefinitionV2 def, int z, int x, int y,
                        String apiKey);
}
