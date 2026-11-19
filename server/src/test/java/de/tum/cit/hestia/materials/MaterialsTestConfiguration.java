package de.tum.cit.hestia.materials;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import de.tum.cit.hestia.core.TestLogin;
import de.tum.cit.hestia.core.internal.identity.IdentityService;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;

/** Test beans of the materials module's Spring tests; the database comes from {@link MaterialsTestBase}. */
@TestConfiguration(proxyBeanMethods = false)
public class MaterialsTestConfiguration {

    /** The identity provider's metadata is fetched over the network; not needed to start. */
    @Bean
    @Primary
    RelyingPartyRegistrationRepository relyingPartyRegistrationRepository() {
        return mock(RelyingPartyRegistrationRepository.class);
    }

    /**
     * /learninggoalhub/api/** needs a login. Every MockMvc request runs as the default test user (with a
     * CSRF token) unless the test says otherwise, e.g. {@code .with(anonymous())} to test what a caller
     * without login gets, or {@code .with(TestLogin.as(...))} for another user or organisation.
     */
    @Bean
    MockMvcBuilderCustomizer loggedInUser(IdentityService identities) {
        return builder -> builder.defaultRequest(
                get("/").with(TestLogin.as(identities, TestTenant.ORG, TestTenant.USER)));
    }
}
