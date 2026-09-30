package de.tum.cit.hestia.learninggoalhub.course;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseRepository extends JpaRepository<Course, Long> {

    /** Goal count per course for the given ids — one row per course that has at least one goal. */
    @Query("select g.course.id as courseId, count(g.id) as count "
            + "from LearningGoal g where g.course.id in :courseIds group by g.course.id")
    List<CourseCount> countGoalsByCourseIds(@Param("courseIds") Collection<Long> courseIds);

    /**
     * Topic (terminal-competency) count per course — one row per course that has at least one.
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
     * Mirrors the client's {@code buildCompetencyForest}: a direct CONTRIBUTES_TO child of a topic
     * is a capability when one of its own children has the SKILL role, or when an instructor added
     * it by hand with the SKILL role.
     */
    @Query("select t.course.id as courseId, count(distinct c.id) as count "
            + "from GoalRelationship r join r.source c join r.target t "
            + "where t.course.id in :courseIds "
            + "and t.origin = de.tum.cit.hestia.learninggoalhub.goal.GoalOrigin.TERMINAL "
            + "and r.type = de.tum.cit.hestia.learninggoalhub.relationships.RelationshipType.CONTRIBUTES_TO "
            + "and c.origin <> de.tum.cit.hestia.learninggoalhub.goal.GoalOrigin.GAP "
            + "and (exists (select 1 from GoalRelationship r2 where r2.target = c "
            + "and r2.type = de.tum.cit.hestia.learninggoalhub.relationships.RelationshipType.CONTRIBUTES_TO "
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
