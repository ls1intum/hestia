package de.tum.cit.hestia.core.internal.privacy;

import de.tum.cit.hestia.core.CurrentUser;
import de.tum.cit.hestia.core.UploadConfirmation;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The user's privacy notices, under {@code /core/api/privacy}: the upload confirmation (asked once
 * before the first upload in any client) and the inactivity warnings recorded while the user was
 * away (shown once after the next login, which has already reset the deletion period).
 */
@RestController
@RequestMapping("/api/privacy")
@Validated
class PrivacyController {

    private final UploadConfirmation uploadConfirmation;
    private final InactivityWarningRepository warnings;
    private final InactivityProperties inactivity;
    private final CurrentUser currentUser;
    private final Clock clock = Clock.systemUTC();

    PrivacyController(UploadConfirmation uploadConfirmation, InactivityWarningRepository warnings,
            InactivityProperties inactivity, CurrentUser currentUser) {
        this.uploadConfirmation = uploadConfirmation;
        this.warnings = warnings;
        this.inactivity = inactivity;
        this.currentUser = currentUser;
    }

    record ConfirmRequest(@NotBlank String noticeVersion) {}

    @GetMapping("/upload-confirmation")
    UploadConfirmation.Status uploadConfirmation() {
        return uploadConfirmation.status();
    }

    @PostMapping("/upload-confirmation")
    UploadConfirmation.Status confirmUploads(@RequestBody @Validated ConfirmRequest request) {
        return uploadConfirmation.confirm(request.noticeVersion());
    }

    /**
     * @param lastSeenAt      the login before the absence
     * @param warnedAt        when the latest warning was recorded
     * @param afterDays       its threshold (e.g. 350)
     * @param deleteAfterDays after how many days without login an account is deleted
     */
    record InactivityNotice(Instant lastSeenAt, Instant warnedAt, int afterDays, int deleteAfterDays) {}

    /** Unacknowledged inactivity warnings, latest first (empty almost always). */
    @GetMapping("/inactivity-notices")
    @Transactional(readOnly = true)
    List<InactivityNotice> inactivityNotices() {
        return warnings.findByUserIdAndAcknowledgedAtIsNullOrderByCreatedAtDesc(currentUser.userId()).stream()
                .limit(1)
                .map(w -> new InactivityNotice(w.getLastSeenAt(), w.getCreatedAt(), w.getAfterDays(),
                        inactivity.deleteAfterDays()))
                .toList();
    }

    /** The user has seen the notice; it is not shown again. */
    @PostMapping("/inactivity-notices/acknowledge")
    @Transactional
    void acknowledgeInactivityNotices() {
        Instant now = clock.instant();
        warnings.findByUserIdAndAcknowledgedAtIsNullOrderByCreatedAtDesc(currentUser.userId())
                .forEach(w -> w.acknowledge(now));
    }
}
