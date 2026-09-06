package app.task;
import app.shared.Access;

import app.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "Answers", description = "AI-generated answers, written by the solve pipeline and read-only here.")
public class TaskAnswerController {

    private final TaskAnswerRepository answerRepository;
    private final Access access;

    public TaskAnswerController(TaskAnswerRepository answerRepository, Access access) {
        this.answerRepository = answerRepository;
        this.access = access;
    }

    /** AI answers are written by the solve services; the frontend only reads them. */
    @Operation(
        summary = "List an exam's AI answers",
        description = "Every answer produced by the solve pipeline for this exam. There is no write endpoint.")
    @ApiResponse(responseCode = "403", description = "The exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such exam, or `examId` is not a valid UUID.")
    @GetMapping("/exams/{examId}/answers")
    public List<TaskDtos.AnswerDto> list(@PathVariable String examId, @CurrentUser String userId) {
        access.requireExam(Access.id(examId), userId);
        return answerRepository.findByExamId(Access.id(examId))
            .stream().map(TaskDtos.AnswerDto::from).toList();
    }
}
