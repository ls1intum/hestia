package de.tum.cit.hestia.learninggoalhub.goal;

import de.tum.cit.hestia.learninggoalhub.document.Document;
import de.tum.cit.hestia.learninggoalhub.document.HighlightRect;
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
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    @Column(name = "unverified_snippet", columnDefinition = "TEXT")
    private String unverifiedSnippet;

    @Column
    private Integer page;

    @Column(nullable = false)
    private boolean grounded;

    @Enumerated(EnumType.STRING)
    @Column(name = "grounding_quality", length = 32)
    private SourceMatchQuality groundingQuality;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "highlight_rects", columnDefinition = "jsonb")
    private List<HighlightRect> highlightRects;

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

    /**
     * A snippet that could not be located is not evidence — the model composes such quotes out of
     * passages that merely look contiguous on the slide. The document link and the quality stay, but
     * the text is never presented as a quote and the unit's page is dropped rather than suggesting a
     * find; the rejected text is kept only so extraction quality remains measurable.
     */
    public GoalSource(LearningGoal goal, Document document, String snippet, Integer page,
                      SourceMatchQuality groundingQuality) {
        this(goal, document,
                groundingQuality == SourceMatchQuality.NONE ? "" : snippet,
                groundingQuality == SourceMatchQuality.NONE ? null : page,
                groundingQuality != null && groundingQuality != SourceMatchQuality.NONE, groundingQuality);
        if (groundingQuality == SourceMatchQuality.NONE && snippet != null && !snippet.isBlank()) {
            this.unverifiedSnippet = snippet;
        }
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

    public String getUnverifiedSnippet() {
        return unverifiedSnippet;
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

    public List<HighlightRect> getHighlightRects() {
        return highlightRects;
    }

    public void setHighlightRects(List<HighlightRect> highlightRects) {
        this.highlightRects = highlightRects;
    }
}
