package de.tum.cit.hestia.learninggoalhub.hierarchy;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HierarchyNodeRepository extends JpaRepository<HierarchyNode, Long> {

    List<HierarchyNode> findByCourseId(Long courseId);

    Optional<HierarchyNode> findFirstByCourseIdAndLevelOrderByIdAsc(Long courseId, HierarchyLevel level);

    boolean existsByCourseIdAndLevel(Long courseId, HierarchyLevel level);
}
