package de.tum.cit.hestia.materials.hierarchy;

import java.util.UUID;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HierarchyNodeRepository extends JpaRepository<HierarchyNode, Long> {

    List<HierarchyNode> findByCourseId(UUID courseId);

    Optional<HierarchyNode> findFirstByCourseIdAndLevelOrderByIdAsc(UUID courseId, HierarchyLevel level);

    boolean existsByCourseIdAndLevel(UUID courseId, HierarchyLevel level);

    /** All terminal skills of a course share one COMPETENCY root node, created on first use. */
    default HierarchyNode findOrCreateCompetencyRoot(UUID courseId) {
        return findFirstByCourseIdAndLevelOrderByIdAsc(courseId, HierarchyLevel.COMPETENCY)
                .orElseGet(() -> save(new HierarchyNode(courseId, null, HierarchyLevel.COMPETENCY,
                        HierarchyNode.COMPETENCY_ROOT_LABEL)));
    }
}
