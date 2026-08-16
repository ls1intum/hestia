package app.task;
import app.shared.Patch;
import app.shared.Access;

import app.error.ApiException;
import app.security.CurrentUser;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TaskController {

    public record CreateTaskRequest(
        String exam_id, Integer position, String type, String section_id,
        List<TaskOption> options, String prompt, String reference_answer,
        String section, BigDecimal points
    ) {}

    private final TaskRepository taskRepository;
    private final Access access;
    private final TaskService taskService;

    public TaskController(TaskRepository taskRepository, Access access, TaskService taskService) {
        this.taskRepository = taskRepository;
        this.access = access;
        this.taskService = taskService;
    }

    @GetMapping("/exams/{examId}/tasks")
    public List<TaskDtos.TaskDto> list(@PathVariable String examId, @CurrentUser String userId) {
        access.requireExam(Access.id(examId), userId);
        return taskRepository.findByExamIdOrderByPositionAsc(Access.id(examId))
            .stream().map(TaskDtos.TaskDto::from).toList();
    }

    @PostMapping("/tasks")
    public TaskDtos.TaskDto create(@RequestBody CreateTaskRequest req, @CurrentUser String userId) {
        UUID examId = Access.id(req.exam_id());
        access.requireExam(examId, userId);
        Task t = new Task();
        t.setExamId(examId);
        t.setPosition(req.position() == null ? 0 : req.position());
        t.setType(req.type());
        t.setSectionId(req.section_id() == null ? null : Access.id(req.section_id()));
        t.setSection(req.section());
        if (req.prompt() != null) t.setPrompt(req.prompt());
        t.setOptions(req.options());
        t.setReferenceAnswer(req.reference_answer());
        t.setPoints(req.points());
        return TaskDtos.TaskDto.from(taskService.addTask(t));
    }

    @PatchMapping("/tasks/{id}")
    public TaskDtos.TaskDto patch(@PathVariable String id, @RequestBody Map<String, Object> body,
                              @CurrentUser String userId) {
        Task t = load(id, userId);
        if (Patch.has(body, "position")) t.setPosition(Patch.intVal(body.get("position")));
        if (Patch.has(body, "type")) t.setType(Patch.str(body.get("type")));
        if (Patch.has(body, "prompt")) t.setPrompt(Patch.str(body.get("prompt")));
        if (Patch.has(body, "options")) t.setOptions(Patch.options(body.get("options")));
        if (Patch.has(body, "reference_answer")) t.setReferenceAnswer(Patch.str(body.get("reference_answer")));
        if (Patch.has(body, "points")) t.setPoints(Patch.bigDecimal(body.get("points")));
        if (Patch.has(body, "section")) t.setSection(Patch.str(body.get("section")));
        if (Patch.has(body, "section_id")) {
            UUID sectionId = Patch.uuid(body.get("section_id"));
            // Guard cross-exam reassignment: the target section must belong to this task's exam.
            if (sectionId != null) access.requireSectionInExam(sectionId, t.getExamId());
            t.setSectionId(sectionId);
        }
        if (Patch.has(body, "parse_confidence")) t.setParseConfidence(Patch.str(body.get("parse_confidence")));
        if (Patch.has(body, "learning_goal_ids")) t.setLearningGoalIds(Patch.longList(body.get("learning_goal_ids")));
        return TaskDtos.TaskDto.from(taskRepository.save(t));
    }

    @DeleteMapping("/tasks/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, @CurrentUser String userId) {
        Task t = load(id, userId);
        taskRepository.delete(t);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/exams/{examId}/tasks")
    public ResponseEntity<Void> deleteBySection(@PathVariable String examId,
                                                 @RequestParam("section_id") String sectionId,
                                                 @CurrentUser String userId) {
        access.requireExam(Access.id(examId), userId);
        taskRepository.deleteByExamIdAndSectionId(Access.id(examId), Access.id(sectionId));
        return ResponseEntity.noContent().build();
    }

    private Task load(String id, String userId) {
        return access.requireOwnedChild(taskRepository, id, userId, Task::getExamId, "Task");
    }
}
