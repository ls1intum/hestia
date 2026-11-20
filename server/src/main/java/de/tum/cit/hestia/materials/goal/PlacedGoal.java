package de.tum.cit.hestia.materials.goal;

import de.tum.cit.hestia.core.GoalInfo;
import de.tum.cit.hestia.core.GoalKind;
import de.tum.cit.hestia.core.GoalOrigin;
import de.tum.cit.hestia.core.GoalRole;
import de.tum.cit.hestia.core.GoalStatus;
import de.tum.cit.hestia.materials.exam.submission.SubmittedExamBlock;
import de.tum.cit.hestia.materials.hierarchy.HierarchyNode;
import de.tum.cit.hestia.materials.taxonomy.BloomLevel;
import de.tum.cit.hestia.materials.taxonomy.SoloLevel;
import java.util.UUID;

/**
 * A learning goal of {@code core} together with its materials placement (may be null for goals made
 * elsewhere). The hierarchy node and exam block are lazy: read them inside a transaction.
 */
public record PlacedGoal(GoalInfo info, GoalPlacement placement) {

    public UUID id() {
        return info.id();
    }

    public UUID courseId() {
        return info.courseId();
    }

    public String text() {
        return info.text();
    }

    /**
     * The generated wording, kept on the first rename; null while the goal was never renamed and for
     * goals an instructor typed.
     */
    public String originalText() {
        if (info.version() <= 1 || GoalCreationProvenance.USER_CREATED.name().equals(info.creationProvenance())) {
            return null;
        }
        return info.originalText();
    }

    public String shortLabel() {
        return info.shortLabel();
    }

    public GoalKind kind() {
        return info.kind();
    }

    public GoalRole role() {
        return info.role();
    }

    public GoalOrigin origin() {
        return info.origin();
    }

    public GoalStatus status() {
        return info.status();
    }

    public GoalCreationProvenance creationProvenance() {
        return info.creationProvenance() == null ? null : GoalCreationProvenance.valueOf(info.creationProvenance());
    }

    public BloomLevel bloomLevel() {
        return info.bloomLevel() == null ? null : BloomLevel.valueOf(info.bloomLevel());
    }

    public SoloLevel soloLevel() {
        return info.soloLevel() == null ? null : SoloLevel.valueOf(info.soloLevel());
    }

    public HierarchyNode hierarchyNode() {
        return placement == null ? null : placement.getHierarchyNode();
    }

    public SubmittedExamBlock examBlock() {
        return placement == null ? null : placement.getExamBlock();
    }

    public Integer lectureOrder() {
        return placement == null ? null : placement.getLectureOrder();
    }
}
