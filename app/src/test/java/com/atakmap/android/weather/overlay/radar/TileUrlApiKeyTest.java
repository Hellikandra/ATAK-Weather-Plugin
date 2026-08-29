package com.atakmap.android.weather.overlay.radar;

import com.atakmap.android.weather.data.remote.schema.AuthConfig;
import com.atakmap.android.weather.data.remote.schema.AuthProvider;
import com.atakmap.android.weather.data.remote.schema.WeatherSourceDefinitionV2;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for finding F35 - radar tile API keys were never injected.
 *
 * <p>{@code StaticTileParser} substituted every placeholder in the tile URL
 * template except the API key one, which it replaced with the empty string
 * under a comment saying injection was handled elsewhere. It was not handled
 * anywhere: {@code AuthProvider} was only ever reached from the weather path.
 * The bundled OpenWeatherMap radar source therefore requested every tile with
 * an empty {@code appid} and got a 401 that nothing logged and nothing
 * surfaced.</p>
 *
 * <p>These tests pin the two properties that were missing: the key reaches the
 * URL when there is one, and no URL is produced at all when there is not.</p>
 */
@DisplayName("F35 - radar tile API key injection")
class TileUrlApiKeyTest {

    /** The bundled OpenWeatherMap template, verbatim. */
    private static final String OWM_TEMPLATE =
            "https://tile.openweathermap.org/map/precipitation_new/{z}/{x}/{y}.png?appid={apikey}";

    private static WeatherSourceDefinitionV2 owmDef() {
        return new WeatherSourceDefinitionV2.Builder()
                .radarSourceId("openweathermap-radar")
                .displayName("OpenWeatherMap Radar")
                .type("radar")
                .manifestFormat("static")
                .tileUrlTemplate(OWM_TEMPLATE)
                .tileSize(256)
                .auth(new AuthConfig.Builder().type("apiKey").queryParam("appid").build())
                .build();
    }

    private static RadarManifest staticManifest() {
        return new RadarManifest.Builder()
                .host(null)
                .past(Collections.singletonList(new RadarManifest.RadarFrame(1700000000L, null)))
                .future(Collections.<RadarManifest.RadarFrame>emptyList())
                .generatedTime(1700000000L)
                .build();
    }

    @Nested
    @DisplayName("StaticTileParser")
    class Static {

        @Test
        @DisplayName("puts the key in the URL instead of blanking the placeholder")
        void substitutesTheKey() {
            RadarManifest m = staticManifest();
            String url = new StaticTileParser().buildTileUrl(
                    m, m.getPast().get(0), owmDef(), 5, 16, 11, "abc123");

            assertEquals("https://tile.openweathermap.org/map/precipitation_new/5/16/11.png"
                    + "?appid=abc123", url);
            assertFalse(url.contains("apikey"), "placeholder must not survive");
        }

        @Test
        @DisplayName("builds no URL at all when a required key is missing")
        void refusesWithoutAKey() {
            RadarManifest m = staticManifest();
            StaticTileParser parser = new StaticTileParser();

            // The pre-fix behaviour produced a well-formed URL with an empty
            // appid, which the provider rejects. An empty string stops the
            // request from being made at all.
            assertEquals("", parser.buildTileUrl(m, m.getPast().get(0), owmDef(), 5, 16, 11, null));
            assertEquals("", parser.buildTileUrl(m, m.getPast().get(0), owmDef(), 5, 16, 11, ""));
        }

        @Test
        @DisplayName("keyless sources are unaffected")
        void keylessSourceStillWorks() {
            WeatherSourceDefinitionV2 mesonet = new WeatherSourceDefinitionV2.Builder()
                    .radarSourceId("iowa-mesonet-mrms")
                    .type("radar")
                    .manifestFormat("static")
                    .tileUrlTemplate("https://mesonet.example/{z}/{x}/{y}.png")
                    .auth(new AuthConfig.Builder().type("none").build())
                    .build();

            RadarManifest m = staticManifest();
            assertEquals("https://mesonet.example/5/16/11.png",
                    new StaticTileParser().buildTileUrl(
                            m, m.getPast().get(0), mesonet, 5, 16, 11, null));
        }
    }

    @Nested
    @DisplayName("RainViewerManifestParser")
    class RainViewer {

        @Test
        @DisplayName("honours the key placeholder for an imported source in the same format")
        void substitutesTheKey() {
            WeatherSourceDefinitionV2 def = new WeatherSourceDefinitionV2.Builder()
                    .radarSourceId("keyed-rainviewer-clone")
                    .type("radar")
                    .manifestFormat("rainviewer")
                    .tileUrlTemplate("https://tiles.example{path}/{size}/{z}/{x}/{y}.png?key={apikey}")
                    .auth(new AuthConfig.Builder().type("apiKey").queryParam("key").build())
                    .build();

            RadarManifest m = new RadarManifest.Builder()
                    .host("https://tiles.example")
                    .past(Collections.singletonList(
                            new RadarManifest.RadarFrame(1700000000L, "/v2/radar/1700000000")))
                    .future(Collections.<RadarManifest.RadarFrame>emptyList())
                    .generatedTime(1700000000L)
                    .build();

            String url = new RainViewerManifestParser().buildTileUrl(
                    m, m.getPast().get(0), def, 5, 16, 11, "zzz");

            assertTrue(url.endsWith("?key=zzz"), url);
            assertFalse(url.contains("apikey"));
        }
    }

    @Nested
    @DisplayName("AuthProvider.applyToUrl")
    class QueryParamAuth {

        private AuthConfig queryAuth() {
            return new AuthConfig.Builder().type("queryParam").queryParam("appid").build();
        }

        @Test
        @DisplayName("appends the configured parameter name, not a hardcoded one")
        void usesConfiguredParamName() {
            assertEquals("https://x.example/m.json?appid=k",
                    AuthProvider.applyToUrl("https://x.example/m.json", queryAuth(), "k"));
        }

        @Test
        @DisplayName("uses an ampersand when the URL already has a query string")
        void respectsExistingQuery() {
            assertEquals("https://x.example/m.json?a=1&appid=k",
                    AuthProvider.applyToUrl("https://x.example/m.json?a=1", queryAuth(), "k"));
        }

        @Test
        @DisplayName("leaves the URL alone when there is no key")
        void noKeyNoChange() {
            assertEquals("https://x.example/m.json",
                    AuthProvider.applyToUrl("https://x.example/m.json", queryAuth(), null));
        }

        @Test
        @DisplayName("header auth leaves the URL alone and produces a header")
        void headerAuthGoesInHeaders() {
            AuthConfig auth = new AuthConfig.Builder().type("bearer").build();
            assertEquals("https://x.example/m.json",
                    AuthProvider.applyToUrl("https://x.example/m.json", auth, "k"));
            assertEquals("Bearer k", AuthProvider.getAuthHeaders(auth, "k").get("Authorization"));
        }
    }
}
