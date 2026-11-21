package de.tum.cit.hestia.core.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.hestia.core.internal.identity.HestiaPrincipal;
import de.tum.cit.hestia.core.internal.identity.IdentityService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;

class SamlLoginTest {

    private final IdentityService identities = mock(IdentityService.class);
    private final SamlLogin login = new SamlLogin(identities, mock(FederationMetadata.class));

    SamlLoginTest() {
        when(identities.federatedLogin(any(), any(), any(), any(), any())).thenAnswer(i -> new HestiaPrincipal(
                UUID.randomUUID(), UUID.randomUUID(), i.getArgument(1), i.getArgument(2), false, i.getArgument(3)));
        when(identities.login(any(), any(), any(), any())).thenAnswer(i -> new HestiaPrincipal(
                UUID.randomUUID(), UUID.randomUUID(), i.getArgument(1), i.getArgument(2), false, i.getArgument(3)));
    }

    @Test
    void organisationComesFromSchacHomeOrganization() {
        HestiaPrincipal p = login.principal("name-id", "tum.de", "ab12cde@tum.de", "Ada");
        verify(identities).login("tum.de", "ab12cde@tum.de", "Ada", null);
        assertThat(p.externalId()).isEqualTo("ab12cde@tum.de");
    }

    @Test
    void fallsBackToTheScopeOfThePrincipalName() {
        login.principal("name-id", null, "ab12cde@mytum.de", null);
        verify(identities).login("mytum.de", "ab12cde@mytum.de", null, null);
    }

    @Test
    void fallsBackToTheNameIdForTheUser() {
        login.principal("name-id", "tum.de", null, null);
        verify(identities).login("tum.de", "name-id", null, null);
    }

    @Test
    void passesTheMailAttributeOn() {
        HestiaPrincipal p = login.principal("name-id", "tum.de", "ab12cde@tum.de", "Ada", "ada@example.test");
        assertThat(p.email()).isEqualTo("ada@example.test");
    }

    @Test
    void rejectsALoginWithoutOrganisation() {
        assertThatThrownBy(() -> login.principal("name-id", null, null, null))
                .isInstanceOf(Saml2AuthenticationException.class);
        assertThatThrownBy(() -> login.principal("name-id", " ", "no-scope", null))
                .isInstanceOf(Saml2AuthenticationException.class);
    }

    private static final FederationMetadata.IdentityProvider ALPHA = new FederationMetadata.IdentityProvider(
            "https://idp.alpha.test/idp", "Universität Alpha", List.of("Universität Alpha"),
            List.of(new FederationMetadata.Scope("alpha.test", false), new FederationMetadata.Scope("^[a-z]+\\.alpha\\.test$", true)));

    @Test
    void aFederationProviderMayAssertItsOwnScopes() {
        login.principal("n", "alpha.test", "ada@alpha.test", "Ada", null, Optional.of(ALPHA));
        verify(identities).federatedLogin("alpha.test", "ada@alpha.test", "Ada", null, "Universität Alpha");

        login.principal("n", null, "bob@cs.alpha.test", null, null, Optional.of(ALPHA));
        verify(identities).federatedLogin("cs.alpha.test", "bob@cs.alpha.test", null, null, "Universität Alpha");
    }

    @Test
    void aFederationProviderMustSendAScopedUserIdNotJustTheNameId() {
        // The NameID could name a TUM admin; it is never used as the user id of a federation login.
        assertThatThrownBy(() -> login.principal("admin@tum.de", "alpha.test", null, null, null, Optional.of(ALPHA)))
                .isInstanceOf(Saml2AuthenticationException.class)
                .hasMessageContaining("no scoped user id");
        verify(identities, never()).federatedLogin(any(), any(), any(), any(), any());
        verify(identities, never()).login(any(), any(), any(), any());
    }

    @Test
    void aRejectionNamesTheScopeButNotTheUser() {
        assertThatThrownBy(() -> login.principal("n", "alpha.test", "secret-person@tum.de", null, null, Optional.of(ALPHA)))
                .isInstanceOf(Saml2AuthenticationException.class)
                .hasMessageContaining("tum.de")
                .hasMessageNotContaining("secret-person");
    }

    @Test
    void aFederationProviderMayNotAssertAnotherUniversity() {
        assertThatThrownBy(() -> login.principal("n", "tum.de", "ada@alpha.test", null, null, Optional.of(ALPHA)))
                .isInstanceOf(Saml2AuthenticationException.class);
        assertThatThrownBy(() -> login.principal("n", null, "ada@tum.de", null, null, Optional.of(ALPHA)))
                .isInstanceOf(Saml2AuthenticationException.class);
        assertThatThrownBy(() -> login.principal("n", "alpha.test", "ada@tum.de", null, null, Optional.of(ALPHA)))
                .isInstanceOf(Saml2AuthenticationException.class);
        assertThatThrownBy(() -> login.principal("n", "alpha.test", "unscoped", null, null, Optional.of(ALPHA)))
                .isInstanceOf(Saml2AuthenticationException.class);
    }
}
