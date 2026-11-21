package de.tum.cit.hestia.core.internal.privacy;

import java.time.Instant;
import java.util.UUID;

/**
 * Delivers an inactivity warning besides the notice the clients show after the next login (the
 * notice is the stored {@code core.inactivity_warning} row). Chosen with
 * {@code hestia.privacy.inactivity.notifier}; the default only logs. Called after the warning was
 * committed; a failure is logged and does not undo it.
 */
public interface InactivityNotifier {

    /**
     * @param orgId          the user's organisation
     * @param userId         the user
     * @param afterDays      the threshold this warning is for (e.g. 300)
     * @param lastSeenAt     the user's last login
     * @param deletionDueAt  the earliest moment the account is deleted if the user stays away
     */
    record Warning(UUID orgId, UUID userId, int afterDays, Instant lastSeenAt, Instant deletionDueAt) {}

    void warn(Warning warning);
}
