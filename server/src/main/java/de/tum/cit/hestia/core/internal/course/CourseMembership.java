package de.tum.cit.hestia.core.internal.course;

import de.tum.cit.hestia.core.CourseRole;
import de.tum.cit.hestia.core.OrgScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** A user's role in a course. */
@Entity
@Table(schema = "core", name = "course_membership")
@IdClass(CourseMembership.Key.class)
class CourseMembership extends OrgScoped {

    record Key(UUID courseId, UUID userId) implements Serializable {
        Key() {
            this(null, null);
        }
    }

    @Id
    @Column(name = "course_id")
    private UUID courseId;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CourseRole role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CourseMembership() {}

    CourseMembership(UUID courseId, UUID userId, CourseRole role, Instant now) {
        this.courseId = courseId;
        this.userId = userId;
        this.role = role;
        this.createdAt = now;
    }

    UUID getCourseId() {
        return courseId;
    }

    UUID getUserId() {
        return userId;
    }

    CourseRole getRole() {
        return role;
    }

    void setRole(CourseRole role) {
        this.role = role;
    }
}
