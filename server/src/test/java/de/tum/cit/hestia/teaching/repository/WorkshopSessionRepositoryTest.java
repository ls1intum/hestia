package de.tum.cit.hestia.teaching.repository;

import de.tum.cit.hestia.TestDatabase;
import de.tum.cit.hestia.core.TestLogin;
import de.tum.cit.hestia.core.internal.identity.IdentityService;
import de.tum.cit.hestia.teaching.model.WorkshopSessionEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The session queries, run for an organisation as in a request (tenant filter and row-level security on). */
@SpringBootTest
class WorkshopSessionRepositoryTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @MockitoBean
    RelyingPartyRegistrationRepository relyingPartyRegistrationRepository;

    @Autowired WorkshopSessionRepository repository;
    @Autowired IdentityService identities;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String uniA = "repo-a-" + run + ".test";
    private final String uniB = "repo-b-" + run + ".test";

    private WorkshopSessionEntity session(UUID createdBy, Integer order, String title) {
        WorkshopSessionEntity e = new WorkshopSessionEntity();
        e.setCreatedBy(createdBy);
        e.setDisplayOrder(order);
        e.setTitle(title);
        return e;
    }

    @Test
    void ownSessionsWithoutCourseComeOrdered() {
        UUID a = TestLogin.login(identities, uniA, "alice").userId();
        UUID b = TestLogin.login(identities, uniA, "bob").userId();
        List<WorkshopSessionEntity> found = TestLogin.runAs(identities, uniA, "alice", () -> {
            repository.saveAll(List.of(session(a, 2, "second"), session(a, 1, "first"), session(b, null, "bobs")));
            return repository.findWithoutCourseOf(a);
        });
        assertThat(found).extracting(WorkshopSessionEntity::getTitle).containsExactly("first", "second");
    }

    @Test
    void anotherOrganisationDoesNotSeeTheRows() {
        UUID a = TestLogin.login(identities, uniA, "alice").userId();
        UUID other = TestLogin.runAs(identities, uniB, "alice", () -> TestLogin.login(identities, uniB, "alice").userId());
        String id = TestLogin.runAs(identities, uniA, "alice", () -> repository.save(session(a, null, "mine")).getId());

        assertThat(TestLogin.runAs(identities, uniB, "alice", () -> repository.findById(id))).isEmpty();
        assertThat(TestLogin.runAs(identities, uniB, "alice", () -> repository.findWithoutCourseOf(a))).isEmpty();
        assertThat(TestLogin.runAs(identities, uniB, "alice", () -> repository.findWithoutCourseOf(other))).isEmpty();
        assertThat(TestLogin.runAs(identities, uniA, "alice", () -> repository.findById(id))).isPresent();
    }

    @Test
    void queriesAreParameterised() {
        UUID a = TestLogin.login(identities, uniA, "alice").userId();
        String jsonInjection = "'; DROP TABLE workshop_sessions; --";
        Optional<WorkshopSessionEntity> retrieved = TestLogin.runAs(identities, uniA, "alice", () -> {
            WorkshopSessionEntity e = session(a, null, "Injection Session");
            e.setSessionJson(jsonInjection);
            return repository.findById(repository.save(e).getId());
        });
        // Stored and read back as the literal text
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getSessionJson()).isEqualTo(jsonInjection);
    }
}
