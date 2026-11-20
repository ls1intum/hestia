package de.tum.cit.hestia.ai;

import java.util.UUID;

/**
 * Jobs are what users are limited by: one unit of work a person starts (read in an exam, let a model
 * solve it), however many model calls it takes. Limits are per user and purpose over a rolling 24
 * hours ({@code hestia.ai.job-limits.<purpose>}, 0 or absent = off); usage is counted either way.
 * Calls name their job ({@link LlmRequest#inJob}), so tokens add up per job.
 */
public interface LlmJobs {

    /** Fails with {@link JobLimitExceededException} if the logged-in user is at the limit; records nothing. */
    void check(String purpose);

    /**
     * Admits one job for the logged-in user: checks the limit and, for an external model, the
     * consent ({@link ConsentRequiredException}); then records it.
     *
     * @return the job id to put on the job's calls.
     */
    UUID admit(String module, String purpose, UUID courseId, ModelRef model);

    /** The logged-in user's jobs of {@code purpose} in the last 24 hours and the limit (0 = off). */
    Usage usage(String purpose);

    record Usage(String purpose, long used, int limit) {

        /** What is left; 0 when the limit is off. */
        public int remaining() {
            return limit > 0 ? (int) Math.max(0, limit - used) : 0;
        }
    }
}
