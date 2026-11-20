package de.tum.cit.hestia.ai.internal;

import de.tum.cit.hestia.core.OrgScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** One entry of the append-only consent log: a grant or a withdrawal. */
@Entity
@Immutable
@Table(schema = "ai", name = "consent_event")
class ConsentEvent extends OrgScoped {

    static final String GRANTED = "GRANTED";
    static final String REVOKED = "REVOKED";

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String scope;

    @Column(nullable = false)
    private String action;

    @Column(name = "notice_version", nullable = false)
    private String noticeVersion;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected ConsentEvent() {}

    ConsentEvent(UUID userId, String scope, String action, String noticeVersion) {
        this.userId = userId;
        this.scope = scope;
        this.action = action;
        this.noticeVersion = noticeVersion;
    }

    UUID getUserId() { return userId; }
    String getScope() { return scope; }
    String getAction() { return action; }
    String getNoticeVersion() { return noticeVersion; }
    OffsetDateTime getCreatedAt() { return createdAt; }
}
