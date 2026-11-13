package de.tum.cit.hestia.assessment;

import de.tum.cit.hestia.TestDatabase;
import de.tum.cit.hestia.assessment.user.User;
import de.tum.cit.hestia.assessment.user.UserRepository;
import de.tum.cit.hestia.assessment.user.UserService;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for the integration tests that need a real database — context boot,
 * Flyway migrations, entity/table validation, cascade deletes, and cross-user
 * isolation. The pure unit tests do NOT extend this and never touch Docker.
 *
 * <p>The database is {@link TestDatabase}: one Postgres for the whole JVM run, set up
 * like production (Flyway as {@code hestia_owner}, the application as {@code hestia_app}).
 * All requests go to the module prefix {@code /examlense}.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    @MockBean
    private RelyingPartyRegistrationRepository relyingPartyRegistrationRepository;

    /**
     * A known auth token so the MockMvc tests can present a valid/invalid bearer.
     * Resolves to the seeded legacy user ({@link de.tum.cit.hestia.assessment.shared.DefaultUser#ID}) via the
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
        TestDatabase.register(registry);
        // Spring caches one context per distinct configuration, all against the one test
        // Postgres; small pools keep their connections below its max_connections.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
        registry.add("app.auth.token", () -> TEST_TOKEN);
        registry.add("app.auth.admin-token", () -> TEST_ADMIN_TOKEN);
        // Keep uploaded files out of the working tree.
        registry.add("storage.local.base-path",
            () -> System.getProperty("java.io.tmpdir") + "/examlense-it-storage");
        // AI provider keys stay blank — providers are built lazily per request,
        // so the context boots fine without them.
    }
}
