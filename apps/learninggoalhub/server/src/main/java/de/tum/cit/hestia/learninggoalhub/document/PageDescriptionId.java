package de.tum.cit.hestia.learninggoalhub.document;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class PageDescriptionId implements Serializable {

    @Column(name = "document_id")
    private Long documentId;

    @Column(name = "page")
    private Integer page;

    protected PageDescriptionId() {
    }

    public PageDescriptionId(Long documentId, Integer page) {
        this.documentId = documentId;
        this.page = page;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public Integer getPage() {
        return page;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PageDescriptionId other)) return false;
        return Objects.equals(documentId, other.documentId) && Objects.equals(page, other.page);
    }

    @Override
    public int hashCode() {
        return Objects.hash(documentId, page);
    }
}
