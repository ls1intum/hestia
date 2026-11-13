package de.tum.cit.hestia;

import java.nio.file.Path;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * The test database of the whole server: one Postgres for the JVM run, set up like production —
 * database {@code hestia}, the roles from {@code infra/postgres/init/01-roles.sql}, Flyway as
 * {@code hestia_owner}, the application as {@code hestia_app}.
 *
 * <p>Use it from a test base class:
 * <pre>{@code
 * @DynamicPropertySource
 * static void database(DynamicPropertyRegistry registry) { TestDatabase.register(registry); }
 * }</pre>
 * The container starts on first use and is shared by every test class (Testcontainers' singleton
 * container pattern); Ryuk or the JVM exit removes it.
 */
public final class TestDatabase {

    static final String OWNER_PASSWORD = "test-owner";
    static final String APP_PASSWORD = "test-app";

    /** Relative to the server project, where Gradle runs the tests. */
    private static final Path ROLES_SCRIPT = Path.of("..", "infra", "postgres", "init", "01-roles.sql");

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("hestia")
            // Every cached Spring context of the run keeps its own pool open on this one database.
            .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=300")
            .withEnv("HESTIA_OWNER_PASSWORD", OWNER_PASSWORD)
            .withEnv("HESTIA_APP_PASSWORD", APP_PASSWORD)
            .withCopyFileToContainer(MountableFile.forHostPath(ROLES_SCRIPT), "/docker-entrypoint-initdb.d/01-roles.sql");

    private TestDatabase() {}

    /** Starts the container if needed and points the datasource and Flyway at it. */
    public static void register(DynamicPropertyRegistry registry) {
        start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "hestia_app");
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", () -> "hestia_owner");
        registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
        // Many differently configured Spring contexts stay cached at once, each with its own pool, and
        // they all share the one Postgres; small pools keep it below its connection limit.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
    }

    /** The running container, e.g. for a test that connects with plain JDBC. */
    public static PostgreSQLContainer<?> container() {
        start();
        return POSTGRES;
    }

    private static synchronized void start() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
    }
}
