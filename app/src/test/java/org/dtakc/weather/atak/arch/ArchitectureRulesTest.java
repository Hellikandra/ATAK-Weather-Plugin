package org.dtakc.weather.atak.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Wave 1 — ArchUnit structural rules.
 * These run in <5 s on every PR and block merges on failure.
 *
 * Rules enforced:
 *   A1 — domain layer has no Android/ATAK imports
 *   A2 — data layer does not import UI/presentation classes
 *   A3 — UI layer does not import data.remote directly
 *   A4 — all IWeatherDataSource implementations are in data.remote
 *   A5 — domain models are immutable (no public setters)
 */
class ArchitectureRulesTest {

    private static JavaClasses allClasses;

    @BeforeAll
    static void load() {
        allClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("org.dtakc.weather.atak");
    }

    /** A1: domain layer is pure Java — no Android or ATAK SDK imports. */
    @Test
    void domainLayer_hasNoAndroidImports() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("android..", "com.atakmap..");
        rule.check(allClasses);
    }

    /** A2: data layer does not import any UI package. */
    @Test
    void dataLayer_doesNotImportUi() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..data..")
                .should().dependOnClassesThat()
                .resideInAPackage("..ui..");
        rule.check(allClasses);
    }

    /** A3: UI presenters depend on domain.repository — not on data.remote directly. */
    @Test
    void uiTabPresenters_doNotImportDataRemote() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..ui.tab..")
                .should().dependOnClassesThat()
                .resideInAPackage("..data.remote..");
        rule.check(allClasses);
    }

    /** A4: IWeatherDataSource implementations live in data.remote packages. */
    @Test
    void weatherDataSources_residInDataRemote() {
        ArchRule rule = classes()
                .that().implement("org.dtakc.weather.atak.data.remote.IWeatherDataSource")
                .should().resideInAPackage("org.dtakc.weather.atak.data.remote..");
        rule.check(allClasses);
    }

    /** A5: domain model classes expose no public setter methods. */
    @Test
    void domainModels_areImmutable() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain.model..")
                .should().haveNameMatching(".*")
                .andShould().containAnyMethodsThat()
                .haveNameMatching("set[A-Z].*");
        rule.check(allClasses);
    }
}
