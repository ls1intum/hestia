package de.tum.cit.hestia.materials.goal;

import de.tum.cit.hestia.core.spi.GoalDirectory;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Placeholder for the deprecated {@link GoalDirectory} port until the modules that still inject it
 * use {@code CourseApi} and {@code LearningGoalApi}; every method fails.
 */
@Service
@SuppressWarnings("removal")
public class GoalDirectoryService implements GoalDirectory {

    private static UnsupportedOperationException gone() {
        return new UnsupportedOperationException("GoalDirectory is replaced by CourseApi and LearningGoalApi");
    }

    @Override
    public Page<Course> listCourses(int page, int size) {
        throw gone();
    }

    @Override
    public Course createCourse(String name) {
        throw gone();
    }

    @Override
    public List<SessionGoals> goalsBySession(long courseId) {
        throw gone();
    }

    @Override
    public List<Goal> goals(long courseId) {
        throw gone();
    }

    @Override
    public Goal goal(long courseId, long goalId) {
        throw gone();
    }

    @Override
    public void deleteGoal(long courseId, long goalId) {
        throw gone();
    }
}
