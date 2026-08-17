package de.tum.cit.hestia.learninggoalhub.extraction;

import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/extractions")
public class CurrentExtractionController {

    private final ExtractionProgressTracker progressTracker;
    private final CourseRepository courseRepository;

    public CurrentExtractionController(ExtractionProgressTracker progressTracker,
                                       CourseRepository courseRepository) {
        this.progressTracker = progressTracker;
        this.courseRepository = courseRepository;
    }

    @GetMapping("/current")
    public ResponseEntity<CurrentExtractionResponse> current() {
        return progressTracker.current()
                .flatMap(current -> {
                    ExtractionProgressTracker.Snapshot snapshot = current.snapshot();
                    return courseRepository.findById(current.courseId())
                            .map(course -> new CurrentExtractionResponse(
                                    current.courseId(),
                                    course.getName(),
                                    snapshot.phase(),
                                    snapshot.percent(),
                                    snapshot.status()));
                })
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    public record CurrentExtractionResponse(
            Long courseId,
            String courseName,
            ExtractionProgressTracker.Phase phase,
            int percent,
            ExtractionProgressTracker.Status status) {
    }
}
