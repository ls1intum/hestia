package de.tum.cit.hestia.learninggoalhub.exam;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.CreationTimestamp;

/**
 * One exam as a consumer submitted it to the exam-goal endpoint: its blocks in exam order. The
 * consumer sends no exam identity, so each request is its own submission, told apart by when it
 * arrived.
 */
@Entity
@Table(name = "exam_submission")
public class ExamSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @OneToMany(mappedBy = "submission", cascade = CascadeType.PERSIST)
    @OrderBy("position")
    private List<SubmittedExamBlock> blocks = new ArrayList<>();

    protected ExamSubmission() {
    }

    public ExamSubmission(Course course) {
        this.course = course;
    }

    /** Appends a block in exam order; it is persisted with the submission. */
    public SubmittedExamBlock addBlock(ExamBlock block, Integer taskNumber, String context) {
        SubmittedExamBlock stored = new SubmittedExamBlock(this, blocks.size(), block, taskNumber, context);
        blocks.add(stored);
        return stored;
    }

    public Long getId() {
        return id;
    }

    public Course getCourse() {
        return course;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public List<SubmittedExamBlock> getBlocks() {
        return blocks;
    }
}
