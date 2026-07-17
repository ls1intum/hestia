package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * In-memory progress for the (synchronous) extraction run, keyed by course.
 *
 * <p>The {@code POST /extract} call still runs the whole pipeline on the request thread and returns
 * the final {@link ExtractionRunner.ExtractionSummary}. Alongside it, the runner publishes its
 * current phase and per-phase counters here so the client can poll {@code GET /extract/status} and
 * render a real progress bar. Kept in memory on purpose: a single-node MVP, no need for a DB row or
 * a Flyway migration, and no transaction-visibility games (DB writes inside the run's transaction
 * would not be visible to a concurrent poller until commit anyway).
 */
@Component
public class ExtractionProgressTracker {

    /** Ordered to match the pipeline so the client can show "step N of 6" if it wants. */
    public enum Phase {
        OUTLINING,
        PARSING,
        EXTRACTING,
        CLASSIFYING,
        EMBEDDING,
        PERSISTING
    }

    public enum Status {
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    private final Map<Long, Run> runs = new ConcurrentHashMap<>();

    /** Begins (or replaces) tracking for a course and returns the handle the runner updates. */
    public Run start(Long courseId, String model) {
        Run run = new Run(model);
        runs.put(courseId, run);
        return run;
    }

    public Optional<Snapshot> snapshot(Long courseId) {
        return Optional.ofNullable(runs.get(courseId)).map(Run::snapshot);
    }

    /** Mutable, thread-safe handle for one in-flight run. Counters are bumped from worker threads. */
    public static final class Run {

        private final String model;
        private volatile Phase phase = Phase.OUTLINING;
        private final AtomicInteger completed = new AtomicInteger();
        private volatile int total;
        private volatile Status status = Status.RUNNING;
        private volatile String error;
        private volatile ExtractionRunner.ExtractionSummary summary;

        private Run(String model) {
            this.model = model;
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

        public void succeed(ExtractionRunner.ExtractionSummary summary) {
            this.summary = summary;
            this.status = Status.SUCCEEDED;
        }

        public void fail(String message) {
            this.error = message;
            this.status = Status.FAILED;
        }

        private Snapshot snapshot() {
            return new Snapshot(status, phase, completed.get(), total, model, summary, error);
        }
    }

    /** Immutable view returned to pollers. */
    public record Snapshot(Status status, Phase phase, int completed, int total, String model,
                           ExtractionRunner.ExtractionSummary summary, String error) {
    }
}
