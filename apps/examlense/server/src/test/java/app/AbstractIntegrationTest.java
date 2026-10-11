package app;

import app.user.User;
import app.user.UserRepository;
import app.user.UserService;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
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

    @MockBean
    private RelyingPartyRegistrationRepository relyingPartyRegistrationRepository;

    static {
        POSTGRES.start();
    }

    /**
     * A known auth token so the MockMvc tests can present a valid/invalid bearer.
     * Resolves to the seeded legacy user ({@link app.shared.DefaultUser#ID}) via the
     * shared-token bootstrap path, which is why tests written before per-user auth
     * still authenticate as a valid owner.
     */
    public static final String TEST_TOKEN = "test-auth-token";

    /**
     * The separate admin bootstrap secret. Distinct from {@link #TEST_TOKEN} because
     * the shared token deliberately does NOT grant admin — it is committed to a
     * public repository, so admin behind it would be admin for anyone who reads it.
     */
    public static final String TEST_ADMIN_TOKEN = "test-admin-bootstrap-token";

    @Autowired private UserRepository userRepository;
    @Autowired private UserService userService;

    /**
     * Create a real user with a usable token. Needed because {@code exams.owner_id}
     * is now a foreign key: seeding an exam against a random UUID no longer works,
     * and a genuine second identity is what makes cross-user isolation testable at
     * the HTTP boundary rather than only at the repository.
     */
    protected TestUser createUser(String externalId) {
        User user = userService.findOrCreateByExternalId(externalId, externalId);
        return new TestUser(user.getId(), userService.mintToken(user.getId(), "test"));
    }

    protected TestUser createAdmin(String externalId) {
        User user = userService.findOrCreateByExternalId(externalId, externalId);
        user.setAdmin(true);
        userRepository.save(user);
        return new TestUser(user.getId(), userService.mintToken(user.getId(), "test-admin"));
    }

    protected record TestUser(UUID id, String token) {}

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.auth.token", () -> TEST_TOKEN);
        registry.add("app.auth.admin-token", () -> TEST_ADMIN_TOKEN);
        // Keep uploaded files out of the working tree.
        registry.add("storage.local.base-path",
            () -> System.getProperty("java.io.tmpdir") + "/examlense-it-storage");
        // AI provider keys stay blank — providers are built lazily per request,
        // so the context boots fine without them.
    }
}
