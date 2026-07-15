package app.section;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity
@Table(name = "section_figures")
public class SectionFigure {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "block_id", nullable = false)
    private UUID blockId;

    @Column(nullable = false)
    private int position;

    @Column(name = "storage_path", nullable = false)
    private String storagePath;

    @Column(nullable = false)
    private String source = "upload";

    private String caption;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getBlockId() { return blockId; }
    public void setBlockId(UUID blockId) { this.blockId = blockId; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getCaption() { return caption; }
    public void setCaption(String caption) { this.caption = caption; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
