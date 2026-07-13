package de.tum.cit.hestia.learninggoalhub.goal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningGoalRepository extends JpaRepository<LearningGoal, Long> {

    List<LearningGoal> findByCourseId(Long courseId);

    List<LearningGoal> findByCourseIdAndOriginIn(Long courseId, Collection<GoalOrigin> origins);

    Optional<LearningGoal> findByIdAndCourseId(Long id, Long courseId);

    Page<LearningGoal> findByCourseId(Long courseId, Pageable pageable);

    Page<LearningGoal> findByCourseIdAndStatus(Long courseId, GoalStatus status, Pageable pageable);

    List<LearningGoal> findByCourseIdAndStatus(Long courseId, GoalStatus status);

    List<LearningGoal> findByCourseIdAndHierarchyNodeIsNotNull(Long courseId);
}
