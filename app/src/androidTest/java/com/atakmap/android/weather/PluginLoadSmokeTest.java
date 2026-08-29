package com.atakmap.android.weather;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.atakmap.android.weather.plugin.BuildConfig;

import org.junit.After;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * On-device smoke test: does the plugin load into ATAK without breaking it?
 *
 * <h3>Why UI Automator and not Espresso</h3>
 *
 * An ATAK plugin has no launchable Activity — it is an APK loaded at runtime into
 * the host's process, and the {@code com.atakmap.app.component} manifest entry is a
 * stub with no class behind it. Espresso starts by launching an Activity you own,
 * so it has nothing to launch here, and instrumentation targeting the plugin's
 * package cannot see views owned by {@code com.atakmap.app.civ}. UI Automator drives
 * <i>other</i> apps through the accessibility layer, which is exactly what is needed.
 * Espresso was a declared dependency for the life of the project and never had a
 * test written against it; it has been removed.
 *
 * <h3>What this guards</h3>
 *
 * Every load-time defect this plugin has shipped would have been caught here:
 * <ul>
 *   <li><b>#20 / #26</b> — the map going black when the plugin opened. Asserted
 *       directly by screenshotting and checking the map area still has colour
 *       variety, rather than merely checking nothing crashed.</li>
 *   <li><b>#18</b> — {@code mkdir failed: ENOENT} from asking the plugin context
 *       for SharedPreferences.</li>
 *   <li>The v3.1.1 first-cut regression — {@code Resource ID ... is not valid} from
 *       inflating a plugin layout with the host context.</li>
 * </ul>
 *
 * <h3>Running it</h3>
 *
 * <pre>
 *   adb install -r &lt;plugin apk&gt;          # ATAK and the plugin must both be installed
 *   ./gradlew connectedCivDebugAndroidTest
 * </pre>
 *
 * <h3>Honest limitations</h3>
 *
 * The selectors below match ATAK's UI by visible text, because the host's resource
 * ids are not part of the plugin SDK. Text can change between ATAK versions; when a
 * selector stops matching, the failure message names what to adjust. Treat a
 * selector failure as "the test needs updating", not "the plugin is broken" — the
 * logcat assertions are the part that finds real defects.
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class PluginLoadSmokeTest {

    /** The host. Taken from BuildConfig so it follows the flavour rather than drifting. */
    private static final String ATAK_PACKAGE = BuildConfig.ATAK_PACKAGE_NAME;

    private static final long LAUNCH_TIMEOUT_MS = 40_000;
    private static final long UI_TIMEOUT_MS     = 10_000;

    /**
     * Log fragments that mean a load-time defect. Each one has actually shipped in
     * this plugin at least once.
     */
    private static final String[] FATAL_LOG_PATTERNS = {
            "mkdir failed: ENOENT",          // issue #18 — prefs on the plugin context
            "is not valid",                  // "Resource ID ... is not valid" — wrong context to inflate
            "FATAL EXCEPTION",
            "AndroidRuntime: Process: " + ATAK_PACKAGE,
    };

    /** The panel sections, by the text shown in the top bar. */
    private static final List<String> TABS =
            Arrays.asList("Weather", "Wind", "Overlays", "Markers", "Settings");

    private UiDevice device;

    @Before
    public void launchAtak() throws Exception {
        device = UiDevice.getInstance(getInstrumentation());
        assertNotNull("UiDevice unavailable — is this running as an instrumentation test?", device);

        // Clear logcat first so the assertions only see this run. executeShellCommand
        // runs with the shell user's privileges, which is what lets the test read
        // ATAK's log lines and not just its own process.
        device.executeShellCommand("logcat -c");

        device.pressHome();
        Context ctx = getInstrumentation().getContext();
        Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(ATAK_PACKAGE);
        assertNotNull("ATAK (" + ATAK_PACKAGE + ") is not installed on this device", intent);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        ctx.startActivity(intent);

        assertTrue("ATAK did not reach the foreground within " + LAUNCH_TIMEOUT_MS + "ms",
                device.wait(Until.hasObject(By.pkg(ATAK_PACKAGE).depth(0)), LAUNCH_TIMEOUT_MS));

        // ATAK shows load prompts and a splash before the map settles.
        Thread.sleep(8_000);
    }

    @After
    public void assertNoLoadTimeFaults() throws Exception {
        String log = device.executeShellCommand("logcat -d");
        List<String> hits = new ArrayList<>();
        for (String line : log.split("\n")) {
            for (String pattern : FATAL_LOG_PATTERNS) {
                if (line.contains(pattern)) { hits.add(line.trim()); break; }
            }
        }
        if (!hits.isEmpty()) {
            StringBuilder sb = new StringBuilder(
                    "Load-time faults in logcat (" + hits.size() + "):\n");
            for (int i = 0; i < Math.min(hits.size(), 15); i++) sb.append("  ").append(hits.get(i)).append('\n');
            fail(sb.toString());
        }
    }

    // ── 1. The plugin opens and the map survives it ─────────────────────────

    @Test
    public void a_pluginOpensAndMapKeepsRendering() throws Exception {
        int coloursBefore = distinctColoursInMapArea();
        assertTrue("The map looks blank before the plugin was even opened — "
                        + "only " + coloursBefore + " distinct colours sampled. "
                        + "Check ATAK has a map layer loaded before running this test.",
                coloursBefore > 8);

        openWeatherPlugin();

        int coloursAfter = distinctColoursInMapArea();
        assertTrue("The map went blank when the plugin opened: distinct colours in the "
                        + "map area fell from " + coloursBefore + " to " + coloursAfter + ". "
                        + "This is issues #20 and #26 — a plugin view painted over the host "
                        + "map surface.",
                coloursAfter > 8);
    }

    // ── 2. Every section opens ──────────────────────────────────────────────

    @Test
    public void b_everySectionOpens() throws Exception {
        openWeatherPlugin();

        List<String> missing = new ArrayList<>();
        for (String tab : TABS) {
            UiObject2 nav = device.wait(Until.findObject(By.text(tab)), UI_TIMEOUT_MS);
            if (nav == null) { missing.add(tab); continue; }
            nav.click();
            Thread.sleep(1_500);   // let the section inflate and bind
        }
        assertTrue("Panel sections not reachable: " + missing
                        + ". Either the plugin failed to inflate them, or the top-bar labels "
                        + "changed and TABS needs updating.",
                missing.isEmpty());
    }

    // ── 3. Closing the plugin leaves ATAK intact ────────────────────────────

    @Test
    public void c_closingLeavesMapIntact() throws Exception {
        openWeatherPlugin();
        device.pressBack();
        Thread.sleep(2_000);
        device.pressBack();
        Thread.sleep(2_000);

        int colours = distinctColoursInMapArea();
        assertTrue("The map is blank after closing the plugin (" + colours + " distinct "
                        + "colours). An overlay view was added to the MapView and never "
                        + "removed — see the detach() paths in the overlay classes.",
                colours > 8);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void openWeatherPlugin() throws Exception {
        // Already open?
        if (device.hasObject(By.text("Overlays"))) return;

        UiObject2 menu = device.wait(Until.findObject(By.desc("Toolbar Menu")), UI_TIMEOUT_MS);
        if (menu == null) menu = device.wait(Until.findObject(By.desc("Show Menu")), 2_000);
        if (menu == null) menu = device.wait(Until.findObject(By.descContains("Menu")), 2_000);
        assertNotNull("Could not find ATAK's menu button. Its content-description differs "
                + "on this ATAK version; update openWeatherPlugin().", menu);
        menu.click();
        Thread.sleep(1_500);

        UiObject2 weather = device.wait(Until.findObject(By.text("Weather")), UI_TIMEOUT_MS);
        assertNotNull("The Weather plugin is not listed in ATAK's menu — it failed to load. "
                + "Check logcat for a plugin-loader error.", weather);
        weather.click();
        Thread.sleep(4_000);   // panel inflate + first fetch
    }

    /**
     * Count distinct colours in the left third of the screen — the part the map
     * occupies while the plugin panel is docked on the right.
     *
     * <p>A rendering map produces dozens of distinct colours in a coarse sample. A
     * map painted over by an opaque view produces one or two. That difference is a
     * direct test for the black-screen defect, which no amount of "did it crash"
     * checking would catch: nothing crashed when it happened, the map simply went
     * black and stayed that way until ATAK restarted.
     */
    private int distinctColoursInMapArea() throws Exception {
        File shot = File.createTempFile("wx-smoke", ".png",
                getInstrumentation().getTargetContext().getCacheDir());
        try {
            assertTrue("Screenshot failed", device.takeScreenshot(shot));
            Bitmap bmp = BitmapFactory.decodeFile(shot.getAbsolutePath());
            assertNotNull("Screenshot could not be decoded", bmp);

            int w = bmp.getWidth(), h = bmp.getHeight();
            int right = Math.max(1, w / 3);           // left third: map, not panel
            Set<Integer> colours = new HashSet<>();
            for (int y = h / 6; y < h * 5 / 6; y += Math.max(1, h / 40)) {
                for (int x = w / 20; x < right; x += Math.max(1, w / 40)) {
                    // Quantise to 5 bits per channel so JPEG-ish noise does not
                    // inflate the count on a genuinely blank screen.
                    int c = bmp.getPixel(x, y);
                    colours.add(((c >> 19) & 0x1F) << 10
                            | ((c >> 11) & 0x1F) << 5
                            | ((c >> 3) & 0x1F));
                }
            }
            bmp.recycle();
            return colours.size();
        } finally {
            //noinspection ResultOfMethodCallIgnored
            shot.delete();
        }
    }
}
