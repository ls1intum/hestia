package de.tum.cit.hestia;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The whole server starts on an empty database: Flyway applies the baselines of all modules as
 * hestia_owner, and Hibernate ({@code ddl-auto: validate}) finds every entity's table and columns.
 */
@SpringBootTest
class HestiaApplicationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    // The identity provider's metadata is fetched over the network; not needed to start.
    @MockitoBean
    RelyingPartyRegistrationRepository relyingPartyRegistrationRepository;

    @Autowired
    DataSource dataSource;

    @Autowired
    Flyway flyway;

    @Test
    void startsOnAnEmptyDatabaseWithOneSchemaPerModule() throws Exception {
        // The first entry is Flyway creating the schemas, without a version.
        assertThat(Arrays.stream(flyway.info().applied()).filter(m -> m.getVersion() != null)
                .map(m -> m.getVersion().getVersion())).startsWith("1", "2", "3", "4");
        try (Connection c = dataSource.getConnection()) {
            assertThat(c.getMetaData().getUserName()).isEqualTo("hestia_app");
            List<String> schemas = new ArrayList<>();
            try (ResultSet rs = c.createStatement().executeQuery(
                    "select nspname from pg_namespace n join pg_roles r on r.oid = n.nspowner"
                            + " where r.rolname = 'hestia_owner' order by nspname")) {
                while (rs.next()) {
                    schemas.add(rs.getString(1));
                }
            }
            assertThat(schemas).contains("core", "ai", "materials", "teaching", "assessment");
        }
    }
}
