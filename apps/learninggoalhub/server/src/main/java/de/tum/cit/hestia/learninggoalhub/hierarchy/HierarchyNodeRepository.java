package de.tum.cit.hestia.learninggoalhub.hierarchy;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HierarchyNodeRepository extends JpaRepository<HierarchyNode, Long> {

    List<HierarchyNode> findByCourseId(Long courseId);

    Optional<HierarchyNode> findFirstByCourseIdAndLevelOrderByIdAsc(Long courseId, HierarchyLevel level);

    boolean existsByCourseIdAndLevel(Long courseId, HierarchyLevel level);

    /** All terminal skills of a course share one COMPETENCY root node, created on first use. */
    default HierarchyNode findOrCreateCompetencyRoot(Course course) {
        return findFirstByCourseIdAndLevelOrderByIdAsc(course.getId(), HierarchyLevel.COMPETENCY)
                .orElseGet(() -> save(new HierarchyNode(course, null, HierarchyLevel.COMPETENCY,
                        HierarchyNode.COMPETENCY_ROOT_LABEL)));
    }
}
