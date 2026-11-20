package de.tum.cit.hestia.materials;

import de.tum.cit.hestia.core.GoalKind;
import de.tum.cit.hestia.core.GoalOrigin;
import de.tum.cit.hestia.core.GoalRole;
import de.tum.cit.hestia.core.GoalStatus;
import de.tum.cit.hestia.materials.goal.GoalCreationProvenance;
import de.tum.cit.hestia.materials.goal.PlacedGoal;
import de.tum.cit.hestia.materials.hierarchy.HierarchyNode;
import de.tum.cit.hestia.materials.taxonomy.BloomLevel;
import de.tum.cit.hestia.materials.taxonomy.SoloLevel;
import java.util.UUID;

/**
 * A learning goal as the tests of the materials module build and read it: a draft that is stored in
 * {@code core} (plus the materials placement) by {@link TestGoalRepository#save}, and that reads
 * like the entity the tests were written against. Saving it again stores the changes the setters made.
 */
public class TestGoal {

    private final UUID courseId;
    private PlacedGoal saved;

    private String text;
    private String shortLabel;
    private GoalKind kind;
    private GoalRole role;
    private GoalOrigin origin = GoalOrigin.EXTRACTED;
    private GoalStatus status = GoalStatus.PENDING;
    private GoalCreationProvenance creationProvenance;
    private HierarchyNode hierarchyNode;
    private BloomLevel bloomLevel;
    private SoloLevel soloLevel;
    private Integer lectureOrder;

    public TestGoal(UUID courseId, String text, GoalKind kind) {
        this.courseId = courseId;
        this.text = text;
        this.kind = kind;
    }

    /** A stored goal as it is now. */
    static TestGoal of(PlacedGoal goal) {
        TestGoal g = new TestGoal(goal.courseId(), goal.text(), goal.kind());
        g.adopt(goal);
        return g;
    }

    void adopt(PlacedGoal goal) {
        this.saved = goal;
        this.text = goal.text();
        this.shortLabel = goal.shortLabel();
        this.kind = goal.kind();
        this.role = goal.role();
        this.origin = goal.origin();
        this.status = goal.status();
        this.creationProvenance = goal.creationProvenance();
        this.hierarchyNode = goal.hierarchyNode();
        this.bloomLevel = goal.bloomLevel();
        this.soloLevel = goal.soloLevel();
        this.lectureOrder = goal.lectureOrder();
    }

    PlacedGoal saved() {
        return saved;
    }

    public UUID getId() {
        return saved == null ? null : saved.id();
    }

    public UUID getCourseId() {
        return courseId;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    /** The wording of version 1; only known once the goal is stored. */
    public String getOriginalText() {
        return saved == null ? null : saved.originalText();
    }

    public String getShortLabel() {
        return shortLabel;
    }

    public void setShortLabel(String shortLabel) {
        this.shortLabel = shortLabel;
    }

    public GoalKind getKind() {
        return kind;
    }

    public GoalRole getRole() {
        return role;
    }

    public void setRole(GoalRole role) {
        this.role = role;
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

    public GoalCreationProvenance getCreationProvenance() {
        return creationProvenance;
    }

    public void setCreationProvenance(GoalCreationProvenance creationProvenance) {
        this.creationProvenance = creationProvenance;
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

    public Integer getLectureOrder() {
        return lectureOrder;
    }

    public void setLectureOrder(Integer lectureOrder) {
        this.lectureOrder = lectureOrder;
    }
}
