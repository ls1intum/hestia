package de.tum.cit.hestia.learninggoalhub.extraction;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/courses/{courseId}")
public class ExtractionController {

    private final ExtractionRunner runner;
    private final ExtractionProgressTracker progressTracker;

    public ExtractionController(ExtractionRunner runner, ExtractionProgressTracker progressTracker) {
        this.runner = runner;
        this.progressTracker = progressTracker;
    }

    @PostMapping("/extract")
    public ExtractionRunner.ExtractionSummary extract(@PathVariable Long courseId,
                                                      @RequestParam(name = "model", required = false) String model) {
        return runner.runForCourse(courseId, model);
    }

    /**
     * Rebuilds ONLY the competency tree from the course's already-extracted goals, without re-running
     * the costly extraction/classification/embedding pipeline. Tears down the existing tree first, so
     * the tree can be re-tuned independently and repeatedly.
     */
    @PostMapping("/competency-tree")
    public ExtractionRunner.CompetencyTreeResult rebuildCompetencyTree(
            @PathVariable Long courseId,
            @RequestParam(name = "model", required = false) String model) {
        return runner.rebuildCompetencyTree(courseId, model);
    }

    /**
     * Progress of the in-flight (or most recent) extraction for this course, for the client to poll
     * while {@code POST /extract} is still running. Returns 204 when no run has been started yet.
     */
    @GetMapping("/extract/status")
    public ResponseEntity<ExtractionProgressTracker.Snapshot> extractStatus(@PathVariable Long courseId) {
        return progressTracker.snapshot(courseId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
