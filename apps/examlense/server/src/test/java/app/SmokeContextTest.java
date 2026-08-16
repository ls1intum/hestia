package app;

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
        Integer migrations = jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success = true", Integer.class);
        assertThat(migrations).isGreaterThanOrEqualTo(4); // V1–V4

        // The tables the app depends on exist.
        for (String table : new String[]{"exams", "sections", "tasks", "task_answers", "task_grades"}) {
            Integer exists = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_name = ?",
                Integer.class, table);
            assertThat(exists).as("table %s exists", table).isEqualTo(1);
        }
    }
}
