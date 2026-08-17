package de.tum.cit.hestia.learninggoalhub.extraction;

import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/courses/{courseId}")
public class ExtractionController {

    private final ExtractionRunner runner;
    private final ExtractionProgressTracker progressTracker;
    private final CourseRepository courseRepository;
    private final ExecutorService extractionExecutor;

    public ExtractionController(ExtractionRunner runner,
                                ExtractionProgressTracker progressTracker,
                                CourseRepository courseRepository,
                                @Qualifier("extractionExecutor") ExecutorService extractionExecutor) {
        this.runner = runner;
        this.progressTracker = progressTracker;
        this.courseRepository = courseRepository;
        this.extractionExecutor = extractionExecutor;
    }

    @PostMapping("/extract")
    public ResponseEntity<ExtractionStartResponse> extract(
            @PathVariable Long courseId,
            @RequestParam(name = "model", required = false) String model,
            @RequestParam(name = "force", defaultValue = "false") boolean force) {
        if (!courseRepository.existsById(courseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found: " + courseId);
        }

        Optional<ExtractionProgressTracker.Run> claimed = progressTracker.tryStart(courseId, model);
        if (claimed.isEmpty()) {
            String runningCourse = progressTracker.current()
                    .map(current -> current.courseId().toString())
                    .orElse("another course");
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An extraction is already running for course " + runningCourse + ".");
        }

        ExtractionProgressTracker.Run run = claimed.get();
        try {
            extractionExecutor.submit(() -> {
                try {
                    // Marked terminal out here, after runForCourse's transaction has committed, so
                    // a client that polls "SUCCEEDED" can actually read the goals it produced.
                    run.succeed(runner.runForCourse(courseId, model, force));
                } catch (RuntimeException | Error ex) {
                    run.fail(errorMessage(ex));
                } finally {
                    progressTracker.finish(courseId);
                }
            });
        } catch (RuntimeException ex) {
            run.fail(errorMessage(ex));
            progressTracker.finish(courseId);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not queue the extraction.", ex);
        }
        return ResponseEntity.accepted().body(new ExtractionStartResponse(courseId,
                ExtractionProgressTracker.Status.RUNNING));
    }

    /**
     * Rebuilds only the competency tree from the goals this course already has — no documents are
     * re-read, so this costs the three tree synthesis calls instead of a full extraction.
     *
     * <p>Returns 409 when the tree holds instructor work (a hand-added skill or child, a generated
     * subtree, an approved terminal), since a rebuild replaces exactly those. {@code force=true}
     * discards them deliberately.
     */
    @PostMapping("/competency-tree")
    public ExtractionRunner.CompetencyTreeResult rebuildCompetencyTree(
            @PathVariable Long courseId,
            @RequestParam(name = "model", required = false) String model,
            @RequestParam(name = "force", defaultValue = "false") boolean force) {
        return runner.rebuildCompetencyTree(courseId, model, force);
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

    private static String errorMessage(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }

    public record ExtractionStartResponse(Long courseId, ExtractionProgressTracker.Status status) {
    }
}

@Configuration(proxyBeanMethods = false)
class ExtractionExecutorConfiguration {

    @Bean(name = "extractionExecutor", destroyMethod = "shutdownNow")
    ExecutorService extractionExecutor() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "learninggoalhub-extraction");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadExecutor(threadFactory);
    }
}
