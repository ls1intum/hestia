package de.tum.cit.hestia.materials.exam;

import de.tum.cit.hestia.core.spi.ExamGoalDerivation;
import de.tum.cit.hestia.materials.course.Course;
import de.tum.cit.hestia.core.CourseNotFoundException;
import de.tum.cit.hestia.materials.course.CourseGuard;
import de.tum.cit.hestia.materials.exam.submission.ExamBlock;
import de.tum.cit.hestia.materials.exam.submission.ExamBlockType;
import de.tum.cit.hestia.materials.goal.GoalDirectoryService;
import de.tum.cit.hestia.materials.goal.LearningGoalResponse;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The {@link ExamGoalDerivation} port: the same derivation as
 * {@code POST /api/courses/{id}/exam-tasks/learning-goals}, with the same checks on the exam.
 */
@Service
public class ExamGoalDerivationService implements ExamGoalDerivation {

    private final CourseGuard guard;
    private final ExamGoalService examGoalService;

    public ExamGoalDerivationService(CourseGuard guard, ExamGoalService examGoalService) {
        this.guard = guard;
        this.examGoalService = examGoalService;
    }

    @Override
    public List<TaskGoals> derive(java.util.UUID courseId, List<ExamTask> tasks) {
        // TODO(package 6): store the derived goals in core through LearningGoalApi.
        throw new UnsupportedOperationException("Exam goal derivation is being moved to core goals");
    }

    private static ExamBlock toBlock(ExamTask task) {
        if (task == null) {
            return null;
        }
        return new ExamBlock(task.blockId(),
                task.blockType() == null ? null : ExamBlockType.parse(task.blockType()),
                task.taskType(), task.description());
    }
}
