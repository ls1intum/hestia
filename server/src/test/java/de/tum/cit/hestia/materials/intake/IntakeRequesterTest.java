package de.tum.cit.hestia.materials.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.hestia.core.CurrentUser;
import de.tum.cit.hestia.core.internal.identity.HestiaAuthentication;
import de.tum.cit.hestia.core.internal.identity.HestiaPrincipal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

class IntakeRequesterTest {

    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final IntakeRequester requester = new IntakeRequester(currentUser);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void login(String externalId) {
        SecurityContextHolder.getContext().setAuthentication(new HestiaAuthentication(
                new HestiaPrincipal(UUID.randomUUID(), UUID.randomUUID(), externalId, externalId, false, null)));
    }

    @Test
    void theExternalIdIsTheIdAndTheLoginMailIsTheSuggestion() {
        login("ab12cde@tum.de");
        when(currentUser.email()).thenReturn("teacher@tum.de");

        IntakeRequester.Identity identity = requester.current();

        assertThat(identity.id()).isEqualTo("ab12cde@tum.de");
        assertThat(identity.suggestedEmail()).isEqualTo("teacher@tum.de");
    }

    @Test
    void withoutAMailThereIsNoSuggestion() {
        login("dev-local-user");

        IntakeRequester.Identity identity = requester.current();

        assertThat(identity.id()).isEqualTo("dev-local-user");
        assertThat(identity.suggestedEmail()).isNull();
    }

    @Test
    void withoutLoginItFails() {
        assertThatThrownBy(requester::current).isInstanceOf(IllegalStateException.class);
    }
}
