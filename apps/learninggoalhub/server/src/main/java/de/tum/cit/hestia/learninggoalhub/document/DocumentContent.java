package de.tum.cit.hestia.learninggoalhub.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * The original upload bytes, kept out of the {@link Document} entity (no association) so routine
 * document queries never touch the potentially large content. The document id doubles as the
 * primary key; implementing {@link Persistable} makes save() insert directly instead of merging,
 * which the pre-assigned id would otherwise trigger.
 */
@Entity
@Table(name = "document_content")
public class DocumentContent implements Persistable<Long> {

    @Id
    @Column(name = "document_id")
    private Long documentId;

    @Column(name = "bytes", nullable = false, columnDefinition = "BYTEA")
    private byte[] bytes;

    @Transient
    private boolean isNew = true;

    protected DocumentContent() {
    }

    public DocumentContent(Document document, byte[] bytes) {
        this.documentId = document.getId();
        this.bytes = bytes;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public byte[] getBytes() {
        return bytes;
    }

    @Override
    public Long getId() {
        return documentId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        this.isNew = false;
    }
}
