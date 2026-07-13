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
