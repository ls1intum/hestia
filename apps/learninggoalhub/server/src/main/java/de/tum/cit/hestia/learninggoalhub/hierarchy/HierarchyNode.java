package de.tum.cit.hestia.learninggoalhub.hierarchy;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.document.Document;
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

@Entity
@Table(name = "hierarchy_node")
public class HierarchyNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private HierarchyNode parent;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private HierarchyLevel level;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String label;

    /** The document this session/exercise unit was derived from; null for the module root. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id")
    private Document document;

    protected HierarchyNode() {
    }

    public HierarchyNode(Course course, HierarchyNode parent, HierarchyLevel level, String label) {
        this(course, parent, level, label, null);
    }

    public HierarchyNode(Course course, HierarchyNode parent, HierarchyLevel level, String label, Document document) {
        this.course = course;
        this.parent = parent;
        this.level = level;
        this.label = label;
        this.document = document;
    }

    public Long getId() {
        return id;
    }

    public HierarchyNode getParent() {
        return parent;
    }

    public Course getCourse() {
        return course;
    }

    public HierarchyLevel getLevel() {
        return level;
    }

    public String getLabel() {
        return label;
    }

    public Document getDocument() {
        return document;
    }
}
