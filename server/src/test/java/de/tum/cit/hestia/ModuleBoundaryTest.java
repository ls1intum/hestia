package de.tum.cit.hestia;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * The modules are the direct sub-packages of {@code de.tum.cit.hestia}. A domain module (materials,
 * teaching, assessment) may use {@code core} and its ports in {@code core.spi}, never another domain
 * module; {@code core} uses no other module. The allowed dependencies are declared in each module's
 * {@code package-info.java}; this test fails on any other dependency and on cycles.
 */
class ModuleBoundaryTest {

    static final ApplicationModules MODULES = ApplicationModules.of(HestiaApplication.class);

    @Test
    void modulesOnlyUseWhatTheyAreAllowedTo() {
        MODULES.verify();
    }
}
