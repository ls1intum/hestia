package de.tum.cit.hestia.learninggoalhub;

import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    }

    @Bean
    @Primary
    RelyingPartyRegistrationRepository relyingPartyRegistrationRepository() {
        return mock(RelyingPartyRegistrationRepository.class);
    }

    /**
     * /api/** needs a login. Every MockMvc request runs as a logged-in user unless the test says
     * otherwise, e.g. {@code .with(anonymous())} to test what a caller without login gets.
     */
    @Bean
    MockMvcBuilderCustomizer loggedInUser() {
        return builder -> builder.defaultRequest(get("/").with(user("test-user")));
    }
}
