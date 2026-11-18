package de.tum.cit.hestia.teaching.model;

import de.tum.cit.hestia.core.OrgScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(schema = "teaching", name = "slide_templates")
public class SlideTemplateEntity extends OrgScoped {

    @Id
    private String id;

    /** The user who uploaded the template; it is deleted with the user. */
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "file_data", nullable = false)
    private byte[] fileData;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public byte[] getFileData() { return fileData; }
    public void setFileData(byte[] fileData) { this.fileData = fileData; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
