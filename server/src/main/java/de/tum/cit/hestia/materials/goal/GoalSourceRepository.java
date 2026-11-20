package de.tum.cit.hestia.materials.goal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoalSourceRepository extends JpaRepository<GoalSource, GoalSourceId> {

    List<GoalSource> findByIdGoalId(UUID goalId);

    List<GoalSource> findByIdGoalIdIn(Collection<UUID> goalIds);

    void deleteByIdGoalIdIn(Collection<UUID> goalIds);
}
