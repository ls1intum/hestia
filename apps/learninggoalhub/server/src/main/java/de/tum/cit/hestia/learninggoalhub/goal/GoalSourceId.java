package de.tum.cit.hestia.learninggoalhub.goal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class GoalSourceId implements Serializable {

    @Column(name = "goal_id")
    private Long goalId;

    @Column(name = "document_id")
    private Long documentId;

    protected GoalSourceId() {
    }

    public GoalSourceId(Long goalId, Long documentId) {
        this.goalId = goalId;
        this.documentId = documentId;
    }

    public Long getGoalId() {
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
