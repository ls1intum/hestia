package app.grading;

import app.error.ApiException;
import app.examination.Examination;
import app.shared.Access;
import app.taskblock.AIAnswer;
import app.taskblock.AIAnswerRepository;
import app.taskblock.TaskBlock;
import app.taskblock.TaskBlockRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GradeService {

    private final GradeRepository grades;
    private final AIAnswerRepository answers;
    private final TaskBlockRepository tasks;
    private final Access access;

    public GradeService(GradeRepository grades, AIAnswerRepository answers,
                        TaskBlockRepository tasks, Access access) {
        this.grades = grades;
        this.answers = answers;
        this.tasks = tasks;
        this.access = access;
    }

    /** Upsert the grade for the task's current answer under the same lock used by answer replacement. */
    @Transactional
    public Grade upsert(String rawTaskId, BigDecimal score, boolean autoGraded, String userId) {
        UUID taskId = Access.id(rawTaskId);
        TaskBlock task = tasks.findByIdForUpdate(taskId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Task not found"));
        Examination exam = access.requireExamination(task.getExamId(), userId);
        if (!"grading".equals(exam.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Grades can only be changed while the exam is being graded.");
        }

        AIAnswer answer = answers.findByTaskId(taskId)
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                "The task has no AI answer to grade."));
        Grade grade = grades.findByAnswerId(answer.getId()).orElseGet(Grade::new);
        grade.setAnswer(answer);
        grade.setScore(score);
        grade.setAutoGraded(autoGraded);
        grade.setGradedBy(UUID.fromString(userId));
        return grades.save(grade);
    }
}
