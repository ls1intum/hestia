package de.tum.cit.hestia.materials.goal;

import de.tum.cit.hestia.core.OrgScoped;
import de.tum.cit.hestia.materials.exam.submission.SubmittedExamBlock;
import de.tum.cit.hestia.materials.hierarchy.HierarchyNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * What only the materials module knows about a learning goal of {@code core}: the hierarchy node it
 * sits under, the exam task an exam goal came from and its order in the lecture material.
 */
@Entity
@Table(schema = "materials", name = "goal_placement")
public class GoalPlacement extends OrgScoped {

    @Id
    @Column(name = "goal_id", nullable = false, updatable = false)
    private UUID goalId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hierarchy_node_id")
    private HierarchyNode hierarchyNode;

    /** The exam task an EXAM goal was derived from; null for every other goal. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exam_block_id")
    private SubmittedExamBlock examBlock;

    /** Stable course-wide order derived from the goal's first appearance in the lecture material. */
    @Column(name = "lecture_order")
    private Integer lectureOrder;

    protected GoalPlacement() {
    }

    public GoalPlacement(UUID goalId, HierarchyNode hierarchyNode, SubmittedExamBlock examBlock,
                         Integer lectureOrder) {
        this.goalId = goalId;
        this.hierarchyNode = hierarchyNode;
        this.examBlock = examBlock;
        this.lectureOrder = lectureOrder;
    }

    public UUID getGoalId() {
        return goalId;
    }

    public HierarchyNode getHierarchyNode() {
        return hierarchyNode;
    }

    public void setHierarchyNode(HierarchyNode hierarchyNode) {
        this.hierarchyNode = hierarchyNode;
    }

    public SubmittedExamBlock getExamBlock() {
        return examBlock;
    }

    public void setExamBlock(SubmittedExamBlock examBlock) {
        this.examBlock = examBlock;
    }

    public Integer getLectureOrder() {
        return lectureOrder;
    }

    public void setLectureOrder(Integer lectureOrder) {
        this.lectureOrder = lectureOrder;
    }
}
