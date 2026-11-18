package de.tum.cit.hestia.core.internal.course;

import de.tum.cit.hestia.core.OrgScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** {@code core.course}: the course every module's objects hang off. */
@Entity
@Table(schema = "core", name = "course")
class CoreCourse extends OrgScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private boolean personal;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CoreCourse() {}

    CoreCourse(String title, UUID createdBy, Instant now) {
        this.title = title;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    String getTitle() {
        return title;
    }

    boolean isPersonal() {
        return personal;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    void rename(String title, Instant now) {
        this.title = title;
        this.updatedAt = now;
    }
}
