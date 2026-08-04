package de.tum.cit.hestia.learninggoalhub.document;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;

/** A short, non-verbatim VLM description of a text-poor PDF page. */
@Entity
@Table(name = "page_description")
public class PageDescription {

    @EmbeddedId
    private PageDescriptionId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("documentId")
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(length = 255)
    private String model;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected PageDescription() {
    }

    public PageDescription(Document document, int page, String description, String model) {
        this.document = document;
        this.id = new PageDescriptionId(document.getId(), page);
        this.description = description;
        this.model = model;
    }

    public PageDescriptionId getId() {
        return id;
    }

    public Document getDocument() {
        return document;
    }

    public int getPage() {
        return id.getPage();
    }

    public String getDescription() {
        return description;
    }

    public String getModel() {
        return model;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
