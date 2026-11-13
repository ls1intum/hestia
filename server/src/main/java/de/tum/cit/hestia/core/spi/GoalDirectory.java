package de.tum.cit.hestia.core.spi;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Read and maintain the courses and learning goals of the materials module, for the other domain
 * modules. Implemented by {@code materials}; replaces the HTTP calls to LearningGoalHub's
 * {@code /api/courses/**} endpoints. Methods that take a course id throw
 * {@link NoSuchElementException} when the course does not exist.
 */
public interface GoalDirectory {

    /** One page of courses, ordered as LearningGoalHub lists them. */
    Page<Course> listCourses(int page, int size);

    /** Creates an empty course (no documents) and returns it. */
    Course createCourse(String name);

    /** A course's goals grouped by hierarchy node (module, session, exercise); ungrouped last. */
    List<SessionGoals> goalsBySession(long courseId);

    /** All goals of a course, any review status. */
    List<Goal> goals(long courseId);

    /** One goal of a course; {@link NoSuchElementException} if the course or goal does not exist. */
    Goal goal(long courseId, long goalId);

    /** Deletes a goal of a course. A goal that is already gone is not an error. */
    void deleteGoal(long courseId, long goalId);

    record Course(long id, String name) {}

    record Page<T>(List<T> content, int number, int size, long totalElements, int totalPages) {}

    /** A learning goal. Enum values are passed as their names. */
    record Goal(long id, String text, String kind, String role, String origin, String status,
                String bloomLevel, String soloLevel, Integer lectureOrder, Hierarchy hierarchy,
                List<Relationship> relationships) {}

    /** Labels of the hierarchy nodes a goal sits under; null where a level is missing. */
    record Hierarchy(String module, String session, String exercise) {}

    record Relationship(String type, long targetGoalId, String targetText, double confidence, String origin) {}

    /** One hierarchy node and its goals; all node fields null for the ungrouped goals. */
    record SessionGoals(Long nodeId, String level, String label, List<Goal> goals) {}
}
