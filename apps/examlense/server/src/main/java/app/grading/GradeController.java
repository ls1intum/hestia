package app.grading;
import app.shared.Access;

import app.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "Grades", description = "Scores for AI answers — one grade per current answer, auto or manual.")
public class GradeController {

    public record UpsertGradeRequest(String task_id, String exam_id, BigDecimal score,
                                     boolean auto_graded) {}

    private final GradeRepository gradeRepository;
    private final Access access;
    private final GradeService gradeService;

    public GradeController(GradeRepository gradeRepository, Access access, GradeService gradeService) {
        this.gradeRepository = gradeRepository;
        this.access = access;
        this.gradeService = gradeService;
    }

    @Operation(
        summary = "List an exam's grades",
        description = "Every grade recorded for this exam. Tasks not yet graded simply have no row here.")
    @ApiResponse(responseCode = "403", description = "The exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such exam, or `examId` is not a valid UUID.")
    @GetMapping("/exams/{examId}/grades")
    public List<GradeDto> list(@PathVariable String examId, @CurrentUser String userId) {
        access.requireExamination(Access.id(examId), userId);
        return gradeRepository.findByExamId(Access.id(examId))
            .stream().map(GradeDto::from).toList();
    }

    /**
     * Resolve the task's current answer and upsert its grade, stamping graded_by = caller.
     * Ownership is checked on the TASK (and exam_id derived from it) — trusting
     * the body's exam_id would let a caller grade or hijack a grade row for a
     * task in someone else's exam.
     */
    @Operation(
        summary = "Create or update a task's grade",
        description = """
            Resolves the task's current AI answer, upserts that answer's grade, and stamps the \
            caller as `graded_by`.

            The body's `exam_id` is **ignored**: ownership and the exam are derived from the \
            task, so a caller can't graft a grade onto someone else's exam. Grades are only \
            writable while the exam's status is `grading`; a finished exam must be reopened \
            first, which keeps its results from silently drifting.""")
    @ApiResponse(responseCode = "403", description = "The task's exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such task, or `task_id` is not a valid UUID.")
    @ApiResponse(responseCode = "409", description = "The exam is not in `grading`, or the task has no AI answer.")
    @PutMapping("/task-grades")
    public GradeDto upsert(@RequestBody UpsertGradeRequest req, @CurrentUser String userId) {
        return GradeDto.from(gradeService.upsert(
            req.task_id(), req.score(), req.auto_graded(), userId));
    }
}
