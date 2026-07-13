package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoalCandidateRepository extends JpaRepository<GoalCandidate, Long> {

    List<GoalCandidate> findByCourseId(Long courseId);
}
