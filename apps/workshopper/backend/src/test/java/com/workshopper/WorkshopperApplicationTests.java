package com.workshopper;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;

@SpringBootTest
@ActiveProfiles("test")
class WorkshopperApplicationTests {

    @MockitoBean
    private RelyingPartyRegistrationRepository relyingPartyRegistrationRepository;

    @Test
    void contextLoads() {
        // Test that the Spring Application Context successfully loads
    }

}
