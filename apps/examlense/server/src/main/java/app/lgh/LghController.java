package app.lgh;

import app.shared.Access;
import app.error.ApiException;
import app.exam.Exam;
import app.task.Task;
import app.task.TaskRepository;
import app.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only proxy in front of LearningGoalHub. The frontend cannot reach LGH
 * directly (LRZ-VPN-only, no CORS), so course lists and goal lookups go
 * through here. LGH being down maps to 502 so the UI can degrade instead of
 * crash.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Learning goals", description = """
    Proxy in front of LearningGoalHub. The browser cannot reach LGH directly (VPN-only, no \
    CORS), so course lookups and goal resolution go through here. LGH being unreachable is a \
    502 the UI degrades on rather than an error state.""")
public class LghController {

    private static final Logger log = LoggerFactory.getLogger(LghController.class);

    public record CourseDto(long id, String name) {}

    public record CreateCourseReq(String name) {}

    public record LearningGoalDto(long id, String text, String bloom_level, String solo_level, String status) {}

    private final LearningGoalHubClient client;
    private final Access access;
    private final TaskRepository taskRepository;

    public LghController(LearningGoalHubClient client, Access access, TaskRepository taskRepository) {
        this.client = client;
        this.access = access;
        this.taskRepository = taskRepository;
    }

    @Operation(
        summary = "List LGH courses",
        description = "Every course LGH knows about, for the picker shown when an exam is created.")
    @ApiResponse(responseCode = "502", description = "LearningGoalHub is unreachable.")
    @GetMapping("/lgh/courses")
    public List<CourseDto> courses(@CurrentUser String userId) {
        return viaLgh("list LGH courses", () -> client.listCourses().stream()
            .map(c -> new CourseDto(c.id(), c.name()))
            .toList());
    }

    /** Create a new, empty LGH course (name only) and return it for linking to an exam. */
    @Operation(
        summary = "Create an LGH course",
        description = "Creates an empty course in LGH and returns it, so a new exam can be linked to it.")
    @ApiResponse(responseCode = "400", description = "`name` is missing or blank.")
    @ApiResponse(responseCode = "502", description = "LearningGoalHub is unreachable.")
    @PostMapping("/lgh/courses")
    public CourseDto createCourse(@RequestBody CreateCourseReq req, @CurrentUser String userId) {
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Course name is required");
        }
        return viaLgh("create LGH course", () -> {
            var c = client.createCourse(name);
            return new CourseDto(c.id(), c.name());
        });
    }

    /**
     * The resolved learning goals of an exam: the goals of its linked LGH
     * course, narrowed to the ids actually stored on the exam's tasks.
     */
    @Operation(
        summary = "Resolve an exam's learning goals",
        description = """
            The goals of the exam's linked LGH course, narrowed to the ids actually stored on \
            its tasks. Only ids live in this database — the text, Bloom, and SOLO levels are \
            resolved through LGH at read time.

            Returns an empty list (not an error) when the exam has no linked course or its \
            tasks carry no goal ids.""")
    @ApiResponse(responseCode = "403", description = "The exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such exam, or `id` is not a valid UUID.")
    @ApiResponse(responseCode = "502", description = "LearningGoalHub is unreachable; the client falls back to `Goal #id` placeholders.")
    @GetMapping("/exams/{id}/learning-goals")
    public List<LearningGoalDto> examLearningGoals(@PathVariable String id, @CurrentUser String userId) {
        Exam exam = access.requireExam(Access.id(id), userId);
        if (exam.getLghCourseId() == null) return List.of();

        Set<Long> taskGoalIds = new HashSet<>();
        for (Task t : taskRepository.findByExamIdOrderByPositionAsc(exam.getId())) {
            if (t.getLearningGoalIds() != null) taskGoalIds.addAll(t.getLearningGoalIds());
        }
        if (taskGoalIds.isEmpty()) return List.of();

        // Note: fetches ALL goals of the course (paged) and filters — O(course
        // size), fine at current scale.
        return viaLgh("resolve LGH goals for exam " + exam.getId(),
            () -> client.listGoals(exam.getLghCourseId()).stream()
                .filter(g -> taskGoalIds.contains(g.id()))
                .map(g -> new LearningGoalDto(g.id(), g.text(), g.bloomLevel(), g.soloLevel(), g.status()))
                .toList());
    }

    /** Run an LGH call, mapping any failure to a 502 the UI degrades on. */
    private <T> T viaLgh(String what, java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            log.warn("Could not {}: {}", what, e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "LearningGoalHub unreachable");
        }
    }
}
