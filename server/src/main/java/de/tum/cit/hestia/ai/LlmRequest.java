package de.tum.cit.hestia.ai;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * One model call. Besides the prompt it says what it is for: the calling {@code module} and its
 * {@code purpose} (free text, for example {@code assessment} / {@code exam-solve}), the course if
 * there is one, and the job it belongs to ({@link LlmJobs#admit}). The user is taken from the job or,
 * without one, from the logged-in request.
 *
 * @param model       null means the default Logos model.
 * @param tool        forces structured output through a tool call; direct providers only.
 * @param temperature null for the configured default.
 * @param limits      timeouts and attempts for this call on Logos; null for the application defaults.
 */
public record LlmRequest(
        String module,
        String purpose,
        UUID courseId,
        UUID jobId,
        ModelRef model,
        String system,
        LlmContent.UserContent user,
        LlmContent.Tool tool,
        Double temperature,
        Limits limits) {

    public LlmRequest {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(user, "user");
        model = model == null ? ModelRef.logos() : model;
    }

    /** A text-only request to the default Logos model. */
    public static LlmRequest of(String module, String purpose, String user) {
        return of(module, purpose, new LlmContent.TextContent(user));
    }

    public static LlmRequest of(String module, String purpose, LlmContent.UserContent user) {
        return new LlmRequest(module, purpose, null, null, ModelRef.logos(), null, user, null, null, null);
    }

    public LlmRequest withModel(ModelRef model) {
        return new LlmRequest(module, purpose, courseId, jobId, model, system, user, tool, temperature, limits);
    }

    public LlmRequest withSystem(String system) {
        return new LlmRequest(module, purpose, courseId, jobId, model, system, user, tool, temperature, limits);
    }

    public LlmRequest withTool(LlmContent.Tool tool) {
        return new LlmRequest(module, purpose, courseId, jobId, model, system, user, tool, temperature, limits);
    }

    public LlmRequest withTemperature(Double temperature) {
        return new LlmRequest(module, purpose, courseId, jobId, model, system, user, tool, temperature, limits);
    }

    public LlmRequest forCourse(UUID courseId) {
        return new LlmRequest(module, purpose, courseId, jobId, model, system, user, tool, temperature, limits);
    }

    public LlmRequest inJob(UUID jobId) {
        return new LlmRequest(module, purpose, courseId, jobId, model, system, user, tool, temperature, limits);
    }

    public LlmRequest withLimits(Limits limits) {
        return new LlmRequest(module, purpose, courseId, jobId, model, system, user, tool, temperature, limits);
    }

    /** Timeouts and attempts for one call, instead of the application-wide ones. */
    public record Limits(Duration connectTimeout, Duration readTimeout, int maxAttempts) {

        public Limits {
            Objects.requireNonNull(connectTimeout, "connectTimeout");
            Objects.requireNonNull(readTimeout, "readTimeout");
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be at least 1");
            }
        }
    }
}
