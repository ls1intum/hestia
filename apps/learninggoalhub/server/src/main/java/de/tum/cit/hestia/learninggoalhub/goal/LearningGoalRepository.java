package de.tum.cit.hestia.learninggoalhub.goal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public interface LearningGoalRepository extends JpaRepository<LearningGoal, Long> {

    List<LearningGoal> findByCourseId(Long courseId);

    List<LearningGoal> findByCourseIdAndOriginIn(Long courseId, Collection<GoalOrigin> origins);

    Optional<LearningGoal> findByIdAndCourseId(Long id, Long courseId);

    Page<LearningGoal> findByCourseId(Long courseId, Pageable pageable);

    Page<LearningGoal> findByCourseIdAndStatus(Long courseId, GoalStatus status, Pageable pageable);

    Page<LearningGoal> findByCourseIdAndRole(Long courseId, GoalRole role, Pageable pageable);

    Page<LearningGoal> findByCourseIdAndStatusAndRole(Long courseId, GoalStatus status, GoalRole role,
                                                       Pageable pageable);

    List<LearningGoal> findByCourseIdAndStatus(Long courseId, GoalStatus status);

    List<LearningGoal> findByCourseIdAndRole(Long courseId, GoalRole role);

    List<LearningGoal> findByCourseIdAndStatusAndRole(Long courseId, GoalStatus status, GoalRole role);

    List<LearningGoal> findByCourseIdAndHierarchyNodeIsNotNull(Long courseId);

    /** Throws a 409 when the course already has a terminal skill with this text, ignoring case. */
    default void rejectDuplicateTopic(Long courseId, String text) {
        boolean duplicate = findByCourseIdAndOriginIn(courseId, List.of(GoalOrigin.TERMINAL)).stream()
                .anyMatch(goal -> goal.getText() != null && goal.getText().strip().equalsIgnoreCase(text));
        if (duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A terminal skill with this text already exists");
        }
    }
}
