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
interface GoalRelationshipRepository extends JpaRepository<CoreGoalRelationship, UUID> {

    Optional<CoreGoalRelationship> findBySourceGoalIdAndTargetGoalIdAndType(UUID source, UUID target, String type);

    @Query("select r from CoreGoalRelationship r, CoreGoal g where g.id = r.sourceGoalId and g.courseId = :courseId "
            + "order by r.createdAt, r.id")
    List<CoreGoalRelationship> findByCourse(UUID courseId);

    @Modifying
    @Query("delete from CoreGoalRelationship r where r.sourceGoalId in :goalIds or r.targetGoalId in :goalIds")
    void deleteTouching(Collection<UUID> goalIds);
}
