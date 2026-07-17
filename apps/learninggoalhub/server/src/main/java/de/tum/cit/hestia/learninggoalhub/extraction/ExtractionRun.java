package de.tum.cit.hestia.learninggoalhub.extraction;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** Audit record for one extraction pipeline run. */
@Entity
@Table(name = "extraction_run")
public class ExtractionRun {

    public enum Status {
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column
    private String model;

    @Column(name = "prompt_version", nullable = false, length = 32)
    private String promptVersion;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String params;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "goals_created")
    private Integer goalsCreated;

    protected ExtractionRun() {
    }

    public ExtractionRun(Course course, String model, String promptVersion, String params) {
        this.course = course;
        this.model = model;
        this.promptVersion = promptVersion;
        this.params = params;
        this.status = Status.RUNNING;
        this.startedAt = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Course getCourse() {
        return course;
    }

    public String getModel() {
        return model;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getParams() {
        return params;
    }

    public Status getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    public Integer getGoalsCreated() {
        return goalsCreated;
    }

    public void finish(Status status, String error, Integer goalsCreated, String promptVersion) {
        this.status = status;
        this.error = error;
        this.goalsCreated = goalsCreated;
        this.promptVersion = promptVersion;
        this.finishedAt = OffsetDateTime.now();
    }
}
