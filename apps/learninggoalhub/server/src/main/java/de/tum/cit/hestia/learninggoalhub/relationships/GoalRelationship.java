package de.tum.cit.hestia.learninggoalhub.relationships;

import de.tum.cit.hestia.learninggoalhub.goal.LearningGoal;
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
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(
    name = "goal_relationship",
    uniqueConstraints = @UniqueConstraint(
        name = "goal_relationship_unique",
        columnNames = {"source_goal_id", "target_goal_id", "type"}
    )
)
public class GoalRelationship {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_goal_id", nullable = false)
    private LearningGoal source;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_goal_id", nullable = false)
    private LearningGoal target;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RelationshipType type;

    @Column(nullable = false)
    private double confidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RelationshipOrigin origin;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected GoalRelationship() {
    }

    public GoalRelationship(LearningGoal source, LearningGoal target, RelationshipType type,
                            double confidence, RelationshipOrigin origin) {
        this.source = source;
        this.target = target;
        this.type = type;
        this.confidence = confidence;
        this.origin = origin;
    }

    public Long getId() {
        return id;
    }

    public LearningGoal getSource() {
        return source;
    }

    public LearningGoal getTarget() {
        return target;
    }

    public RelationshipType getType() {
        return type;
    }

    public double getConfidence() {
        return confidence;
    }

    public RelationshipOrigin getOrigin() {
        return origin;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
