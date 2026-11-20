package de.tum.cit.hestia.ai;

import java.time.OffsetDateTime;

/**
 * The logged-in user's consents. Every grant and withdrawal is appended to a log; the current state
 * is the latest entry. A consent counts only for the notice version it was given for, so a new
 * notice asks again.
 */
public interface ConsentApi {

    /** The notice the user consents to, in its current version. */
    Notice notice(ConsentScope scope);

    Status status(ConsentScope scope);

    /** Consents to the current notice version. */
    Status grant(ConsentScope scope);

    /** Withdraws with effect for the future. */
    Status revoke(ConsentScope scope);

    record Notice(ConsentScope scope, String version, String text) {}

    /**
     * @param granted       whether a call needing this consent may run now.
     * @param noticeVersion the current notice version.
     * @param lastAction    GRANTED, REVOKED, or null if the user never decided.
     * @param lastActionAt  when, or null.
     */
    record Status(ConsentScope scope, boolean granted, String noticeVersion, String lastAction,
            OffsetDateTime lastActionAt) {}
}
