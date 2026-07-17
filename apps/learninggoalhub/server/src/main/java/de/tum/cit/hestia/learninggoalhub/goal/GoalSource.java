package de.tum.cit.hestia.learninggoalhub.goal;

import de.tum.cit.hestia.learninggoalhub.document.Document;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

@Entity
@Table(name = "goal_source")
public class GoalSource {

    @EmbeddedId
    private GoalSourceId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("goalId")
    @JoinColumn(name = "goal_id")
    private LearningGoal goal;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("documentId")
    @JoinColumn(name = "document_id")
    private Document document;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String snippet;

    @Column
    private Integer page;

    protected GoalSource() {
    }

    public GoalSource(LearningGoal goal, Document document, String snippet) {
        this(goal, document, snippet, null);
    }

    public GoalSource(LearningGoal goal, Document document, String snippet, Integer page) {
        this.goal = goal;
        this.document = document;
        this.id = new GoalSourceId(goal.getId(), document.getId());
        this.snippet = snippet;
        this.page = page;
    }

    public GoalSourceId getId() {
        return id;
    }

    public LearningGoal getGoal() {
        return goal;
    }

    public Document getDocument() {
        return document;
    }

    public String getSnippet() {
        return snippet;
    }

    public Integer getPage() {
        return page;
    }
}
