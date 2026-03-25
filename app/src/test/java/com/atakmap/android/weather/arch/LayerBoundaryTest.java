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
