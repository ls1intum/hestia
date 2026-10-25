package de.tum.cit.hestia.learninggoalhub;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * The packages form layers: llm and course at the bottom, extraction and exam at the top. A class
 * that reaches back up a layer makes two packages depend on each other, and this test fails.
 */
class PackageCycleTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("de.tum.cit.hestia.learninggoalhub");

    @Test
    void packagesAreFreeOfCycles() {
        slices().matching("de.tum.cit.hestia.learninggoalhub.(**)")
                .should().beFreeOfCycles()
                .check(CLASSES);
    }
}
