package de.tum.cit.hestia.learninggoalhub.extraction;

import de.tum.cit.hestia.learninggoalhub.topictree.CompetencyTreeResult;
import de.tum.cit.hestia.learninggoalhub.topictree.TopicTreeService;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import de.tum.cit.hestia.learninggoalhub.course.ExtractionStatus;
import de.tum.cit.hestia.learninggoalhub.web.ExtractionErrors;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
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

    private static final Logger log = LoggerFactory.getLogger(ExtractionController.class);

    private final ExtractionRunner runner;
    private final TopicTreeService topicTrees;
    private final ExtractionProgressTracker progressTracker;
    private final ExtractionRunRepository extractionRunRepository;
    private final ExtractionRunAuditService extractionRunAuditService;
    private final CourseRepository courseRepository;
    private final ExecutorService extractionExecutor;

    public ExtractionController(ExtractionRunner runner,
                                TopicTreeService topicTrees,
                                ExtractionProgressTracker progressTracker,
                                ExtractionRunRepository extractionRunRepository,
                                ExtractionRunAuditService extractionRunAuditService,
                                CourseRepository courseRepository,
                                @Qualifier("extractionExecutor") ExecutorService extractionExecutor) {
        this.runner = runner;
        this.topicTrees = topicTrees;
        this.progressTracker = progressTracker;
        this.extractionRunRepository = extractionRunRepository;
        this.extractionRunAuditService = extractionRunAuditService;
        this.courseRepository = courseRepository;
        this.extractionExecutor = extractionExecutor;
    }

    @PostMapping("/extract")
    public ResponseEntity<ExtractionStartResponse> extract(
            @PathVariable Long courseId,
            @RequestParam(name = "force", defaultValue = "false") boolean force) {
        courseRepository.requireExists(courseId);

        Optional<ExtractionProgressTracker.Run> claimed = progressTracker.tryStart(courseId, null);
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
                    run.succeed(runner.runForCourse(courseId, null, force));
                } catch (RuntimeException | Error ex) {
                    log.warn("Extraction of course {} failed", courseId, ex);
                    run.fail(ExtractionErrors.describe(ex));
                } finally {
                    progressTracker.release(run);
                }
            });
        } catch (RuntimeException ex) {
            run.fail(ExtractionErrors.describe(ex));
            progressTracker.release(run);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not queue the extraction.", ex);
        }
        return ResponseEntity.accepted().body(new ExtractionStartResponse(courseId,
                ExtractionProgressTracker.Status.RUNNING));
    }

    /**
     * Rebuilds only the Topic tree from the goals this course already has — no documents are
     * re-read, so this runs only the taxonomy planning, assignment/review, and classification calls
     * instead of a full extraction.
     *
     * <p>Returns 409 when the tree holds instructor work (a hand-added skill or child, a generated
     * subtree, an approved terminal), since a rebuild replaces exactly those. {@code force=true}
     * discards them deliberately.
     */
    @PostMapping("/competency-tree")
    public CompetencyTreeResult rebuildCompetencyTree(
            @PathVariable Long courseId,
            @RequestParam(name = "force", defaultValue = "false") boolean force) {
        Optional<ExtractionProgressTracker.Run> claimed = progressTracker.tryStart(courseId, null);
        if (claimed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Another extraction or Topic tree rebuild is already running.");
        }
        ExtractionProgressTracker.Run run = claimed.get();
        run.phase(ExtractionProgressTracker.Phase.SYNTHESIZING, 1);
        try {
            CompetencyTreeResult result = topicTrees.rebuildTopicTree(courseId, null, force);
            run.increment();
            extractionRunAuditService.finishTreeRetry(courseId);
            run.succeed(new ExtractionRunner.ExtractionSummary(0, 0, result.competencies()));
            return result;
        } catch (RuntimeException | Error ex) {
            run.fail(ExtractionErrors.describe(ex));
            throw ex;
        } finally {
            progressTracker.release(run);
        }
    }

    /**
     * Progress of the in-flight (or most recent) extraction for this course, for the client to poll
     * while {@code POST /extract} is still running. Returns 204 when no run has been started yet.
     */
    @GetMapping("/extract/status")
    public ResponseEntity<ExtractionProgressTracker.Snapshot> extractStatus(@PathVariable Long courseId) {
        Optional<ExtractionProgressTracker.Snapshot> live = progressTracker.snapshot(courseId);
        if (live.isPresent()) {
            return ResponseEntity.ok(live.get());
        }
        return extractionRunRepository.findFirstByCourseIdOrderByStartedAtDesc(courseId)
                .map(ExtractionController::durableSnapshot)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    private static ExtractionProgressTracker.Snapshot durableSnapshot(ExtractionRun extractionRun) {
        boolean succeeded = extractionRun.getStatus() == ExtractionStatus.SUCCEEDED;
        ExtractionProgressTracker.Status status = succeeded
                ? ExtractionProgressTracker.Status.SUCCEEDED
                : ExtractionProgressTracker.Status.FAILED;
        String error = extractionRun.getError();
        if (extractionRun.getStatus() == ExtractionStatus.RUNNING) {
            error = "The extraction was interrupted before it finished. Retry the extraction.";
        }
        ExtractionRunner.ExtractionSummary summary = extractionRun.getGoalsCreated() == null
                ? null
                : new ExtractionRunner.ExtractionSummary(0, extractionRun.getGoalsCreated(), 0);
        return new ExtractionProgressTracker.Snapshot(
                status,
                ExtractionProgressTracker.Phase.SYNTHESIZING,
                0,
                0,
                extractionRun.getModel(),
                summary,
                error,
                succeeded ? 100 : 99,
                extractionRun.getFailedSessions() == null ? 0 : extractionRun.getFailedSessions(),
                List.of(),
                extractionRun.getDroppedOutcomes() == null ? 0 : extractionRun.getDroppedOutcomes());
    }

    public record ExtractionStartResponse(Long courseId, ExtractionProgressTracker.Status status) {
    }
}

@Configuration(proxyBeanMethods = false)
class ExtractionExecutorConfiguration {

    @Bean
    TransactionOperations extractionTransactions(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

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
