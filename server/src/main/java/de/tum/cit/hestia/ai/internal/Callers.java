package de.tum.cit.hestia.ai.internal;

import de.tum.cit.hestia.core.CurrentUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Who is calling, if anyone: background threads have no logged-in user. */
@Component
class Callers {

    private final CurrentUser currentUser;

    Callers(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    Optional<UUID> currentUserId() {
        try {
            return Optional.ofNullable(currentUser.userId());
        } catch (RuntimeException notLoggedIn) {
            return Optional.empty();
        }
    }

    UUID requireUserId() {
        return currentUser.userId();
    }
}
