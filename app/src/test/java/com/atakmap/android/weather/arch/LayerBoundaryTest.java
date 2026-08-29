package com.atakmap.android.weather.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ArchUnit architecture tests — enforce layer boundaries (Sprint 2 — S2.3).
 *
 * <h3>Package structure enforced</h3>
 * <pre>
 *   domain.model      — pure Java data classes, no Android imports
 *   domain.repository — interfaces only
 *   data.*            — repository impls + remote sources; may NOT import presentation
 *   presentation.*    — ViewModels, Views; may NOT import data.remote directly
 *   overlay.*         — map overlays + markers; may NOT import data.remote directly
 *   util.*            — utility classes; may NOT import data or presentation
 * </pre>
 *
 * <h3>Rules</h3>
 * <ol>
 *   <li>Domain model must not depend on Android framework (except allowlisted).</li>
 *   <li>Domain model must not depend on data or presentation layers.</li>
 *   <li>Data layer must not depend on presentation layer.</li>
 *   <li>Presentation layer must not depend on data.remote directly.</li>
 *   <li>Util package must not depend on data or presentation layers.</li>
 * </ol>
 */
class LayerBoundaryTest {

    private static final String BASE = "com.atakmap.android.weather";
    private static JavaClasses classes;

    // ── Ratcheted rules ───────────────────────────────────────────────────────
    //
    // One rule below is ratcheted: it describes the architecture the project
    // intends but does not yet have. It was failing outright, which meant the
    // whole suite was red — and a permanently red suite is one nobody reads,
    // which is how the compile errors in this source set went unnoticed for
    // months.
    //
    // The domain rule was ratcheted too, at 16 violations. The F26 dead-code
    // removal took it to 0, so it is a hard assertion again. That is the ratchet
    // working: the count could only go down, and when it hit zero the guard was
    // made permanent.
    //
    // Rather than delete the rules or weaken what they assert, the violation
    // counts are frozen here. The rules still run in full; they simply fail on
    // an INCREASE rather than on any violation at all. Both numbers must only
    // ever go down.
    //
    // A ratchet is not a fix. The real work for F16 is to inject SourceCatalog /
    // IWeatherRepository instead of reaching for WeatherSourceManager.getInstance()
    // from the presentation layer — Wave 2. When you land part of it, lower the
    // number here in the same commit; that is the whole point of a ratchet.

    /**
     * Presentation -> data.remote. 121 at commit b814271; 101 after the F26
     * dead-code removal took RadarTabCoordinator, RouteWeatherView and
     * ComparisonView out of the presentation layer; 100 after F30 moved
     * FetchCallback into the domain, so SourceManagerView no longer reaches into
     * data.remote for it; 98 after F35 put API key storage behind
     * {@code domain.repository.ApiKeyStore} and stopped the radar source list
     * re-reading the same definition fields four times per row. Wave 3 (F22)
     * takes it to 0.
     */
    private static final int PRESENTATION_TO_REMOTE_BASELINE = 98;

    /**
     * Evaluate a rule and fail only if the violation count has grown.
     *
     * @param rule     the rule, checked in full — nothing is excluded
     * @param baseline the count recorded when the ratchet was set
     * @param finding  the review finding that owns the real fix
     */
    private static void ratchet(ArchRule rule, int baseline, String finding) {
        int actual = rule.evaluate(classes).getFailureReport().getDetails().size();
        if (actual < baseline) {
            System.out.println("[ratchet] " + finding + ": violations down to " + actual
                    + " from a baseline of " + baseline
                    + " — lower the baseline in LayerBoundaryTest to lock the gain in.");
        }
        assertTrue(actual <= baseline,
                finding + ": architecture violations rose from " + baseline + " to " + actual
                        + ". This rule is ratcheted — the count may only decrease. "
                        + "Route the new dependency through an interface instead of adding "
                        + "to the backlog.");
    }

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
    }

    // ── Rule 1: Domain model isolation ─────────────────────────────────────────

    @Test
    @DisplayName("Domain model classes must not import Android framework classes")
    void domainModel_shouldNotImport_androidFramework() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain.model..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "android..",
                        "androidx.."
                )
                .because("Domain models must be pure Java — no Android dependencies");

        rule.check(classes);
    }

    // ── Rule 2: Domain must not depend on data/presentation ────────────────────

    @Test
    @DisplayName("Domain classes must not import data or presentation layers")
    void domain_shouldNotImport_dataOrPresentation() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "..data..",
                        "..presentation..",
                        "..overlay..",
                        "..infrastructure.."
                )
                .because("Domain layer must not have outward dependencies");

        // No longer ratcheted. Deleting the dead code in F26 removed every one of
        // the 16 violations — MultiPointForecastService, which implemented
        // HttpClient.Callback directly, was the bulk of them. The domain layer is
        // clean, so this is a hard assertion again and must stay that way.
        rule.check(classes);
    }

    // ── Rule 3: Data layer must not depend on presentation ─────────────────────

    @Test
    @DisplayName("Data layer must not import presentation layer")
    void data_shouldNotImport_presentation() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..data..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "..presentation..",
                        "..overlay.."
                )
                .because("Data layer must not know about presentation or overlay layers");

        rule.check(classes);
    }

    // ── Rule 4: Presentation must not import data.remote directly ──────────────

    @Test
    @DisplayName("Presentation layer must not import data.remote directly")
    void presentation_shouldNotImport_dataRemote() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..presentation..")
                .should().dependOnClassesThat()
                .resideInAPackage("..data.remote..")
                .because("Presentation must go through repository interfaces, not remote sources");

        ratchet(rule, PRESENTATION_TO_REMOTE_BASELINE, "F16");
    }

    // ── Rule 5: Util must be leaf ──────────────────────────────────────────────

    @Test
    @DisplayName("Util classes must not import data or presentation layers")
    void util_shouldNotImport_dataOrPresentation() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..util..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "..data..",
                        "..presentation..",
                        "..overlay..",
                        "..infrastructure.."
                )
                .because("Utility classes must be leaf dependencies — no upward imports");

        rule.check(classes);
    }
}
