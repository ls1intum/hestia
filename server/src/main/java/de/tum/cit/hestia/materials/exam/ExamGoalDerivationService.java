package de.tum.cit.hestia.materials.exam;

import de.tum.cit.hestia.core.spi.ExamGoalDerivation;
import de.tum.cit.hestia.materials.course.Course;
import de.tum.cit.hestia.materials.course.CourseRepository;
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

    private final CourseRepository courseRepository;
    private final ExamGoalService examGoalService;

    public ExamGoalDerivationService(CourseRepository courseRepository, ExamGoalService examGoalService) {
        this.courseRepository = courseRepository;
        this.examGoalService = examGoalService;
    }

    @Override
    public List<TaskGoals> derive(long courseId, List<ExamTask> tasks) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new NoSuchElementException("Course not found: " + courseId));
        List<ExamBlock> blocks = tasks == null ? null : tasks.stream().map(ExamGoalDerivationService::toBlock).toList();
        try {
            ExamGoalController.validate(new ExamGoalController.GenerateExamGoalsRequest(blocks));
        } catch (ResponseStatusException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.BAD_REQUEST)) {
                throw new IllegalArgumentException(e.getReason(), e);
            }
            throw e;
        }
        return examGoalService.generateForBlocks(course, blocks, null).stream()
                .map(taskGoals -> new TaskGoals(
                        taskGoals.blockId(),
                        taskGoals.goals().stream()
                                .map(g -> GoalDirectoryService.toGoal(LearningGoalResponse.from(g, List.of(), List.of())))
                                .toList()))
                .toList();
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
