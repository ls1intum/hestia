package de.tum.cit.hestia.assessment;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The single most valuable pre-deploy test: prove the whole application boots.
 * If the Spring context loads against a real Postgres, then bean wiring is
 * intact, the async pools and security chain constructed, Flyway ran V1–V4
 * cleanly, and Hibernate's {@code ddl-auto=validate} confirmed every entity
 * matches its table. A broken migration or entity drift fails right here instead
 * of in production.
 */
class SmokeContextTest extends AbstractIntegrationTest {

    @Autowired
    DataSource dataSource;

    @Test
    void contextLoads() {
        assertThat(dataSource).isNotNull();
    }

    @Test
    void flywayMigratedTheCoreSchema() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // (The Flyway history is owned by hestia_owner; the application role cannot read it.
        // A successful boot already means every migration ran and the entities match.)
        // The tables the app depends on exist.
        for (String table : new String[]{"exams", "sections", "tasks", "task_answers", "task_grades"}) {
            Integer exists = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'assessment' and table_name = ?",
                Integer.class, table);
            assertThat(exists).as("table assessment.%s exists", table).isEqualTo(1);
        }
    }
}
