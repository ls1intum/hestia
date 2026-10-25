package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
        EXTRACTING,
        PERSISTING,
        SYNTHESIZING
    }

    public enum Status {
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    private final Map<Long, Run> runs = new ConcurrentHashMap<>();
    /**
     * The run holding the one global slot. Held by handle, not by course id, so a finished run can
     * never release the slot of a newer run of the same course.
     */
    private final AtomicReference<Run> activeRun = new AtomicReference<>();

    /** Claims the one global extraction slot and begins tracking the run. */
    public Optional<Run> tryStart(Long courseId, String model) {
        Run run = new Run(courseId, model, this::release);
        if (!activeRun.compareAndSet(null, run)) {
            return Optional.empty();
        }
        // Releasing the slot as the run turns terminal — rather than only in the caller's finally —
        // means anyone who can observe SUCCEEDED/FAILED can already claim the slot. Otherwise the
        // next extraction is rejected with a spurious 409 in the window between the two.
        runs.put(courseId, run);
        return Optional.of(run);
    }

    /** Releases the global slot only when {@code run} still holds it. */
    public void release(Run run) {
        activeRun.compareAndSet(run, null);
    }

    public Optional<Current> current() {
        Run run = activeRun.get();
        return run == null ? Optional.empty() : Optional.of(new Current(run.courseId, run.snapshot()));
    }

    /** The handle of the run holding the global slot, for work that must be booked to it. */
    public Optional<Run> activeRun() {
        return Optional.ofNullable(activeRun.get());
    }

    /** Begins (or replaces) tracking for a course and returns the handle the runner updates. */
    public Run start(Long courseId, String model) {
        // The controller claims the slot before submitting the job. Reuse that handle so the
        // background runner cannot replace the snapshot that the controller already exposed.
        Run active = activeRun.get();
        if (active != null && Objects.equals(active.courseId, courseId)) {
            return active;
        }
        Run run = new Run(courseId, model, this::release);
        runs.put(courseId, run);
        return run;
    }

    public Optional<Snapshot> snapshot(Long courseId) {
        return Optional.ofNullable(runs.get(courseId)).map(Run::snapshot);
    }

    /** Mutable, thread-safe handle for one in-flight run. Counters are bumped from worker threads. */
    public static final class Run {

        private final Long courseId;
        private final String model;
        private volatile Phase phase = Phase.DESCRIBING_FIGURES;
        private final AtomicInteger completed = new AtomicInteger();
        /** Sessions the pipeline could not analyse. Any entry makes the run fail atomically. */
        private final AtomicInteger failedSessions = new AtomicInteger();
        private final ConcurrentLinkedQueue<String> failedSessionNames = new ConcurrentLinkedQueue<>();
        private volatile int total;
        private volatile Status status = Status.RUNNING;
        private volatile String error;
        private volatile ExtractionRunner.ExtractionSummary summary;
        private final AtomicInteger lastPercent = new AtomicInteger();
        /** Token counts per "phase / model", in the order the phases first called a model. */
        private final Map<String, TokenTally> usage = Collections.synchronizedMap(new LinkedHashMap<>());
        /** Frees the global slot; run before the terminal status becomes visible. */
        private final Consumer<Run> releaseSlot;

        private Run(Long courseId, String model, Consumer<Run> releaseSlot) {
            this.courseId = courseId;
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
        public void sessionFailed(String sessionName) {
            failedSessions.incrementAndGet();
            failedSessionNames.add(sessionName == null || sessionName.isBlank()
                    ? "Untitled session"
                    : sessionName.strip());
        }

        public int failedSessions() {
            return failedSessions.get();
        }

        public List<String> failedSessionNames() {
            return List.copyOf(failedSessionNames);
        }

        /** Books one answered LLM call to the phase the run is in. */
        public void recordUsage(String model, int prompt, int completion, int reasoning) {
            usage.computeIfAbsent(phase + " / " + model, ignored -> new TokenTally())
                    .add(prompt, completion, reasoning);
        }

        /** One line per phase and model plus a total, for the log at the end of the run. */
        public String usageReport() {
            TokenTally total = new TokenTally();
            StringBuilder report = new StringBuilder();
            synchronized (usage) {
                usage.forEach((key, tally) -> {
                    report.append("\n  ").append(key).append(": ").append(tally);
                    total.add(tally);
                });
            }
            return "total: " + total + report;
        }

        public void succeed(ExtractionRunner.ExtractionSummary summary) {
            this.summary = summary;
            releaseSlot.accept(this);
            this.status = Status.SUCCEEDED;
        }

        public void fail(String message) {
            this.error = message;
            releaseSlot.accept(this);
            this.status = Status.FAILED;
        }

        /** Publishes the committed extraction counts before the separately transactional tree stage. */
        public void checkpoint(ExtractionRunner.ExtractionSummary committedSummary) {
            this.summary = committedSummary;
        }

        private Snapshot snapshot() {
            Status currentStatus = status;
            int percent = currentStatus == Status.SUCCEEDED
                    ? 100
                    : Math.min(99, weightedPercent(phase, completed.get(), total));
            percent = lastPercent.accumulateAndGet(percent, Math::max);
            return new Snapshot(currentStatus, phase, completed.get(), total, model, summary, error, percent,
                    failedSessions.get(), List.copyOf(failedSessionNames));
        }
    }

    private static int weightedPercent(Phase phase, int completed, int total) {
        int[] envelope = switch (phase) {
            case DESCRIBING_FIGURES -> new int[] {0, 40};
            case EXTRACTING -> new int[] {40, 80};
            case PERSISTING -> new int[] {80, 90};
            case SYNTHESIZING -> new int[] {90, 100};
        };
        double fraction = total <= 0 ? 0 : Math.min(1, Math.max(0, (double) completed / total));
        return (int) Math.round(envelope[0] + (envelope[1] - envelope[0]) * fraction);
    }

    /** Running token counts for one phase and model. */
    private static final class TokenTally {

        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicLong prompt = new AtomicLong();
        private final AtomicLong completion = new AtomicLong();
        private final AtomicLong reasoning = new AtomicLong();

        void add(int promptTokens, int completionTokens, int reasoningTokens) {
            calls.incrementAndGet();
            prompt.addAndGet(promptTokens);
            completion.addAndGet(completionTokens);
            reasoning.addAndGet(reasoningTokens);
        }

        void add(TokenTally other) {
            calls.addAndGet(other.calls.get());
            prompt.addAndGet(other.prompt.get());
            completion.addAndGet(other.completion.get());
            reasoning.addAndGet(other.reasoning.get());
        }

        @Override
        public String toString() {
            return "%d calls, %d prompt + %d completion (of which %d reasoning) tokens".formatted(
                    calls.get(), prompt.get(), completion.get(), reasoning.get());
        }
    }

    /**
     * Immutable view returned to pollers.
     *
     * @param failedSessions sessions the run could not analyse.
     * @param failedSessionNames their visible lecture/session names, in completion order.
     */
    public record Snapshot(Status status, Phase phase, int completed, int total, String model,
                           ExtractionRunner.ExtractionSummary summary, String error, int percent,
                           int failedSessions, List<String> failedSessionNames) {
    }

    /** The globally active run and its latest immutable snapshot. */
    public record Current(Long courseId, Snapshot snapshot) {
    }
}
