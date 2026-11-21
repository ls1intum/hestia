package de.tum.cit.hestia.core.internal.privacy;

import de.tum.cit.hestia.core.CurrentUser;
import de.tum.cit.hestia.core.UploadConfirmation;
import de.tum.cit.hestia.core.UploadConfirmationOutdatedException;
import de.tum.cit.hestia.core.UploadConfirmationRequiredException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Upload confirmations, kept as an append-only log ({@code core.upload_confirmation}). The notice is
 * versioned in the repository ({@code resources/core/upload-confirmation-<version>.md}); a changed
 * text gets a new version, and a confirmation counts only for the version it was given for.
 */
@Service
class UploadConfirmationService implements UploadConfirmation {

    /** Current notice version; the file {@code core/upload-confirmation-<version>.md} holds its text. */
    static final String NOTICE_VERSION = "2026-11-21";

    /** The checkbox text of {@link #NOTICE_VERSION}; part of what the user confirms. */
    static final String CHECKBOX_LABEL = "Ich bestätige, dass meine Uploads keine personenbezogenen Daten Dritter "
            + "enthalten (z. B. Namen oder Matrikelnummern von Studierenden).";

    private final UploadConfirmationRepository confirmations;
    private final CurrentUser currentUser;
    private final boolean enforced;
    private final String text;
    private final Clock clock = Clock.systemUTC();

    UploadConfirmationService(UploadConfirmationRepository confirmations, CurrentUser currentUser,
            @Value("${hestia.privacy.upload-confirmation.enforced:true}") boolean enforced) {
        this.confirmations = confirmations;
        this.currentUser = currentUser;
        this.enforced = enforced;
        this.text = load(NOTICE_VERSION);
    }

    @Override
    @Transactional(readOnly = true)
    public Status status() {
        return statusOf(currentUser.userId());
    }

    @Override
    @Transactional
    public Status confirm(String noticeVersion) {
        if (!NOTICE_VERSION.equals(noticeVersion)) {
            throw new UploadConfirmationOutdatedException(noticeVersion);
        }
        UUID userId = currentUser.userId();
        Status current = statusOf(userId);
        if (current.confirmed()) {
            return current; // a second click or another tab: one entry is enough
        }
        confirmations.saveAndFlush(new UploadConfirmationEvent(userId, NOTICE_VERSION, clock.instant()));
        return statusOf(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public void requireConfirmed() {
        if (enforced && !statusOf(currentUser.userId()).confirmed()) {
            throw new UploadConfirmationRequiredException();
        }
    }

    private Status statusOf(UUID userId) {
        Optional<UploadConfirmationEvent> latest =
                confirmations.findFirstByUserIdAndNoticeVersionOrderByCreatedAtDesc(userId, NOTICE_VERSION);
        return new Status(latest.isPresent(), NOTICE_VERSION, text, CHECKBOX_LABEL,
                latest.map(UploadConfirmationEvent::getCreatedAt).orElse(null));
    }

    private static String load(String version) {
        String name = "core/upload-confirmation-" + version + ".md";
        try {
            return new ClassPathResource(name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Upload confirmation notice " + name + " missing", e);
        }
    }
}
