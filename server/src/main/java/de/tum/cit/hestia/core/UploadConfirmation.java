package de.tum.cit.hestia.core;

import java.time.Instant;

/**
 * The logged-in user's confirmation that their uploads contain no personal data of others (e.g.
 * student names or ID numbers). Asked once per user, before the first upload in any module; every
 * confirmation is appended to a log with the notice version it was given for, so it can be proven.
 * A new notice version asks again.
 */
public interface UploadConfirmation {

    /** The current notice and whether the user has confirmed it. */
    Status status();

    /**
     * Records the confirmation of {@code noticeVersion}.
     *
     * @throws UploadConfirmationOutdatedException if that is not the current version
     */
    Status confirm(String noticeVersion);

    /**
     * Called by every endpoint that accepts an upload, before it stores anything.
     *
     * @throws UploadConfirmationRequiredException if the user has not confirmed the current notice
     */
    void requireConfirmed();

    /**
     * @param confirmed     whether the user confirmed the current notice version
     * @param noticeVersion the current notice version
     * @param text          the notice (Markdown, German)
     * @param checkboxLabel the text next to the checkbox
     * @param confirmedAt   when the user confirmed the current version, or null
     */
    record Status(boolean confirmed, String noticeVersion, String text, String checkboxLabel, Instant confirmedAt) {}
}
