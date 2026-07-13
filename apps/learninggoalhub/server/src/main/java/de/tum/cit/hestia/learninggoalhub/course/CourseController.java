package de.tum.cit.hestia.learninggoalhub.course;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/courses")
public class CourseController {

    private final CourseRepository courseRepository;

    public CourseController(CourseRepository courseRepository) {
        this.courseRepository = courseRepository;
    }

    @PostMapping
    public ResponseEntity<CourseResponse> create(@Valid @RequestBody CreateCourseRequest request) {
        Course saved = courseRepository.save(new Course(request.name()));
        return ResponseEntity
                .created(URI.create("/api/courses/" + saved.getId()))
                .body(CourseResponse.from(saved));
    }

    @GetMapping
    public PagedModel<CourseSummaryResponse> listCourses(
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        Page<Course> page = courseRepository.findAll(pageable);
        List<Long> ids = page.getContent().stream().map(Course::getId).toList();
        Map<Long, Long> goalCounts = ids.isEmpty()
                ? Map.of()
                : toCountMap(courseRepository.countGoalsByCourseIds(ids));
        Map<Long, Long> documentCounts = ids.isEmpty()
                ? Map.of()
                : toCountMap(courseRepository.countDocumentsByCourseIds(ids));
        // PagedModel serializes a stable {content, page} JSON shape, unlike PageImpl whose format
        // is an implementation detail that has changed across Spring Data versions.
        return new PagedModel<>(page.map(course -> new CourseSummaryResponse(
                course.getId(),
                course.getName(),
                course.getCreatedAt(),
                documentCounts.getOrDefault(course.getId(), 0L),
                goalCounts.getOrDefault(course.getId(), 0L))));
    }

    @GetMapping("/{id}")
    public CourseSummaryResponse getCourse(@PathVariable Long id) {
        Course course = courseRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found: " + id));
        List<Long> ids = List.of(course.getId());
        long goalCount = toCountMap(courseRepository.countGoalsByCourseIds(ids)).getOrDefault(id, 0L);
        long documentCount = toCountMap(courseRepository.countDocumentsByCourseIds(ids)).getOrDefault(id, 0L);
        return new CourseSummaryResponse(
                course.getId(), course.getName(), course.getCreatedAt(), documentCount, goalCount);
    }

    /**
     * Deletes a course and everything that hangs off it. The schema declares {@code ON DELETE
     * CASCADE} on every {@code course_id} foreign key (documents, sections, learning goals,
     * goal sources, hierarchy nodes and goal relationships), so removing the course row is enough.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!courseRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found: " + id);
        }
        courseRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private static Map<Long, Long> toCountMap(List<CourseRepository.CourseCount> counts) {
        return counts.stream().collect(Collectors.toMap(
                CourseRepository.CourseCount::getCourseId,
                CourseRepository.CourseCount::getCount));
    }

    public record CreateCourseRequest(@NotBlank String name) {
    }

    public record CourseResponse(Long id, String name) {
        static CourseResponse from(Course course) {
            return new CourseResponse(course.getId(), course.getName());
        }
    }

    public record CourseSummaryResponse(
            Long id, String name, OffsetDateTime createdAt, long documentCount, long goalCount) {
    }
}
