package de.tum.cit.hestia.assessment;

import de.tum.cit.hestia.TestDatabase;
import de.tum.cit.hestia.core.CourseApi;
import de.tum.cit.hestia.core.OrgContext;
import de.tum.cit.hestia.core.TestLogin;
import de.tum.cit.hestia.core.internal.identity.IdentityService;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Base for the integration tests that need a real database: context boot, Flyway migrations,
 * entity/table validation, cascade deletes, and isolation between users and organisations. The pure
 * unit tests do NOT extend this and never touch Docker.
 *
 * <p>The database is {@link TestDatabase}: one Postgres for the whole JVM run, set up like
 * production (Flyway as {@code hestia_owner}, the application as {@code hestia_app}). All requests
 * go to the module prefix {@code /examlense}. Users log in through the same
 * {@link IdentityService} as the SAML login ({@link TestLogin}); every test instance works in
 * its own organisation, so tests do not see each other's rows.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    @MockitoBean
    private RelyingPartyRegistrationRepository relyingPartyRegistrationRepository;

    @Autowired protected IdentityService identities;
    @Autowired protected CourseApi courseApi;

    /** Direct repository access in a test needs the organisation, like any thread of the server. */
    @BeforeEach
    void bindOrganisation() {
        OrgContext.set(orgId());
    }

    @AfterEach
    void unbindOrganisation() {
        OrgContext.set(null);
    }

    /** The organisation (schacHomeOrganization) of this test instance. */
    protected final String uni = "assessment-" + UUID.randomUUID().toString().substring(0, 8) + ".test";

    /** A request of {@code user} in this test's organisation, with a CSRF token. */
    protected RequestPostProcessor as(String user) {
        return TestLogin.as(identities, uni, user);
    }

    protected RequestPostProcessor asIn(String otherOrg, String user) {
        return TestLogin.as(identities, otherOrg, user);
    }

    /** The id of {@code user} in this test's organisation (created on first use). */
    protected UUID userId(String user) {
        return TestLogin.login(identities, uni, user).userId();
    }

    /** The personal course of {@code user}: where an exam without a chosen course goes. */
    protected UUID courseOf(String user) {
        return runAs(user, () -> courseApi.personalCourse().id());
    }

    protected UUID orgId() {
        return TestLogin.login(identities, uni, "bootstrap").orgId();
    }

    /** Runs {@code action} as {@code user} of this test's organisation, outside a request. */
    protected <T> T runAs(String user, Supplier<T> action) {
        return TestLogin.runAs(identities, uni, user, action);
    }

    protected void runAs(String user, Runnable action) {
        TestLogin.runAs(identities, uni, user, () -> {
            action.run();
            return null;
        });
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
        // Spring caches one context per distinct configuration, all against the one test
        // Postgres; small pools keep their connections below its max_connections.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
        // Keep uploaded files out of the working tree.
        registry.add("storage.local.base-path",
            () -> System.getProperty("java.io.tmpdir") + "/examlense-it-storage");
        registry.add("app.files.signing-secret", () -> "test-signing-secret");
        // AI provider keys stay blank — providers are built lazily per request,
        // so the context boots fine without them.
    }
}
