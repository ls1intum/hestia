package de.tum.cit.hestia.learninggoalhub.goal;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "learning_goal")
public class LearningGoal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GoalKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GoalOrigin origin = GoalOrigin.EXTRACTED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GoalStatus status = GoalStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hierarchy_node_id")
    private HierarchyNode hierarchyNode;

    @Enumerated(EnumType.STRING)
    @Column(name = "bloom_level", length = 32)
    private BloomLevel bloomLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "solo_level", length = 32)
    private SoloLevel soloLevel;

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Column(name = "embedding", columnDefinition = "vector(4096)")
    private float[] embedding;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected LearningGoal() {
    }

    public LearningGoal(Course course, String text, GoalKind kind) {
        this.course = course;
        this.text = text;
        this.kind = kind;
    }

    public Long getId() {
        return id;
    }

    public Course getCourse() {
        return course;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public GoalKind getKind() {
        return kind;
    }

    public GoalOrigin getOrigin() {
        return origin;
    }

    public void setOrigin(GoalOrigin origin) {
        this.origin = origin;
    }

    public GoalStatus getStatus() {
        return status;
    }

    public void setStatus(GoalStatus status) {
        this.status = status;
    }

    public HierarchyNode getHierarchyNode() {
        return hierarchyNode;
    }

    public void setHierarchyNode(HierarchyNode hierarchyNode) {
        this.hierarchyNode = hierarchyNode;
    }

    public BloomLevel getBloomLevel() {
        return bloomLevel;
    }

    public void setBloomLevel(BloomLevel bloomLevel) {
        this.bloomLevel = bloomLevel;
    }

    public SoloLevel getSoloLevel() {
        return soloLevel;
    }

    public void setSoloLevel(SoloLevel soloLevel) {
        this.soloLevel = soloLevel;
    }

    public float[] getEmbedding() {
        return embedding;
    }

    public void setEmbedding(float[] embedding) {
        this.embedding = embedding;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
