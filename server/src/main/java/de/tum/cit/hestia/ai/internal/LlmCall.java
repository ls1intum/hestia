package de.tum.cit.hestia.ai.internal;

import de.tum.cit.hestia.core.OrgScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One answered model call, with who it was for and what it cost in tokens. */
@Entity
@Table(schema = "ai", name = "llm_call")
class LlmCall extends OrgScoped {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "job_id")
    private UUID jobId;

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

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected LlmCall() {}

    LlmCall(UUID jobId, UUID userId, UUID courseId, String module, String purpose, String provider, String model,
            boolean external, Integer promptTokens, Integer completionTokens) {
        this.jobId = jobId;
        this.userId = userId;
        this.courseId = courseId;
        this.module = module;
        this.purpose = purpose;
        this.provider = provider;
        this.model = model;
        this.external = external;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
    }

    UUID getId() { return id; }
    UUID getJobId() { return jobId; }
    UUID getUserId() { return userId; }
    UUID getCourseId() { return courseId; }
    String getModule() { return module; }
    String getPurpose() { return purpose; }
    String getProvider() { return provider; }
    String getModel() { return model; }
    boolean isExternal() { return external; }
    Integer getPromptTokens() { return promptTokens; }
    Integer getCompletionTokens() { return completionTokens; }
}
