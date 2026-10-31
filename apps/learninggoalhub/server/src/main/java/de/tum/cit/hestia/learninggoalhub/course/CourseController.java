package de.tum.cit.hestia.learninggoalhub.course;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/courses")
public class CourseController {

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    /** The width of the {@code course.name} column. */
    static final int MAX_NAME_LENGTH = 255;

    @PostMapping
    public ResponseEntity<CourseResponse> create(@Valid @RequestBody CreateCourseRequest request) {
        Course saved = courseService.create(request.name(), request.outputLanguage(), request.figuresEnabled());
        return ResponseEntity
                .created(URI.create("/api/courses/" + saved.getId()))
                .body(CourseResponse.from(saved));
    }

    @GetMapping
    public PagedModel<CourseSummaryResponse> listCourses(
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        // PagedModel serializes a stable {content, page} JSON shape, unlike PageImpl whose format
        // is an implementation detail that has changed across Spring Data versions.
        return new PagedModel<>(courseService.summaries(pageable));
    }

    @GetMapping("/{id}")
    public CourseSummaryResponse getCourse(@PathVariable Long id) {
        return courseService.summary(id);
    }

    /**
     * Marks the course's one-time skill review as done. The client shows that review the first time
     * a course is opened after its extraction produced skills; this records the dismissal so it does
     * not reappear. Its own endpoint rather than a field on {@code PATCH}: it records a moment
     * the server sets, not a value the client chooses.
     */
    @ApiResponse(responseCode = "204", description = "No Content", content = @Content)
    @PostMapping("/{id}/skills-reviewed")
    public ResponseEntity<Void> markSkillsReviewed(@PathVariable Long id) {
        courseService.markSkillsReviewed(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Updates a course. Every field is left alone when omitted, so a rename need not know the
     * course's other settings; an explicit {@code outputLanguage: null} means "back to auto-detect".
     */
    @PatchMapping("/{id}")
    public CourseResponse update(@PathVariable Long id, @RequestBody UpdateCourseRequest request) {
        return CourseResponse.from(courseService.update(id, request));
    }

    /**
     * Deletes a course and everything that hangs off it. The schema declares {@code ON DELETE
     * CASCADE} on every {@code course_id} foreign key (documents, sections, learning goals,
     * goal sources, hierarchy nodes and goal relationships), so removing the course row is enough.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        courseService.delete(id);
        return ResponseEntity.noContent().build();
    }

    public record CreateCourseRequest(@NotBlank @Size(min = 1, max = MAX_NAME_LENGTH) String name, String outputLanguage,
                                      Boolean figuresEnabled) {
    }

    public record CourseResponse(Long id, String name, String outputLanguage, boolean figuresEnabled) {
        static CourseResponse from(Course course) {
            return new CourseResponse(course.getId(), course.getName(), course.getOutputLanguage(),
                    course.isFiguresEnabled());
        }
    }

}
