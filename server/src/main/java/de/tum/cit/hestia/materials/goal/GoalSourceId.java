package de.tum.cit.hestia.materials.goal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class GoalSourceId implements Serializable {

    @Column(name = "goal_id")
    private UUID goalId;

    @Column(name = "document_id")
    private Long documentId;

    protected GoalSourceId() {
    }

    public GoalSourceId(UUID goalId, Long documentId) {
        this.goalId = goalId;
        this.documentId = documentId;
    }

    public UUID getGoalId() {
        return goalId;
    }

    public Long getDocumentId() {
        return documentId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GoalSourceId other)) return false;
        return Objects.equals(goalId, other.goalId) && Objects.equals(documentId, other.documentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(goalId, documentId);
    }
}
