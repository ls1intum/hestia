package app.api;

import app.persistence.repository.TaskAnswerRepository;
import app.security.CurrentUser;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TaskAnswerController {

    private final TaskAnswerRepository answerRepository;
    private final Access access;

    public TaskAnswerController(TaskAnswerRepository answerRepository, Access access) {
        this.answerRepository = answerRepository;
        this.access = access;
    }

    /** AI answers are written by the solve services; the frontend only reads them. */
    @GetMapping("/exams/{examId}/answers")
    public List<Dtos.AnswerDto> list(@PathVariable String examId, @CurrentUser String userId) {
        access.requireExam(Access.id(examId), userId);
        return answerRepository.findByExamId(Access.id(examId))
            .stream().map(Dtos.AnswerDto::from).toList();
    }
}
