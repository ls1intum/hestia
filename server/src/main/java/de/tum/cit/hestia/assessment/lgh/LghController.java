package de.tum.cit.hestia.assessment.lgh;

import de.tum.cit.hestia.assessment.error.ApiException;
import de.tum.cit.hestia.assessment.examination.Examination;
import de.tum.cit.hestia.assessment.shared.Access;
import de.tum.cit.hestia.assessment.taskblock.TaskBlock;
import de.tum.cit.hestia.assessment.taskblock.TaskBlockRepository;
import de.tum.cit.hestia.core.CourseApi;
import de.tum.cit.hestia.core.CourseInfo;
import de.tum.cit.hestia.core.GoalInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Courses and learning goals for the exam pages, served from {@code core}: the courses the caller
 * belongs to (for the picker shown when an exam is created or moved) and the goals of an exam's
 * tasks.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Learning goals", description = """
    Courses of the caller (ids are UUID strings; the personal course is flagged) and the learning \
    goals linked to an exam's tasks, read from the shared goal catalogue so a renamed goal shows \
    its new text.""")
public class LghController {

    public record CourseDto(String id, String name, String role, boolean personal) {
        static CourseDto from(CourseInfo c) {
            return new CourseDto(c.id().toString(), c.title(), c.role().name(), c.personal());
        }
    }

    public record CreateCourseReq(String name) {}

    public record LearningGoalDto(String id, String text, String bloom_level, String solo_level, String status) {
        static LearningGoalDto from(GoalInfo g) {
            return new LearningGoalDto(g.id().toString(), g.text(), g.bloomLevel(), g.soloLevel(),
                g.status() == null ? null : g.status().name());
        }
    }

    private static final int PAGE_SIZE = 100;

    private final CourseApi courses;
    private final Access access;
    private final TaskBlockRepository taskRepository;
    private final TaskGoalLinks goalLinks;

    public LghController(CourseApi courses, Access access, TaskBlockRepository taskRepository,
                         TaskGoalLinks goalLinks) {
        this.courses = courses;
        this.access = access;
        this.taskRepository = taskRepository;
        this.goalLinks = goalLinks;
    }

    @Operation(
        summary = "List the caller's courses",
        description = "Every course the caller is a member of, for the picker shown when an exam is created or moved.")
    @GetMapping("/lgh/courses")
    public List<CourseDto> courses() {
        List<CourseDto> all = new java.util.ArrayList<>();
        for (int page = 0; ; page++) {
            List<CourseInfo> batch = courses.listMine(page, PAGE_SIZE);
            batch.forEach(c -> all.add(CourseDto.from(c)));
            if (batch.size() < PAGE_SIZE) break;
        }
        return all;
    }

    @Operation(
        summary = "Create a course",
        description = "Creates a course with the caller as owner and returns it, so a new exam can be put into it.")
    @ApiResponse(responseCode = "400", description = "`name` is missing or blank.")
    @PostMapping("/lgh/courses")
    public CourseDto createCourse(@RequestBody CreateCourseReq req) {
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Course name is required");
        }
        return CourseDto.from(courses.create(name));
    }

    /** The goals linked to the exam's tasks, each once, with their current wording. */
    @Operation(
        summary = "Resolve an exam's learning goals",
        description = """
            The goals linked to the exam's tasks, each once, with the text, Bloom and SOLO levels \
            they currently have in their course.

            Returns an empty list (not an error) when the tasks carry no goals.""")
    @ApiResponse(responseCode = "403", description = "The caller has no access to the exam's course.")
    @ApiResponse(responseCode = "404", description = "No such exam, or `id` is not a valid UUID.")
    @GetMapping("/exams/{id}/learning-goals")
    public List<LearningGoalDto> examLearningGoals(@PathVariable String id) {
        Examination exam = access.requireExamination(Access.id(id));
        List<UUID> taskIds = taskRepository.findByExamIdOrderByPositionAsc(exam.getId()).stream()
            .map(TaskBlock::getId).toList();
        Map<UUID, LearningGoalDto> goals = new LinkedHashMap<>();
        goalLinks.goalsOf(taskIds).values().forEach(list ->
            list.forEach(g -> goals.putIfAbsent(g.id(), LearningGoalDto.from(g))));
        return List.copyOf(goals.values());
    }
}
