package de.tum.cit.hestia.learninggoalhub.goal;

import de.tum.cit.hestia.learninggoalhub.document.Document;
import de.tum.cit.hestia.learninggoalhub.extraction.SourceMatchQuality;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    @Column(nullable = false)
    private boolean grounded;

    @Enumerated(EnumType.STRING)
    @Column(name = "grounding_quality", length = 32)
    private SourceMatchQuality groundingQuality;

    protected GoalSource() {
    }

    public GoalSource(LearningGoal goal, Document document, String snippet) {
        this(goal, document, snippet, null, false);
    }

    public GoalSource(LearningGoal goal, Document document, String snippet, Integer page) {
        this(goal, document, snippet, page, false);
    }

    public GoalSource(LearningGoal goal, Document document, String snippet, Integer page, boolean grounded) {
        this(goal, document, snippet, page, grounded, null);
    }

    public GoalSource(LearningGoal goal, Document document, String snippet, Integer page,
                      SourceMatchQuality groundingQuality) {
        this(goal, document, snippet, page,
                groundingQuality != null && groundingQuality != SourceMatchQuality.NONE, groundingQuality);
    }

    private GoalSource(LearningGoal goal, Document document, String snippet, Integer page,
                       boolean grounded, SourceMatchQuality groundingQuality) {
        this.goal = goal;
        this.document = document;
        this.id = new GoalSourceId(goal.getId(), document.getId());
        this.snippet = snippet;
        this.page = page;
        this.grounded = grounded;
        this.groundingQuality = groundingQuality;
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

    public boolean isGrounded() {
        return grounded;
    }

    public SourceMatchQuality getGroundingQuality() {
        return groundingQuality;
    }
}
