package de.tum.cit.hestia.materials;

import de.tum.cit.hestia.TestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Base of the materials tests that need the database: the one Postgres of the whole test run. */
public abstract class MaterialsTestBase {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }
}
