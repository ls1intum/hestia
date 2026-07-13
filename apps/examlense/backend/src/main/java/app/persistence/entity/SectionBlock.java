package app.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity
@Table(name = "section_blocks")
public class SectionBlock {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "section_id", nullable = false)
    private UUID sectionId;

    @Column(name = "exam_id", nullable = false)
    private UUID examId;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private String content = "";

    @Column(nullable = false)
    private String kind = "context";

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSectionId() { return sectionId; }
    public void setSectionId(UUID sectionId) { this.sectionId = sectionId; }
    public UUID getExamId() { return examId; }
    public void setExamId(UUID examId) { this.examId = examId; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
