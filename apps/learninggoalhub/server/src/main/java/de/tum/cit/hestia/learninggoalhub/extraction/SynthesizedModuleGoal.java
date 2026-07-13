package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.List;

/**
 * A module-level learning outcome derived bottom-up by the {@link ModuleGoalSynthesizer} from a
 * course's session- and exercise-level goals. Unlike an {@link ExtractedGoal} it is not grounded in
 * a verbatim source snippet — it is an abstraction over several sub-goals — so it carries no source
 * quote and is persisted with a SYNTHESIZED origin.
 *
 * @param text       the overarching outcome as a single concise sentence, starting with a verb.
 * @param supporting the zero-based indices of the input sub-goals that genuinely build toward this
 *                   outcome, as judged by the synthesiser. Drives the CONTRIBUTES_TO edges so each
 *                   sub-goal links only to the module outcome(s) it actually serves, instead of the
 *                   cartesian "every sub-goal contributes to every module goal".
 */
public record SynthesizedModuleGoal(String text, List<Integer> supporting) {

    public SynthesizedModuleGoal {
        supporting = supporting == null ? List.of() : List.copyOf(supporting);
    }

    /** Convenience for callers/tests that do not track provenance. */
    public SynthesizedModuleGoal(String text) {
        this(text, List.of());
    }
}
