package de.tum.cit.hestia.materials.course;

import de.tum.cit.hestia.core.CourseAccess;
import org.springframework.stereotype.Component;

/**
 * Course roles for the materials module's courses. A materials course belongs to a {@code core.course}
 * (its {@code coreCourseId}), on which the roles live; this resolves the materials id and asks
 * {@link CourseAccess}. A course of another organisation is invisible (Hibernate's tenant filter),
 * a course the user is no member of answers as if it did not exist: both are a 404, a role that is
 * too small is a 403.
 */
@Component
public class CourseGuard {

    private final CourseRepository courseRepository;
    private final CourseAccess access;

    public CourseGuard(CourseRepository courseRepository, CourseAccess access) {
        this.courseRepository = courseRepository;
        this.access = access;
    }

    /** The course for a reader (any member), or a 404. */
    public Course requireRead(Long courseId) {
        Course course = courseRepository.findOrThrow(courseId);
        access.requireRead(course.getCoreCourseId());
        return course;
    }

    /** The course for someone who changes its content or starts model calls (editor or owner), or a 404 / 403. */
    public Course requireWrite(Long courseId) {
        Course course = courseRepository.findOrThrow(courseId);
        access.requireWrite(course.getCoreCourseId());
        return course;
    }

    /** The course for its owner, or a 404 / 403. */
    public Course requireOwner(Long courseId) {
        Course course = courseRepository.findOrThrow(courseId);
        access.requireOwner(course.getCoreCourseId());
        return course;
    }

    /** Whether the user is a member of the course; false for an unknown course. */
    public boolean canRead(Long courseId) {
        return courseRepository.findById(courseId).map(c -> access.canRead(c.getCoreCourseId())).orElse(false);
    }
}
