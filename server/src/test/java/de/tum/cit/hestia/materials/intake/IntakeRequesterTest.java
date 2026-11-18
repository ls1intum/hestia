package de.tum.cit.hestia.materials.intake;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;

class IntakeRequesterTest {

    private final IntakeRequester requester = new IntakeRequester(List.of(" ab12cde@tum.de ", ""));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aSamlLoginIsIdentifiedByItsPrincipalNameAndSuggestsItsMail() {
        var principal = new DefaultSaml2AuthenticatedPrincipal("transient-name-id", Map.of(
                IntakeRequester.EPPN, List.<Object>of("ab12cde@tum.de"),
                IntakeRequester.MAIL, List.<Object>of("teacher@tum.de")));
        SecurityContextHolder.getContext().setAuthentication(
                new Saml2Authentication(principal, "<saml/>", AuthorityUtils.createAuthorityList("ROLE_USER")));

        IntakeRequester.Identity identity = requester.current();

        assertThat(identity.id()).isEqualTo("ab12cde@tum.de");
        assertThat(identity.admin()).isTrue();
        assertThat(identity.suggestedEmail()).isEqualTo("teacher@tum.de");
    }

    @Test
    void withoutSamlTheAuthenticationNameIsTheId() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "dev-local-user", null, AuthorityUtils.createAuthorityList("ROLE_USER")));

        IntakeRequester.Identity identity = requester.current();

        assertThat(identity.id()).isEqualTo("dev-local-user");
        assertThat(identity.admin()).isFalse();
        assertThat(identity.suggestedEmail()).isNull();
    }
}
