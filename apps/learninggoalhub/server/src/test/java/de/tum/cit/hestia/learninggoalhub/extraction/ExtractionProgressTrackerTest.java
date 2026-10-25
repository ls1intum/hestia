package de.tum.cit.hestia.learninggoalhub.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExtractionProgressTrackerTest {

    @Test
    void anOldRunsLateReleaseKeepsANewerSameCourseRunsSlot() {
        ExtractionProgressTracker tracker = new ExtractionProgressTracker();
        ExtractionProgressTracker.Run first = tracker.tryStart(1L, null).orElseThrow();
        // Turning terminal frees the slot, so a second run of the same course can claim it before
        // the first run's caller reaches its finally block.
        first.succeed(new ExtractionRunner.ExtractionSummary(0, 0, 0));
        ExtractionProgressTracker.Run second = tracker.tryStart(1L, null).orElseThrow();

        tracker.release(first);

        assertThat(tracker.activeRun()).containsSame(second);
        assertThat(tracker.tryStart(2L, null)).isEmpty();
    }

    @Test
    void releasingTheHolderFreesTheSlot() {
        ExtractionProgressTracker tracker = new ExtractionProgressTracker();
        ExtractionProgressTracker.Run run = tracker.tryStart(1L, null).orElseThrow();

        tracker.release(run);

        assertThat(tracker.activeRun()).isEmpty();
        assertThat(tracker.tryStart(2L, null)).isPresent();
    }

    @Test
    void theRunnerReusesTheHandleTheControllerClaimed() {
        ExtractionProgressTracker tracker = new ExtractionProgressTracker();
        ExtractionProgressTracker.Run claimed = tracker.tryStart(1L, null).orElseThrow();

        assertThat(tracker.start(1L, null)).isSameAs(claimed);
        assertThat(tracker.current()).hasValueSatisfying(current -> assertThat(current.courseId()).isEqualTo(1L));
    }
}
