package de.tum.cit.hestia.materials.intake;

import de.tum.cit.hestia.core.OrgScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One uploaded file of a submission, bytes included. Listings read {@link IntakeFileInfo} through
 * the repository so they never load the bytes.
 */
@Entity
@Table(schema = "materials", name = "intake_file")
public class IntakeFile extends OrgScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "submission_id", nullable = false)
    private UUID submissionId;

    @Column(name = "original_name", nullable = false)
    private String originalName;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "sha256", nullable = false)
    private String sha256;

    @Column(name = "bytes", nullable = false, columnDefinition = "BYTEA")
    private byte[] bytes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IntakeFile() {
    }

    IntakeFile(UUID submissionId, String originalName, String contentType, byte[] bytes, String sha256,
               Instant createdAt) {
        this.submissionId = submissionId;
        this.originalName = originalName;
        this.contentType = contentType;
        this.sizeBytes = bytes.length;
        this.sha256 = sha256;
        this.bytes = bytes;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSubmissionId() {
        return submissionId;
    }

    public String getOriginalName() {
        return originalName;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public byte[] getBytes() {
        return bytes;
    }
}
