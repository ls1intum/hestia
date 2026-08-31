package de.tum.cit.hestia.learninggoalhub.extraction;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists extraction audit state independently of the extraction transaction. */
@Service
public class ExtractionRunAuditService {

    private final CourseRepository courseRepository;
    private final ExtractionRunRepository extractionRunRepository;

    public ExtractionRunAuditService(CourseRepository courseRepository,
                                     ExtractionRunRepository extractionRunRepository) {
        this.courseRepository = courseRepository;
        this.extractionRunRepository = extractionRunRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long start(Long courseId, String model, String promptVersion, String params) {
        Course course = courseRepository.findById(courseId).orElseThrow();
        return extractionRunRepository.saveAndFlush(new ExtractionRun(course, model, promptVersion, params)).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(Long runId, ExtractionRun.Status status, String error, Integer goalsCreated,
                       Integer failedSessions, String promptVersion) {
        ExtractionRun run = extractionRunRepository.findById(runId).orElseThrow();
        run.finish(status, error, goalsCreated, failedSessions, promptVersion);
        extractionRunRepository.saveAndFlush(run);
    }

    /** Marks a failed extraction complete after its already-committed goals gained a tree on retry. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishTreeRetry(Long courseId) {
        extractionRunRepository.findFirstByCourseIdOrderByStartedAtDesc(courseId)
                .filter(run -> run.getStatus() == ExtractionRun.Status.FAILED)
                .filter(run -> run.getGoalsCreated() != null)
                .ifPresent(run -> {
                    run.finish(ExtractionRun.Status.SUCCEEDED, null, run.getGoalsCreated(),
                            run.getFailedSessions(), run.getPromptVersion());
                    extractionRunRepository.saveAndFlush(run);
                });
    }
}
