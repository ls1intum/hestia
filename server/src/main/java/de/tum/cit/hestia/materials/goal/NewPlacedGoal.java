package de.tum.cit.hestia.materials.goal;

import de.tum.cit.hestia.core.NewGoal;
import de.tum.cit.hestia.materials.exam.submission.SubmittedExamBlock;
import de.tum.cit.hestia.materials.hierarchy.HierarchyNode;

/** A goal to create in core together with its materials placement. */
public record NewPlacedGoal(NewGoal goal, HierarchyNode node, SubmittedExamBlock examBlock, Integer lectureOrder) {

    public static NewPlacedGoal of(NewGoal goal) {
        return new NewPlacedGoal(goal, null, null, null);
    }
}
