package com.atakmap.android.weather.domain.service;

import com.atakmap.android.weather.domain.model.WeatherModel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the briefing generator — and the point of finding F27.
 *
 * <p>These are plain JUnit tests with no Robolectric, no {@code Context} and no
 * Android runtime. That was impossible before F27: {@link BriefingDocument}
 * carried methods that raised Toasts and started share Intents, so exercising
 * the briefing at all meant standing up an Android environment for logic that
 * is string formatting. Splitting the presentation off left a value type and a
 * pure function, which is what the tests below rest on.</p>
 *
 * <p>The assertions deliberately check structure and content rather than exact
 * layout — a briefing is formatted prose, and pinning every space would make
 * the tests fail on every wording change without catching a single defect.</p>
 */
@DisplayName("F27 — briefing generation is pure and testable")
class BriefingGeneratorTest {

    private static WeatherModel liege() {
        return new WeatherModel.Builder(50.63, 5.57)
                .locationName("Liege")
                .temperatureMin(4.0)
                .temperatureMax(11.5)
                .apparentTemperature(6.0)
                .humidity(82)
                .pressure(1013)
                .visibility(9000)
                .windSpeed(18.0)
                .windDirection(225)
                .weatherCode(61)
                .servedBy("Open-Meteo (GFS)")
                .build();
    }

    @Test
    @DisplayName("names the location and the source in the briefing")
    void includesLocationAndSource() {
        BriefingDocument doc = BriefingGenerator.generate(
                liege(), null, null, null, "Liege", "Open-Meteo (GFS)");

        assertNotNull(doc);
        String text = doc.getPlainText();
        assertTrue(text.contains("Liege"), "location must appear in the briefing");
        assertTrue(text.contains("Open-Meteo (GFS)"), "source must appear in the briefing");
        assertTrue(doc.getTitle().contains("Liege"), doc.getTitle());
    }

    @Test
    @DisplayName("formats coordinates with the correct hemisphere letters")
    void formatsHemispheres() {
        String north = BriefingGenerator.generate(
                liege(), null, null, null, "Liege", "src").getPlainText();
        assertTrue(north.contains("50.63°N"), north.substring(0, 200));
        assertTrue(north.contains("5.57°E"), north.substring(0, 200));

        WeatherModel southWest = new WeatherModel.Builder(-33.87, -70.65)
                .locationName("Santiago").build();
        String south = BriefingGenerator.generate(
                southWest, null, null, null, "Santiago", "src").getPlainText();
        assertTrue(south.contains("33.87°S"), south.substring(0, 200));
        assertTrue(south.contains("70.65°W"), south.substring(0, 200));
    }

    @Test
    @DisplayName("omits the forecast sections when there is no forecast data")
    void omitsEmptySections() {
        String text = BriefingGenerator.generate(
                liege(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), "Liege", "src").getPlainText();

        assertTrue(text.contains("CURRENT CONDITIONS"));
        assertFalse(text.contains("24-HOUR FORECAST"), "no hourly data, so no hourly section");
        assertFalse(text.contains("7-DAY OUTLOOK"), "no daily data, so no daily section");
        assertFalse(text.contains("WIND PROFILE"), "no wind data, so no wind section");
    }

    @Test
    @DisplayName("produces a briefing rather than throwing when there is no data at all")
    void handlesNoData() {
        BriefingDocument doc = BriefingGenerator.generate(
                null, null, null, null, null, null);

        assertNotNull(doc);
        assertTrue(doc.getPlainText().contains("No weather data"));
        assertTrue(doc.getHtml().contains("No weather data"));
    }

    @Test
    @DisplayName("falls back to placeholder names rather than printing null")
    void fallsBackOnMissingNames() {
        String text = BriefingGenerator.generate(
                liege(), null, null, null, null, null).getPlainText();

        assertTrue(text.contains("Unknown Location"), "null location must not print as null");
        assertTrue(text.contains("Unknown Source"), "null source must not print as null");
        assertFalse(text.contains("null"), "no null should reach the rendered briefing");
    }

    @Test
    @DisplayName("emits both a plain text and an HTML rendering")
    void emitsBothFormats() {
        BriefingDocument doc = BriefingGenerator.generate(
                liege(), null, null, null, "Liege", "src");

        assertFalse(doc.getPlainText().isEmpty());
        assertTrue(doc.getHtml().contains("<html"), "HTML rendering must be HTML");
        assertFalse(doc.getPlainText().contains("<html"), "plain text must not be HTML");
        assertTrue(doc.getGeneratedTime() > 0);
    }

    @Test
    @DisplayName("BriefingDocument is a value type — it only holds and returns")
    void documentIsPure() {
        BriefingDocument doc = new BriefingDocument("text", "<html></html>", "Title", 1700000000L);

        assertEquals("text", doc.getPlainText());
        assertEquals("<html></html>", doc.getHtml());
        assertEquals("Title", doc.getTitle());
        assertEquals(1700000000L, doc.getGeneratedTime());

        // The guard behind finding F27: this class must expose nothing that
        // needs an Android runtime. It used to carry copyToClipboard(Context),
        // share(Context) and two saveToFile(Context) methods.
        for (java.lang.reflect.Method m : BriefingDocument.class.getDeclaredMethods()) {
            for (Class<?> p : m.getParameterTypes()) {
                assertFalse(p.getName().startsWith("android."),
                        "BriefingDocument." + m.getName() + " takes an Android type");
            }
        }
    }
}
