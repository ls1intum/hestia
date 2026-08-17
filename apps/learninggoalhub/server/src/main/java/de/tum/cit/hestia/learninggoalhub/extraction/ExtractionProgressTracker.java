package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * In-memory progress for extraction runs, keyed by course.
 *
 * <p>Kept in memory on purpose: a single-node MVP, no need for a DB row or a Flyway migration, and
 * no transaction-visibility games (DB writes inside the run's transaction would not be visible to a
 * concurrent poller until commit anyway).
 */
@Component
public class ExtractionProgressTracker {

    /** Ordered to match the pipeline so the client can show the current pipeline step if it wants. */
    public enum Phase {
        DESCRIBING_FIGURES,
        OUTLINING,
        PARSING,
        EXTRACTING,
        CLASSIFYING,
        EMBEDDING,
        PERSISTING,
        SYNTHESIZING
    }

    public enum Status {
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    private final Map<Long, Run> runs = new ConcurrentHashMap<>();
    private final AtomicReference<Long> runningCourseId = new AtomicReference<>();

    /** Claims the one global extraction slot and begins tracking the run. */
    public Optional<Run> tryStart(Long courseId, String model) {
        if (!runningCourseId.compareAndSet(null, courseId)) {
            return Optional.empty();
        }
        // Releasing the slot as the run turns terminal — rather than only in the caller's finally —
        // means anyone who can observe SUCCEEDED/FAILED can already claim the slot. Otherwise the
        // next extraction is rejected with a spurious 409 in the window between the two.
        Run run = new Run(model, () -> finish(courseId));
        runs.put(courseId, run);
        return Optional.of(run);
    }

    /** Releases the global slot only when it still belongs to {@code courseId}. */
    public void finish(Long courseId) {
        for (;;) {
            Long current = runningCourseId.get();
            if (!Objects.equals(current, courseId)
                    || runningCourseId.compareAndSet(current, null)) {
                return;
            }
        }
    }

    public Optional<Current> current() {
        Long courseId = runningCourseId.get();
        if (courseId == null) {
            return Optional.empty();
        }
        Run run = runs.get(courseId);
        return run == null ? Optional.empty() : Optional.of(new Current(courseId, run.snapshot()));
    }

    /** Begins (or replaces) tracking for a course and returns the handle the runner updates. */
    public Run start(Long courseId, String model) {
        // The controller claims the slot before submitting the job. Reuse that handle so the
        // background runner cannot replace the snapshot that the controller already exposed.
        if (Objects.equals(runningCourseId.get(), courseId)) {
            return runs.computeIfAbsent(courseId, ignored -> new Run(model, () -> finish(courseId)));
        }
        Run run = new Run(model, () -> finish(courseId));
        runs.put(courseId, run);
        return run;
    }

    public Optional<Snapshot> snapshot(Long courseId) {
        return Optional.ofNullable(runs.get(courseId)).map(Run::snapshot);
    }

    /** Mutable, thread-safe handle for one in-flight run. Counters are bumped from worker threads. */
    public static final class Run {

        private final String model;
        private volatile Phase phase = Phase.DESCRIBING_FIGURES;
        private final AtomicInteger completed = new AtomicInteger();
        /** Sessions the pipeline had to drop. A run stays SUCCEEDED with these missing. */
        private final AtomicInteger failedSessions = new AtomicInteger();
        private volatile int total;
        private volatile Status status = Status.RUNNING;
        private volatile String error;
        private volatile ExtractionRunner.ExtractionSummary summary;
        private final AtomicInteger lastPercent = new AtomicInteger();
        /** Frees the global slot; run before the terminal status becomes visible. */
        private final Runnable releaseSlot;

        private Run(String model, Runnable releaseSlot) {
            this.model = model;
            this.releaseSlot = releaseSlot;
        }

        /** Moves to a new phase and resets the completed/total counters for it. */
        public void phase(Phase next, int total) {
            this.phase = next;
            this.total = total;
            this.completed.set(0);
        }

        public void increment() {
            completed.incrementAndGet();
        }

        /** Advances the counter by {@code delta}, e.g. when one batched call covers several items. */
        public void increment(int delta) {
            completed.addAndGet(delta);
        }

        /** Records a session the pipeline could not analyse, so the review can say what is missing. */
        public void sessionFailed() {
            failedSessions.incrementAndGet();
        }

        public int failedSessions() {
            return failedSessions.get();
        }

        public void succeed(ExtractionRunner.ExtractionSummary summary) {
            this.summary = summary;
            releaseSlot.run();
            this.status = Status.SUCCEEDED;
        }

        public void fail(String message) {
            this.error = message;
            releaseSlot.run();
            this.status = Status.FAILED;
        }

        private Snapshot snapshot() {
            Status currentStatus = status;
            int percent = currentStatus == Status.SUCCEEDED
                    ? 100
                    : Math.min(99, weightedPercent(phase, completed.get(), total));
            percent = lastPercent.accumulateAndGet(percent, Math::max);
            return new Snapshot(currentStatus, phase, completed.get(), total, model, summary, error, percent,
                    failedSessions.get());
        }
    }

    private static int weightedPercent(Phase phase, int completed, int total) {
        int[] envelope = switch (phase) {
            case DESCRIBING_FIGURES -> new int[] {0, 30};
            case OUTLINING -> new int[] {30, 35};
            case PARSING -> new int[] {35, 40};
            case EXTRACTING -> new int[] {40, 70};
            case CLASSIFYING -> new int[] {70, 80};
            case EMBEDDING -> new int[] {80, 85};
            case PERSISTING -> new int[] {85, 90};
            case SYNTHESIZING -> new int[] {90, 100};
        };
        double fraction = total <= 0 ? 0 : Math.min(1, Math.max(0, (double) completed / total));
        return (int) Math.round(envelope[0] + (envelope[1] - envelope[0]) * fraction);
    }

    /**
     * Immutable view returned to pollers.
     *
     * @param failedSessions sessions dropped by the run. Deliberately its own field rather than part
     *                       of {@code summary}: it is a warning about what is missing, not a result.
     */
    public record Snapshot(Status status, Phase phase, int completed, int total, String model,
                           ExtractionRunner.ExtractionSummary summary, String error, int percent,
                           int failedSessions) {
    }

    /** The globally active run and its latest immutable snapshot. */
    public record Current(Long courseId, Snapshot snapshot) {
    }
}
