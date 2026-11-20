package de.tum.cit.hestia.assessment.taskblock;
import de.tum.cit.hestia.assessment.shared.Patch;
import de.tum.cit.hestia.assessment.shared.Access;

import de.tum.cit.hestia.assessment.error.ApiException;
import de.tum.cit.hestia.assessment.lgh.TaskGoalLinks;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Tasks", description = """
    The exam's questions. `type` is one of `single_choice`, `multiple_choice`, or `text`; a task \
    may belong to a section or sit unassigned.""")
public class TaskBlockController {

    public record CreateTaskBlockRequest(
        String exam_id, Integer position, String type, String section_id,
        List<AnswerOption> options, String prompt, String reference_answer,
        String section, BigDecimal points
    ) {}

    private final TaskBlockRepository taskRepository;
    private final Access access;
    private final TaskBlockService taskService;
    private final TaskGoalLinks goalLinks;

    public TaskBlockController(TaskBlockRepository taskRepository, Access access, TaskBlockService taskService,
                               TaskGoalLinks goalLinks) {
        this.goalLinks = goalLinks;
        this.taskRepository = taskRepository;
        this.access = access;
        this.taskService = taskService;
    }

    @Operation(
        summary = "List an exam's tasks",
        description = """
            Ordered by `position`, across all sections. Each task carries its learning goals \
            (`learning_goal_ids` and `learning_goals`) as they currently read in the course, so a \
            renamed goal shows its new text.""")
    @ApiResponse(responseCode = "403", description = "The exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such exam, or `examId` is not a valid UUID.")
    @GetMapping("/exams/{examId}/tasks")
    public List<TaskBlockDtos.TaskBlockDto> list(@PathVariable String examId) {
        access.requireExamination(Access.id(examId));
        List<TaskBlock> tasks = taskRepository.findByExamIdOrderByPositionAsc(Access.id(examId));
        var goals = goalLinks.goalsOf(tasks.stream().map(TaskBlock::getId).toList());
        return tasks.stream()
            .map(t -> TaskBlockDtos.TaskBlockDto.from(t, goals.getOrDefault(t.getId(), List.of())))
            .toList();
    }

    @Operation(
        summary = "Create a task",
        description = """
            Inserts at `position` (default 0), shifting later items down. A null `section_id` \
            leaves the task unassigned. `options` is only meaningful for the choice types.""")
    @ApiResponse(responseCode = "403", description = "The exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such exam, or an id is not a valid UUID.")
    @PostMapping("/tasks")
    public TaskBlockDtos.TaskBlockDto create(@RequestBody CreateTaskBlockRequest req) {
        UUID examId = Access.id(req.exam_id());
        access.requireExaminationForWrite(examId);
        TaskBlock t = new TaskBlock();
        t.setExamId(examId);
        t.setPosition(req.position() == null ? 0 : req.position());
        t.setType(req.type());
        t.setSectionId(req.section_id() == null ? null : Access.id(req.section_id()));
        t.setSection(req.section());
        if (req.prompt() != null) t.setPrompt(req.prompt());
        t.setOptions(req.options());
        t.setReferenceAnswer(req.reference_answer());
        t.setPoints(req.points());
        return TaskBlockDtos.TaskBlockDto.from(taskService.addTaskBlock(t));
    }

    @Operation(
        summary = "Update a task",
        description = """
            Sparse update accepting `position`, `type`, `prompt`, `options`, `reference_answer`, \
            `points`, `section`, `section_id` and `parse_confidence`.

            Moving a task via `section_id` is checked: the target section must belong to the same \
            exam. The task's learning goals are alignment links written by goal generation; they are \
            not patchable here.""")
    @ApiResponse(responseCode = "400", description = "The target `section_id` is unknown or belongs to a different exam.")
    @ApiResponse(responseCode = "403", description = "The task's exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such task, or `id` is not a valid UUID.")
    @PatchMapping("/tasks/{id}")
    public TaskBlockDtos.TaskBlockDto patch(@PathVariable String id, @RequestBody Map<String, Object> body) {
        TaskBlock t = load(id, true);
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
            if (sectionId != null) access.requireSectionInExamination(sectionId, t.getExamId());
            t.setSectionId(sectionId);
        }
        if (Patch.has(body, "parse_confidence")) t.setParseConfidence(Patch.str(body.get("parse_confidence")));
        TaskBlock saved = taskRepository.save(t);
        return TaskBlockDtos.TaskBlockDto.from(saved,
            goalLinks.goalsOf(List.of(saved.getId())).getOrDefault(saved.getId(), List.of()));
    }

    @Operation(summary = "Delete a task", description = "Its answer and grade cascade with it.")
    @ApiResponse(responseCode = "204", description = "Deleted.")
    @ApiResponse(responseCode = "403", description = "The task's exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such task, or `id` is not a valid UUID.")
    @DeleteMapping("/tasks/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        TaskBlock t = load(id, true);
        goalLinks.unlinkAll(List.of(t.getId()));
        taskRepository.delete(t);
        return ResponseEntity.noContent().build();
    }

    @Operation(
        summary = "Delete every task in a section",
        description = "Bulk delete used when the editor clears a section. Deleting the section itself does this too.")
    @ApiResponse(responseCode = "204", description = "Deleted.")
    @ApiResponse(responseCode = "403", description = "The exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such exam, or an id is not a valid UUID.")
    @DeleteMapping("/exams/{examId}/tasks")
    public ResponseEntity<Void> deleteBySection(@PathVariable String examId,
                                                 @RequestParam("section_id")
                                                 @Parameter(description = "Section whose tasks are removed.")
                                                 String sectionId) {
        access.requireExaminationForWrite(Access.id(examId));
        goalLinks.unlinkAll(taskRepository.findByExamIdAndSectionIdOrderByPositionAsc(Access.id(examId), Access.id(sectionId))
            .stream().map(TaskBlock::getId).toList());
        taskRepository.deleteByExamIdAndSectionId(Access.id(examId), Access.id(sectionId));
        return ResponseEntity.noContent().build();
    }

    private TaskBlock load(String id, boolean write) {
        return access.requireChild(taskRepository, id, write, TaskBlock::getExamId, "Task");
    }
}
