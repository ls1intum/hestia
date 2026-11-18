package de.tum.cit.hestia.core.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.hestia.core.internal.identity.HestiaPrincipal;
import de.tum.cit.hestia.core.internal.identity.IdentityService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;

class SamlLoginTest {

    private final IdentityService identities = mock(IdentityService.class);
    private final SamlLogin login = new SamlLogin(identities);

    SamlLoginTest() {
        when(identities.login(any(), any(), any())).thenAnswer(i -> new HestiaPrincipal(
                UUID.randomUUID(), UUID.randomUUID(), i.getArgument(1), i.getArgument(2), false));
    }

    @Test
    void organisationComesFromSchacHomeOrganization() {
        HestiaPrincipal p = login.principal("name-id", "tum.de", "ab12cde@tum.de", "Ada");
        verify(identities).login("tum.de", "ab12cde@tum.de", "Ada");
        assertThat(p.externalId()).isEqualTo("ab12cde@tum.de");
    }

    @Test
    void fallsBackToTheScopeOfThePrincipalName() {
        login.principal("name-id", null, "ab12cde@mytum.de", null);
        verify(identities).login("mytum.de", "ab12cde@mytum.de", null);
    }

    @Test
    void fallsBackToTheNameIdForTheUser() {
        login.principal("name-id", "tum.de", null, null);
        verify(identities).login("tum.de", "name-id", null);
    }

    @Test
    void rejectsALoginWithoutOrganisation() {
        assertThatThrownBy(() -> login.principal("name-id", null, null, null))
                .isInstanceOf(Saml2AuthenticationException.class);
        assertThatThrownBy(() -> login.principal("name-id", " ", "no-scope", null))
                .isInstanceOf(Saml2AuthenticationException.class);
    }
}
