package de.tum.cit.hestia.materials.exam;

import de.tum.cit.hestia.core.CourseAccess;
import de.tum.cit.hestia.core.DomainModule;
import de.tum.cit.hestia.core.spi.ExamGoalDerivation;
import de.tum.cit.hestia.materials.course.CourseContext;
import de.tum.cit.hestia.materials.course.MaterialSettingsService;
import de.tum.cit.hestia.materials.exam.submission.ExamBlock;
import de.tum.cit.hestia.materials.exam.submission.ExamBlockType;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The {@link ExamGoalDerivation} port: the same derivation as
 * {@code POST /api/courses/{id}/exam-tasks/learning-goals}, with the same checks on the exam. The
 * goals are stored in the given course of {@code core} as {@code EXAM} goals created via
 * {@code ASSESSMENT}; the caller needs EDITOR on the course. The exam is stored as a materials exam
 * submission, so each goal knows the task it came from.
 */
@Service
public class ExamGoalDerivationService implements ExamGoalDerivation {

    private final CourseAccess courseAccess;
    private final MaterialSettingsService settingsService;
    private final ExamGoalService examGoalService;

    public ExamGoalDerivationService(CourseAccess courseAccess, MaterialSettingsService settingsService,
                                     ExamGoalService examGoalService) {
        this.courseAccess = courseAccess;
        this.settingsService = settingsService;
        this.examGoalService = examGoalService;
    }

    @Override
    public List<TaskGoals> derive(UUID courseId, List<ExamTask> tasks) {
        // 404 without a membership, 403 below EDITOR: checked before any model call is made.
        courseAccess.requireWrite(courseId);
        CourseContext course = settingsService.context(courseId);
        List<ExamBlock> blocks = tasks == null ? List.of() : tasks.stream().map(ExamGoalDerivationService::toBlock).toList();
        ExamGoalController.validate(new ExamGoalController.GenerateExamGoalsRequest(blocks));
        return examGoalService.generateForBlocks(course, blocks, null, DomainModule.ASSESSMENT).stream()
                .map(task -> new TaskGoals(task.blockId(), task.goals().stream().map(goal -> goal.info()).toList()))
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
