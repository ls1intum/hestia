package de.tum.cit.hestia.learninggoalhub.relationships;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GoalRelationshipRepository extends JpaRepository<GoalRelationship, Long> {

    List<GoalRelationship> findBySourceId(Long sourceGoalId);

    List<GoalRelationship> findBySourceIdIn(Collection<Long> sourceGoalIds);

    /**
     * Loads relationships for the given source goals with their target goal fetched eagerly,
     * so callers can read {@code target.id}/{@code target.text} without triggering N+1 queries.
     */
    @Query("select r from GoalRelationship r join fetch r.target where r.source.id in :sourceIds")
    List<GoalRelationship> findBySourceIdInWithTarget(@Param("sourceIds") Collection<Long> sourceIds);

    boolean existsBySourceIdAndTargetIdAndType(Long sourceId, Long targetId, RelationshipType type);
}
