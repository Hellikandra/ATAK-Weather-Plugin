package com.atakmap.android.weather.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

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

    // ── No rule here is ratcheted any more ────────────────────────────────────
    //
    // Two of these five used to be. Both described an architecture the project
    // intended but did not have, and both failed outright — which made the whole
    // suite red, and a permanently red suite is one nobody reads. That is how the
    // compile errors in this source set went unnoticed for months.
    //
    // So the counts were frozen instead: the rules ran in full and failed only on
    // an INCREASE. The domain rule was ratcheted at 16 violations and reached 0
    // when F26 deleted the dead code holding it up. This one started at 121, and
    // came down in four steps — F26 to 101, F30 to 100, F35 to 98, and F22 to 0.
    //
    // A ratchet is a way to hold a line while the real fix is written, not a
    // substitute for writing it. Both have now been written, so both rules assert
    // again. Do not reintroduce a baseline here: if one of these starts failing,
    // the dependency it caught belongs behind an interface.

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
    }

    // ── Rule 1: Domain isolation from Android ──────────────────────────────────

    @Test
    @DisplayName("Domain classes must not import Android framework classes")
    void domain_shouldNotImport_androidFramework() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "android..",
                        "androidx.."
                )
                .because("The domain layer must be pure Java — no Android dependencies");

        // Widened from ..domain.model.. to the whole layer when F27 was fixed.
        // The narrow version was why BriefingDocument could sit in domain.service
        // raising Toasts and starting share Intents for two years without any
        // rule objecting: it was a service, not a model, so nothing looked.
        //
        // Services and repository ports are as much the domain as the models
        // are. If this fails, the Android-facing half of whatever you are
        // writing belongs in presentation.
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

        // No longer ratcheted. F22 took this from 98 to 0 by introducing
        // domain.repository.SourceCatalog: presentation asks what sources exist
        // and which is active, and never learns that definitions are JSON on
        // disk or that the registry is a singleton. It was 121 at b814271.
        //
        // If this fails, something in presentation has reached for
        // WeatherSourceManager or SourceDefinitionLoader again. Add the
        // operation to SourceCatalog instead of restoring the baseline.
        rule.check(classes);
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
