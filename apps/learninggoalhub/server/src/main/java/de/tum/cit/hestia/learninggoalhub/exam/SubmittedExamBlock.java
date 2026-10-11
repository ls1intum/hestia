package de.tum.cit.hestia.learninggoalhub.exam;

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

/**
 * A stored block of an {@link ExamSubmission}, kept as the consumer sent it. A task block also
 * carries its 1-based number among the exam's tasks and the accumulated context the model saw with
 * it, so an exam goal can say where it came from without replaying the submission.
 */
@Entity
@Table(name = "exam_block")
public class SubmittedExamBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submission_id", nullable = false)
    private ExamSubmission submission;

    @Column(nullable = false)
    private int position;

    /** The consumer's identifier for the block; meaningless to an instructor. */
    @Column(name = "block_id", columnDefinition = "TEXT")
    private String blockId;

    @Enumerated(EnumType.STRING)
    @Column(name = "block_type", nullable = false, length = 16)
    private ExamBlockType blockType;

    @Column(name = "task_type", columnDefinition = "TEXT")
    private String taskType;

    /** 1-based position among the submission's task blocks; null for a context block. */
    @Column(name = "task_number")
    private Integer taskNumber;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** The context blocks preceding a task, joined as the model received them; null when none. */
    @Column(columnDefinition = "TEXT")
    private String context;

    protected SubmittedExamBlock() {
    }

    SubmittedExamBlock(ExamSubmission submission, int position, ExamBlock block, Integer taskNumber,
                       String context) {
        this.submission = submission;
        this.position = position;
        this.blockId = block.blockId();
        this.blockType = block.blockType();
        this.taskType = block.taskType();
        this.taskNumber = taskNumber;
        this.description = block.description();
        this.context = context;
    }

    public Long getId() {
        return id;
    }

    public ExamSubmission getSubmission() {
        return submission;
    }

    public int getPosition() {
        return position;
    }

    public String getBlockId() {
        return blockId;
    }

    public ExamBlockType getBlockType() {
        return blockType;
    }

    public String getTaskType() {
        return taskType;
    }

    public Integer getTaskNumber() {
        return taskNumber;
    }

    public String getDescription() {
        return description;
    }

    public String getContext() {
        return context;
    }
}
