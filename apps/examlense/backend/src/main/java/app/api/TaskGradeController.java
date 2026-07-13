package app.api;

import app.persistence.entity.Task;
import app.persistence.entity.TaskGrade;
import app.persistence.repository.TaskGradeRepository;
import app.persistence.repository.TaskRepository;
import app.security.CurrentUser;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
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

    @GetMapping("/exams/{examId}/grades")
    public List<Dtos.GradeDto> list(@PathVariable String examId, @CurrentUser String userId) {
        access.requireExam(Access.id(examId), userId);
        return gradeRepository.findByExamId(Access.id(examId))
            .stream().map(Dtos.GradeDto::from).toList();
    }

    /**
     * Upsert on task_id (one grade per task), stamping graded_by = caller.
     * Ownership is checked on the TASK (and exam_id derived from it) — trusting
     * the body's exam_id would let a caller grade or hijack a grade row for a
     * task in someone else's exam.
     */
    @PutMapping("/task-grades")
    public Dtos.GradeDto upsert(@RequestBody UpsertGradeRequest req, @CurrentUser String userId) {
        Task task = access.requireOwnedChild(taskRepository, req.task_id(), userId, Task::getExamId, "Task");
        UUID taskId = task.getId();
        TaskGrade g = gradeRepository.findByTaskId(taskId).orElseGet(TaskGrade::new);
        g.setTaskId(taskId);
        g.setExamId(task.getExamId());
        g.setScore(req.score());
        g.setAutoGraded(req.auto_graded());
        g.setFeedback(req.feedback());
        g.setGradedBy(UUID.fromString(userId));
        return Dtos.GradeDto.from(gradeRepository.save(g));
    }
}
