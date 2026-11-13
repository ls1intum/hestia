package de.tum.cit.hestia.materials;

import de.tum.cit.hestia.TestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Base of the materials tests that need the database: the one Postgres of the whole test run. */
public abstract class MaterialsTestBase {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
        // Many differently configured Spring contexts stay cached at once, each with its own pool, and
        // they all share the one Postgres; small pools keep it below its connection limit.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
    }
}
