package de.tum.cit.hestia.learninggoalhub.extraction;

import de.tum.cit.hestia.learninggoalhub.goal.BloomLevel;
import de.tum.cit.hestia.learninggoalhub.goal.GoalKind;
import de.tum.cit.hestia.learninggoalhub.goal.SoloLevel;
import java.util.List;

/**
 * One skill returned by extraction, with the knowledge it rests on.
 *
 * <p>Bloom and SOLO arrive with the outcome rather than from a later classification call. The level
 * is carried by the verb and only by the verb, and the verb is chosen here, with the source material
 * in view; asking a second model to read it back off the finished sentence could only disagree with
 * the writer. It also lets the tier be stated as a contract — a skill is APPLY or above, a knowledge
 * item is REMEMBER or UNDERSTAND — which a separate classifier could never enforce, because by the
 * time it ran the tiers were already decided.
 */
public record ExtractedSkill(String text, String shortLabel, GoalKind kind,
                             BloomLevel bloom, SoloLevel solo,
                             Integer sourceStartLine, Integer sourceEndLine,
                             Integer sourceFigure,
                             List<Knowledge> knowledge) {

    public ExtractedSkill(String text, String shortLabel, GoalKind kind,
                          BloomLevel bloom, SoloLevel solo,
                          Integer sourceStartLine, Integer sourceEndLine,
                          List<Knowledge> knowledge) {
        this(text, shortLabel, kind, bloom, solo, sourceStartLine, sourceEndLine, null, knowledge);
    }

    public ExtractedSkill {
        knowledge = knowledge == null ? List.of() : List.copyOf(knowledge);
    }

    public record Knowledge(String text, String shortLabel, GoalKind kind,
                            BloomLevel bloom, SoloLevel solo,
                            Integer sourceStartLine, Integer sourceEndLine,
                            Integer sourceFigure) {

        public Knowledge(String text, String shortLabel, GoalKind kind,
                         BloomLevel bloom, SoloLevel solo,
                         Integer sourceStartLine, Integer sourceEndLine) {
            this(text, shortLabel, kind, bloom, solo, sourceStartLine, sourceEndLine, null);
        }
    }
}
