package de.tum.cit.hestia.materials.extraction;

import java.util.Optional;

import java.util.UUID;

import de.tum.cit.hestia.core.CourseAccess;
import de.tum.cit.hestia.core.CourseApi;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/extractions")
public class CurrentExtractionController {

    private final ExtractionProgressTracker progressTracker;
    private final CourseApi courseApi;
    private final CourseAccess courseAccess;

    public CurrentExtractionController(ExtractionProgressTracker progressTracker,
                                       CourseApi courseApi,
                                       CourseAccess courseAccess) {
        this.courseAccess = courseAccess;
        this.progressTracker = progressTracker;
        this.courseApi = courseApi;
    }

    @ApiResponse(responseCode = "200", description = "OK")
    @ApiResponse(responseCode = "204", description = "No Content", content = @Content)
    @GetMapping("/current")
    public ResponseEntity<CurrentExtractionResponse> current() {
        return progressTracker.current()
                .flatMap(current -> {
                    ExtractionProgressTracker.Snapshot snapshot = current.snapshot();
                    // Another organisation's course is not found; a course the user is no member of is not shown.
                    return Optional.of(current.courseId())
                            .filter(courseAccess::canRead)
                            .map(courseId -> new CurrentExtractionResponse(
                                    current.courseId(),
                                    courseApi.get(courseId).title(),
                                    snapshot.phase(),
                                    snapshot.percent(),
                                    snapshot.status()));
                })
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    public record CurrentExtractionResponse(
            UUID courseId,
            String courseName,
            ExtractionProgressTracker.Phase phase,
            int percent,
            ExtractionProgressTracker.Status status) {
    }
}
