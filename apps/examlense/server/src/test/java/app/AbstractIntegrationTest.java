package app;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for the integration tests that need a real database — context boot,
 * Flyway migrations, entity/table validation, cascade deletes, and cross-user
 * isolation. The pure unit tests do NOT extend this and never touch Docker.
 *
 * <p>Uses the Testcontainers <em>singleton container</em> pattern: one Postgres
 * is started in a static initializer and shared by every subclass for the whole
 * JVM run. We deliberately do NOT use {@code @Testcontainers}/{@code @Container}
 * lifecycle here — that stops the container after the first test class finishes,
 * which would break every subsequent class. The container is reaped by
 * Testcontainers' Ryuk sidecar at JVM exit.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    /** Matches the docker-compose Postgres version so migrations behave identically. */
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static {
        POSTGRES.start();
    }

    /** A known auth token so the MockMvc tests can present a valid/invalid bearer. */
    public static final String TEST_TOKEN = "test-auth-token";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.auth.token", () -> TEST_TOKEN);
        // Keep uploaded files out of the working tree.
        registry.add("storage.local.base-path",
            () -> System.getProperty("java.io.tmpdir") + "/examlense-it-storage");
        // AI provider keys stay blank — providers are built lazily per request,
        // so the context boots fine without them.
    }
}
