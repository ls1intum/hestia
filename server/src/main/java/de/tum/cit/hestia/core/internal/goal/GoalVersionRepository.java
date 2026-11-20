package de.tum.cit.hestia.core.internal.goal;

import de.tum.cit.hestia.core.DomainModule;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Tenant-filtered repository of the goal tables (current organisation only). */
interface GoalVersionRepository extends JpaRepository<CoreGoalVersion, CoreGoalVersion.Key> {

    @Query("select v from CoreGoalVersion v where v.key.goalId = :goalId order by v.key.version")
    List<CoreGoalVersion> history(UUID goalId);

    /** The versions numbered 1 and {@code current_version} of the given goals. */
    @Query("select v from CoreGoalVersion v, CoreGoal g where g.id = v.key.goalId and g.id in :goalIds "
            + "and (v.key.version = 1 or v.key.version = g.currentVersion)")
    List<CoreGoalVersion> firstAndCurrent(Collection<UUID> goalIds);
}
