package app.lgh;

import app.api.Access;
import app.error.ApiException;
import app.persistence.entity.Exam;
import app.persistence.entity.Task;
import app.persistence.repository.TaskRepository;
import app.security.CurrentUser;
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

    @GetMapping("/lgh/courses")
    public List<CourseDto> courses(@CurrentUser String userId) {
        return viaLgh("list LGH courses", () -> client.listCourses().stream()
            .map(c -> new CourseDto(c.id(), c.name()))
            .toList());
    }

    /** Create a new, empty LGH course (name only) and return it for linking to an exam. */
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
