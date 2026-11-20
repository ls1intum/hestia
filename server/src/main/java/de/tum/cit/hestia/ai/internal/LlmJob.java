package de.tum.cit.hestia.ai.internal;

import de.tum.cit.hestia.core.OrgScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One admitted job: a unit of work a person started, however many calls it takes. */
@Entity
@Table(schema = "ai", name = "llm_job")
class LlmJob extends OrgScoped {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "course_id")
    private UUID courseId;

    @Column(nullable = false)
    private String module;

    @Column(nullable = false)
    private String purpose;

    @Column(nullable = false)
    private String provider;

    private String model;

    @Column(nullable = false)
    private boolean external;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected LlmJob() {}

    LlmJob(UUID userId, UUID courseId, String module, String purpose, String provider, String model, boolean external) {
        this.userId = userId;
        this.courseId = courseId;
        this.module = module;
        this.purpose = purpose;
        this.provider = provider;
        this.model = model;
        this.external = external;
    }

    UUID getId() { return id; }
    UUID getUserId() { return userId; }
    UUID getCourseId() { return courseId; }
    String getPurpose() { return purpose; }
    OffsetDateTime getCreatedAt() { return createdAt; }
}
