package de.tum.cit.hestia.materials.goal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GoalPlacementRepository extends JpaRepository<GoalPlacement, UUID> {

    /** The placements of the goals, with their hierarchy node fetched. */
    @Query("select p from GoalPlacement p left join fetch p.hierarchyNode where p.goalId in :goalIds")
    List<GoalPlacement> findByGoalIdIn(@Param("goalIds") Collection<UUID> goalIds);

    void deleteByGoalIdIn(Collection<UUID> goalIds);
}
