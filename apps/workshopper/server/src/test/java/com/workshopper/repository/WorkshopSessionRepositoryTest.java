package com.workshopper.repository;

import com.workshopper.model.WorkshopSessionEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WorkshopSessionRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Let Hibernate manage schema creation for this test
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired
    private WorkshopSessionRepository repository;

    @Test
    void shouldFindAllOrdered() {
        WorkshopSessionEntity e1 = new WorkshopSessionEntity();
        e1.setTitle("Session 1");
        e1.setDisplayOrder(2);
        
        WorkshopSessionEntity e2 = new WorkshopSessionEntity();
        e2.setTitle("Session 2");
        e2.setDisplayOrder(1);

        WorkshopSessionEntity e3 = new WorkshopSessionEntity();
        e3.setTitle("Session 3");
        // Null displayOrder should be last (NULLS LAST)

        repository.saveAll(List.of(e1, e2, e3));

        List<WorkshopSessionEntity> all = repository.findAllOrdered();
        assertThat(all).hasSize(3);
        assertThat(all.get(0).getTitle()).isEqualTo("Session 2"); // order 1
        assertThat(all.get(1).getTitle()).isEqualTo("Session 1"); // order 2
        assertThat(all.get(2).getTitle()).isEqualTo("Session 3"); // order null
    }

    @Test
    void shouldFindByOwnerIdOrdered() {
        WorkshopSessionEntity e1 = new WorkshopSessionEntity();
        e1.setOwnerId("userA");
        e1.setDisplayOrder(2);

        WorkshopSessionEntity e2 = new WorkshopSessionEntity();
        e2.setOwnerId("userA");
        e2.setDisplayOrder(1);

        WorkshopSessionEntity e3 = new WorkshopSessionEntity();
        e3.setOwnerId("userB");

        repository.saveAll(List.of(e1, e2, e3));

        List<WorkshopSessionEntity> userA = repository.findByOwnerIdOrdered("userA");
        assertThat(userA).hasSize(2);
        assertThat(userA.get(0).getOwnerId()).isEqualTo("userA");
        assertThat(userA.get(0).getDisplayOrder()).isEqualTo(1);
    }

    @Test
    void shouldNotBeVulnerableToSqlInjection() {
        // A classic SQL injection payload attempting to bypass a WHERE clause
        String sqlInjectionPayload = "userA' OR '1'='1";
        
        WorkshopSessionEntity e1 = new WorkshopSessionEntity();
        e1.setOwnerId(sqlInjectionPayload);
        e1.setTitle("Injection Session");
        
        WorkshopSessionEntity e2 = new WorkshopSessionEntity();
        e2.setOwnerId("userA");
        e2.setTitle("Normal Session");
        
        repository.saveAll(List.of(e1, e2));
        
        // When we query by exactly the payload, JPA should parameterize it safely
        // and return ONLY the record where ownerId literally matches the payload.
        List<WorkshopSessionEntity> results = repository.findByOwnerIdOrdered(sqlInjectionPayload);
        
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTitle()).isEqualTo("Injection Session");
        
        // 2. Querying by normal user should be unaffected
        List<WorkshopSessionEntity> normalResults = repository.findByOwnerIdOrdered("userA");
        assertThat(normalResults).hasSize(1);
        assertThat(normalResults.get(0).getTitle()).isEqualTo("Normal Session");
        
        // 3. Explicitly prove the WHERE clause cannot be bypassed:
        // Pass a malicious payload against normal data and verify it returns ZERO rows 
        // (rather than evaluating to true and matching everything).
        List<WorkshopSessionEntity> bypassAttempt = repository.findByOwnerIdOrdered("fakeUser' OR '1'='1");
        assertThat(bypassAttempt).isEmpty();
        
        // 4. Attempting a second injection pattern in JSON fields to ensure no DB execution
        String jsonInjection = "'; DROP TABLE workshop_sessions; --";
        WorkshopSessionEntity e3 = new WorkshopSessionEntity();
        e3.setOwnerId("userB");
        e3.setSessionJson(jsonInjection);
        WorkshopSessionEntity saved = repository.save(e3);
        
        // Should retrieve exactly the literal text
        Optional<WorkshopSessionEntity> retrieved = repository.findById(saved.getId());
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getSessionJson()).isEqualTo(jsonInjection);
    }
}
