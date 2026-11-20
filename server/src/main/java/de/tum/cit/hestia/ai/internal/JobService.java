package de.tum.cit.hestia.ai.internal;

import de.tum.cit.hestia.ai.ConsentRequiredException;
import de.tum.cit.hestia.ai.ConsentScope;
import de.tum.cit.hestia.ai.JobLimitExceededException;
import de.tum.cit.hestia.ai.LlmJobs;
import de.tum.cit.hestia.ai.ModelRef;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admits jobs against the per-user limits. Two concurrent admissions can both pass the check and
 * overshoot a limit by one; that is tolerated, the limits bound daily use rather than enforce a hard
 * cap. Counts live in the database, so a restart resets nobody.
 */
@Service
class JobService implements LlmJobs {

    private final LlmJobRepository jobs;
    private final ConsentService consents;
    private final Callers callers;
    private final AiProperties properties;

    JobService(LlmJobRepository jobs, ConsentService consents, Callers callers, AiProperties properties) {
        this.jobs = jobs;
        this.consents = consents;
        this.callers = callers;
        this.properties = properties;
    }

    @Override
    @Transactional(readOnly = true)
    public void check(String purpose) {
        check(callers.requireUserId(), purpose);
    }

    @Override
    @Transactional
    public UUID admit(String module, String purpose, UUID courseId, ModelRef model) {
        UUID userId = callers.requireUserId();
        check(userId, purpose);
        if (model.external() && !consents.isGranted(userId, ConsentScope.EXTERNAL_MODELS)) {
            throw new ConsentRequiredException(ConsentScope.EXTERNAL_MODELS);
        }
        LlmJob job = new LlmJob(userId, courseId, module, purpose, model.provider().name(), model.id(), model.external());
        return jobs.save(job).getId();
    }

    @Override
    @Transactional(readOnly = true)
    public Usage usage(String purpose) {
        UUID userId = callers.requireUserId();
        return new Usage(purpose, jobs.countSince(userId, purpose, windowStart()), properties.limitFor(purpose));
    }

    /** The job, if it exists in the current organisation. */
    @Transactional(readOnly = true)
    public Optional<LlmJob> find(UUID jobId) {
        if (jobId == null || de.tum.cit.hestia.core.OrgContext.current().isEmpty()) {
            return Optional.empty();
        }
        return jobs.findById(jobId);
    }

    private void check(UUID userId, String purpose) {
        int limit = properties.limitFor(purpose);
        if (limit > 0 && jobs.countSince(userId, purpose, windowStart()) >= limit) {
            throw new JobLimitExceededException(purpose, limit);
        }
    }

    /** Rolling 24 hours rather than the calendar day, so nobody games the reset at midnight. */
    private static OffsetDateTime windowStart() {
        return OffsetDateTime.now().minusDays(1);
    }
}
