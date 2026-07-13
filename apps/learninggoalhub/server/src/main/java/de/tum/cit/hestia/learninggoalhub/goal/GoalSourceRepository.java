package de.tum.cit.hestia.learninggoalhub.goal;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoalSourceRepository extends JpaRepository<GoalSource, GoalSourceId> {

    List<GoalSource> findByGoalId(Long goalId);

    List<GoalSource> findByGoalIdIn(Collection<Long> goalIds);
}
