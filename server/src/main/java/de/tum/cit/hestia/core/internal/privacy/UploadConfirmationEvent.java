package de.tum.cit.hestia.core.internal.privacy;

import de.tum.cit.hestia.core.OrgScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** One entry of the append-only log of upload confirmations. */
@Entity
@Immutable
@Table(schema = "core", name = "upload_confirmation")
class UploadConfirmationEvent extends OrgScoped {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "notice_version", nullable = false)
    private String noticeVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UploadConfirmationEvent() {}

    UploadConfirmationEvent(UUID userId, String noticeVersion, Instant now) {
        this.userId = userId;
        this.noticeVersion = noticeVersion;
        this.createdAt = now;
    }

    String getNoticeVersion() {
        return noticeVersion;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
