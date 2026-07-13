package de.tum.cit.hestia.learninggoalhub.exam;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoalController.LearningGoalResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Consumer-facing endpoint (ExamLens): derives and persists learning goals for the tasks of an
 * exam. See {@code docs/api.md} for the contract.
 */
@RestController
@RequestMapping("/api/courses/{courseId}/exam-tasks")
public class ExamGoalController {

    private final CourseRepository courseRepository;
    private final ExamGoalService examGoalService;

    public ExamGoalController(CourseRepository courseRepository, ExamGoalService examGoalService) {
        this.courseRepository = courseRepository;
        this.examGoalService = examGoalService;
    }

    @Operation(summary = "Generate learning goals for exam tasks",
            description = "Takes an exam as an ordered list of context/task blocks, derives the learning goals "
                    + "each task assesses (one LLM call per task, with all preceding context blocks attached), "
                    + "persists them under the course's EXAM hierarchy root and returns them per task block. "
                    + "Synchronous — expect a few seconds per task block.")
    @PostMapping("/learning-goals")
    public List<ExamTaskGoalsResponse> generate(@PathVariable Long courseId,
                                                @RequestBody GenerateExamGoalsRequest request,
                                                @Parameter(description = "Optional SAIA chat-model override; omit for the configured default.")
                                                @RequestParam(name = "model", required = false) String model) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Course not found: " + courseId));
        validate(request);

        return examGoalService.generateForBlocks(course, request.blocks(), model).stream()
                .map(taskGoals -> new ExamTaskGoalsResponse(
                        taskGoals.blockId(),
                        taskGoals.goals().stream()
                                .map(g -> LearningGoalResponse.from(g, List.of(), List.of()))
                                .toList()))
                .toList();
    }

    private static void validate(GenerateExamGoalsRequest request) {
        if (request == null || request.blocks() == null || request.blocks().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "blocks must not be empty");
        }
        boolean hasTask = false;
        for (ExamBlock block : request.blocks()) {
            if (block.blockType() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "blockType is required (CONTEXT or TASK) for block " + block.blockId());
            }
            if (block.blockType() == ExamBlockType.TASK) {
                hasTask = true;
                if (block.blockId() == null || block.blockId().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "blockId is required for task blocks");
                }
                if (block.description() == null || block.description().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "description must not be blank for task block " + block.blockId());
                }
            }
        }
        if (!hasTask) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "blocks must contain at least one TASK block");
        }
    }

    /** The exam to derive goals for; blocks in exam order (context blocks apply to later tasks). */
    public record GenerateExamGoalsRequest(List<ExamBlock> blocks) {
    }

    /** The goals created for one TASK block, keyed by the consumer's {@code blockId}. */
    public record ExamTaskGoalsResponse(String blockId, List<LearningGoalResponse> goals) {
    }
}
