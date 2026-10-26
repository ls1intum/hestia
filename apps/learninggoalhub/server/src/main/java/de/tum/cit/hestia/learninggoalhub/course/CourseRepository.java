package de.tum.cit.hestia.learninggoalhub.course;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public interface CourseRepository extends JpaRepository<Course, Long> {

    /** The course, its row locked until the surrounding transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Course c where c.id = :id")
    Optional<Course> lockById(@Param("id") Long id);

    /** The course, or a 404 when there is none. */
    default Course findOrThrow(Long id) {
        return findById(id).orElseThrow(() -> notFound(id));
    }

    /**
     * The course with its row locked until the transaction ends, or a 404. Writes that check before
     * they insert (a duplicate topic, the next lecture order) take it first, so two concurrent
     * requests for one course cannot both pass the check.
     */
    default Course lockOrThrow(Long id) {
        return lockById(id).orElseThrow(() -> notFound(id));
    }

    /** Throws a 404 unless the course exists. */
    default void requireExists(Long id) {
        if (!existsById(id)) {
            throw notFound(id);
        }
    }

    private static ResponseStatusException notFound(Long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found: " + id);
    }

    // The count queries below name goal and document entities in JPQL strings, which the package
    // cycle test cannot see; they are the one place course depends on those packages.

    /** Goal count per course for the given ids — one row per course that has at least one goal. */
    @Query("select g.course.id as courseId, count(g.id) as count "
            + "from LearningGoal g where g.course.id in :courseIds group by g.course.id")
    List<CourseCount> countGoalsByCourseIds(@Param("courseIds") Collection<Long> courseIds);

    /**
     * Topic count per course — one row per course that has at least one.
     * Kept separate from the total goal count, which stays the count of every goal regardless of
     * origin because API consumers already depend on that meaning.
     */
    @Query("select g.course.id as courseId, count(g.id) as count "
            + "from LearningGoal g where g.course.id in :courseIds "
            + "and g.origin = de.tum.cit.hestia.learninggoalhub.goal.GoalOrigin.TERMINAL "
            + "group by g.course.id")
    List<CourseCount> countTopicsByCourseIds(@Param("courseIds") Collection<Long> courseIds);

    /**
     * Skill count per course (a capability in code terms) — one row per course that has at least one.
     * Mirrors the client's {@code buildTopicForest}: a direct CONTRIBUTES_TO child of a topic
     * is a capability when one of its own children has the SKILL role, or when an instructor added
     * it by hand with the SKILL role.
     */
    @Query("select t.course.id as courseId, count(distinct c.id) as count "
            + "from GoalRelationship r join r.source c join r.target t "
            + "where t.course.id in :courseIds "
            + "and t.origin = de.tum.cit.hestia.learninggoalhub.goal.GoalOrigin.TERMINAL "
            + "and r.type = de.tum.cit.hestia.learninggoalhub.goal.RelationshipType.CONTRIBUTES_TO "
            + "and c.origin <> de.tum.cit.hestia.learninggoalhub.goal.GoalOrigin.GAP "
            + "and (exists (select 1 from GoalRelationship r2 where r2.target = c "
            + "and r2.type = de.tum.cit.hestia.learninggoalhub.goal.RelationshipType.CONTRIBUTES_TO "
            + "and r2.source.role = de.tum.cit.hestia.learninggoalhub.goal.GoalRole.SKILL) "
            + "or (c.creationProvenance = de.tum.cit.hestia.learninggoalhub.goal.GoalCreationProvenance.USER_CREATED "
            + "and c.role = de.tum.cit.hestia.learninggoalhub.goal.GoalRole.SKILL)) "
            + "group by t.course.id")
    List<CourseCount> countSkillsByCourseIds(@Param("courseIds") Collection<Long> courseIds);

    /** Document count per course for the given ids — one row per course that has at least one document. */
    @Query("select d.course.id as courseId, count(d.id) as count "
            + "from Document d where d.course.id in :courseIds group by d.course.id")
    List<CourseCount> countDocumentsByCourseIds(@Param("courseIds") Collection<Long> courseIds);

    /** Projection for the grouped count queries above. */
    interface CourseCount {
        Long getCourseId();

        long getCount();
    }
}
