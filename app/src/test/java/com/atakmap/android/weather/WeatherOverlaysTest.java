package com.atakmap.android.weather;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the overlay bundle introduced by finding F25.
 *
 * <p>The overlay types themselves cannot be instantiated in a JVM test — they
 * hold a {@code MapView} and touch the ATAK runtime. That is fine, because what
 * F25 is about is not any individual overlay: it is the <em>assembly</em>. So
 * these tests exercise the builder's contract, which is pure.</p>
 *
 * <p>The guarantee being pinned: a bundle either has all nine overlays or does
 * not exist. Before this, nine setters could each be silently skipped, and a
 * missed one produced a tab that was dead when a user opened it — weeks later,
 * with nothing pointing back to the line that forgot.</p>
 */
@DisplayName("F25 — the overlay bundle cannot be half-built")
class WeatherOverlaysTest {

    @Test
    @DisplayName("an empty builder names every missing overlay, not just the first")
    void namesAllMissing() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> WeatherOverlays.builder().build());

        String msg = e.getMessage();
        for (String required : new String[] {
                "radar", "heatmap", "sigmet", "lightning", "cbrn",
                "heatmapLegend", "windArrows", "windParticleLayer", "windParticleView" }) {
            assertTrue(msg.contains(required),
                    "the failure should name '" + required + "': " + msg);
        }
    }

    @Test
    @DisplayName("the failure points at the finding, so the next reader knows why")
    void explainsItself() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> WeatherOverlays.builder().build());
        assertTrue(e.getMessage().contains("F25"), e.getMessage());
        assertTrue(e.getMessage().contains("required"), e.getMessage());
    }

    @Test
    @DisplayName("supplying eight of nine still fails — there is no partial success")
    void rejectsNearlyComplete() {
        // Only the nulls matter here; the builder validates presence, and no
        // overlay type can be constructed without the ATAK runtime.
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> WeatherOverlays.builder()
                        .radar(null)
                        .heatmap(null)
                        .build());
        assertTrue(e.getMessage().contains("radar"), e.getMessage());
    }

    @Test
    @DisplayName("the bundle is immutable — accessors only, no setters")
    void isImmutable() {
        for (Method m : WeatherOverlays.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            if (m.isSynthetic()) continue;
            assertTrue(m.getParameterCount() == 0 || "builder".equals(m.getName()),
                    "WeatherOverlays." + m.getName() + " takes arguments; the bundle must be "
                            + "read-only once built, or F25 comes straight back");
        }
    }

    @Test
    @DisplayName("exposes exactly the nine overlays, so a tenth cannot be forgotten quietly")
    void exposesNine() {
        int accessors = 0;
        for (Method m : WeatherOverlays.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && !m.isSynthetic()
                    && m.getParameterCount() == 0 && !"builder".equals(m.getName())) {
                accessors++;
            }
        }
        assertEquals(9, accessors,
                "if you added or removed an overlay, update WeatherMapComponent's builder "
                        + "call and this count together");
    }
}
