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
interface AlignmentLinkRepository extends JpaRepository<CoreAlignmentLink, UUID> {

    Optional<CoreAlignmentLink> findByGoalIdAndTargetModuleAndTargetTypeAndTargetId(
            UUID goalId, DomainModule module, String targetType, String targetId);

    List<CoreAlignmentLink> findByTargetModuleAndTargetTypeAndTargetIdOrderByCreatedAtAscIdAsc(
            DomainModule module, String targetType, String targetId);

    List<CoreAlignmentLink> findByTargetModuleAndTargetTypeAndTargetIdInOrderByCreatedAtAscIdAsc(
            DomainModule module, String targetType, Collection<String> targetIds);

    List<CoreAlignmentLink> findByGoalIdOrderByCreatedAtAscIdAsc(UUID goalId);

    List<CoreAlignmentLink> findByCourseIdAndReviewStateOrderByCreatedAtAscIdAsc(UUID courseId, String reviewState);

    @Modifying
    @Query("delete from CoreAlignmentLink l where l.targetModule = :module and l.targetType = :targetType "
            + "and l.targetId = :targetId")
    void deleteTarget(DomainModule module, String targetType, String targetId);

    @Modifying
    @Query("delete from CoreAlignmentLink l where l.targetModule = :module and l.targetType = :targetType "
            + "and l.targetId = :targetId and l.courseId = :courseId")
    void deleteTargetInCourse(DomainModule module, String targetType, String targetId, UUID courseId);
}
