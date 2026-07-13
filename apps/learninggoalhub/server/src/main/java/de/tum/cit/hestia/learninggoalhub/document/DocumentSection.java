package de.tum.cit.hestia.learninggoalhub.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One structural learning unit of a {@link Document}, detected deterministically at upload time
 * (from PDF bookmarks). It is a half-open character range {@code [startOffset, endOffset)} into the
 * document's {@code rawText}; the extraction step materializes each section as one SESSION hierarchy
 * node and routes the chunks falling in its range to it. A document with no detectable structure has
 * no sections and is treated as a single session.
 */
@Entity
@Table(name = "document_section")
public class DocumentSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    /** Position of this section among its document's sections, in document order (0-based). */
    @Column(nullable = false)
    private int ordinal;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(name = "start_offset", nullable = false)
    private int startOffset;

    @Column(name = "end_offset", nullable = false)
    private int endOffset;

    protected DocumentSection() {
    }

    public DocumentSection(Document document, int ordinal, String title, int startOffset, int endOffset) {
        this.document = document;
        this.ordinal = ordinal;
        this.title = title;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
    }

    public Long getId() {
        return id;
    }

    public Document getDocument() {
        return document;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public String getTitle() {
        return title;
    }

    public int getStartOffset() {
        return startOffset;
    }

    public int getEndOffset() {
        return endOffset;
    }
}
