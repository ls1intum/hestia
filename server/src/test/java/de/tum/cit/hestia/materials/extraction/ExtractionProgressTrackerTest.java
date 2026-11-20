package de.tum.cit.hestia.materials.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExtractionProgressTrackerTest {

    private static final java.util.UUID ONE = java.util.UUID.randomUUID();
    private static final java.util.UUID TWO = java.util.UUID.randomUUID();

    @Test
    void anOldRunsLateReleaseKeepsANewerSameCourseRunsSlot() {
        ExtractionProgressTracker tracker = new ExtractionProgressTracker();
        ExtractionProgressTracker.Run first = tracker.tryStart(ONE, null).orElseThrow();
        // Turning terminal frees the slot, so a second run of the same course can claim it before
        // the first run's caller reaches its finally block.
        first.succeed(new ExtractionRunner.ExtractionSummary(0, 0, 0));
        ExtractionProgressTracker.Run second = tracker.tryStart(ONE, null).orElseThrow();

        tracker.release(first);

        assertThat(tracker.activeRun()).containsSame(second);
        assertThat(tracker.tryStart(TWO, null)).isEmpty();
    }

    @Test
    void releasingTheHolderFreesTheSlot() {
        ExtractionProgressTracker tracker = new ExtractionProgressTracker();
        ExtractionProgressTracker.Run run = tracker.tryStart(ONE, null).orElseThrow();

        tracker.release(run);

        assertThat(tracker.activeRun()).isEmpty();
        assertThat(tracker.tryStart(TWO, null)).isPresent();
    }

    @Test
    void theRunnerReusesTheHandleTheControllerClaimed() {
        ExtractionProgressTracker tracker = new ExtractionProgressTracker();
        ExtractionProgressTracker.Run claimed = tracker.tryStart(ONE, null).orElseThrow();

        assertThat(tracker.start(ONE, null)).isSameAs(claimed);
        assertThat(tracker.current()).hasValueSatisfying(current -> assertThat(current.courseId()).isEqualTo(ONE));
    }
}
