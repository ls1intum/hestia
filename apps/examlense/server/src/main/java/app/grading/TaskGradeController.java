package app.grading;
import app.shared.Access;

import app.error.ApiException;
import app.exam.Exam;
import app.task.Task;
import app.task.TaskRepository;
import app.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "Grades", description = "Scores for AI answers — one grade per task, auto or manual.")
public class TaskGradeController {

    public record UpsertGradeRequest(String task_id, String exam_id, BigDecimal score,
                                     boolean auto_graded, String feedback) {}

    private final TaskGradeRepository gradeRepository;
    private final TaskRepository taskRepository;
    private final Access access;

    public TaskGradeController(TaskGradeRepository gradeRepository, TaskRepository taskRepository, Access access) {
        this.gradeRepository = gradeRepository;
        this.taskRepository = taskRepository;
        this.access = access;
    }

    @Operation(
        summary = "List an exam's grades",
        description = "Every grade recorded for this exam. Tasks not yet graded simply have no row here.")
    @ApiResponse(responseCode = "403", description = "The exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such exam, or `examId` is not a valid UUID.")
    @GetMapping("/exams/{examId}/grades")
    public List<GradeDto> list(@PathVariable String examId, @CurrentUser String userId) {
        access.requireExam(Access.id(examId), userId);
        return gradeRepository.findByExamId(Access.id(examId))
            .stream().map(GradeDto::from).toList();
    }

    /**
     * Upsert on task_id (one grade per task), stamping graded_by = caller.
     * Ownership is checked on the TASK (and exam_id derived from it) — trusting
     * the body's exam_id would let a caller grade or hijack a grade row for a
     * task in someone else's exam.
     */
    @Operation(
        summary = "Create or update a task's grade",
        description = """
            Upserts on `task_id` — one grade per task — and stamps the caller as `graded_by`.

            The body's `exam_id` is **ignored**: ownership and the exam are derived from the \
            task, so a caller can't graft a grade onto someone else's exam. Grades are only \
            writable while the exam's status is `grading`; a finished exam must be reopened \
            first, which keeps its results from silently drifting.""")
    @ApiResponse(responseCode = "403", description = "The task's exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such task, or `task_id` is not a valid UUID.")
    @ApiResponse(responseCode = "409", description = "The exam is not in the `grading` status.")
    @PutMapping("/task-grades")
    public GradeDto upsert(@RequestBody UpsertGradeRequest req, @CurrentUser String userId) {
        Task task = access.requireOwnedChild(taskRepository, req.task_id(), userId, Task::getExamId, "Task");
        // Grades are only editable while the exam is being graded. A finished exam
        // must first be re-opened (status → grading) before any score changes; this
        // keeps its "final" (live-derived) results from silently drifting.
        Exam exam = access.requireExam(task.getExamId(), userId);
        if (!"grading".equals(exam.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Grades can only be changed while the exam is being graded.");
        }
        UUID taskId = task.getId();
        TaskGrade g = gradeRepository.findByTaskId(taskId).orElseGet(TaskGrade::new);
        g.setTaskId(taskId);
        g.setExamId(task.getExamId());
        g.setScore(req.score());
        g.setAutoGraded(req.auto_graded());
        g.setFeedback(req.feedback());
        g.setGradedBy(UUID.fromString(userId));
        return GradeDto.from(gradeRepository.save(g));
    }
}
