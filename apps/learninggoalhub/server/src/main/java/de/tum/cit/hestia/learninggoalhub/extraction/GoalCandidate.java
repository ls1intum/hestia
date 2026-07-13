package de.tum.cit.hestia.learninggoalhub.extraction;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.goal.GoalKind;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoal;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNode;
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
import org.hibernate.annotations.CreationTimestamp;

/**
 * One fine-grained learning goal as extracted from a single chunk by the first stage
 * ({@link ExtractionService}). The second stage ({@link SessionGoalConsolidator}) merges a session's
 * candidates into the few broad {@link LearningGoal}s that actually get surfaced; candidates are
 * persisted purely for traceability — {@link #consolidatedGoal} records which consolidated goal a
 * candidate was merged into (null when it was dropped or the goal not yet persisted).
 */
@Entity
@Table(name = "goal_candidate")
public class GoalCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hierarchy_node_id", nullable = false)
    private HierarchyNode hierarchyNode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consolidated_goal_id")
    private LearningGoal consolidatedGoal;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GoalKind kind;

    @Column(name = "source_snippet", columnDefinition = "TEXT")
    private String sourceSnippet;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected GoalCandidate() {
    }

    public GoalCandidate(Course course, HierarchyNode hierarchyNode, String text, GoalKind kind,
                         String sourceSnippet) {
        this.course = course;
        this.hierarchyNode = hierarchyNode;
        this.text = text;
        this.kind = kind;
        this.sourceSnippet = sourceSnippet;
    }

    public Long getId() {
        return id;
    }

    public Course getCourse() {
        return course;
    }

    public HierarchyNode getHierarchyNode() {
        return hierarchyNode;
    }

    public LearningGoal getConsolidatedGoal() {
        return consolidatedGoal;
    }

    public void setConsolidatedGoal(LearningGoal consolidatedGoal) {
        this.consolidatedGoal = consolidatedGoal;
    }

    public String getText() {
        return text;
    }

    public GoalKind getKind() {
        return kind;
    }

    public String getSourceSnippet() {
        return sourceSnippet;
    }
}
