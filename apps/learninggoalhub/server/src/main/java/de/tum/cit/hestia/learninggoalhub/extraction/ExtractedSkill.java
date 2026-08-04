package de.tum.cit.hestia.learninggoalhub.extraction;

import de.tum.cit.hestia.learninggoalhub.goal.GoalKind;
import java.util.List;

public record ExtractedSkill(String text, String shortLabel, GoalKind kind,
                             Integer sourceStartLine, Integer sourceEndLine,
                             List<Knowledge> knowledge) {

    public ExtractedSkill {
        knowledge = knowledge == null ? List.of() : List.copyOf(knowledge);
    }

    public record Knowledge(String text, String shortLabel, GoalKind kind,
                            Integer sourceStartLine, Integer sourceEndLine) {
    }
}
