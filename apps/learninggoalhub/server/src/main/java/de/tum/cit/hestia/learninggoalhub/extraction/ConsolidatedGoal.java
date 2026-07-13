package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.List;

/**
 * A session-/lecture-level learning outcome derived by the {@link SessionGoalConsolidator} from the
 * fine-grained candidate goals that the per-chunk extraction produced for one session. The
 * consolidation is where the "a lecture has only a handful of broad outcomes" intent is actually
 * realised: the model sees all of a session's candidates at once and merges overlapping or narrow
 * ones into the few outcomes they serve.
 *
 * <p>Mirrors {@link SynthesizedModuleGoal} one level lower in the bottom-up pipeline
 * (chunk-candidates → session-goals → module-goals): the LLM returns only {@code text} plus the
 * indices of the supporting candidates, and {@link ExtractionRunner} derives the goal's kind and
 * source snippet from those candidates.
 *
 * @param text       the consolidated outcome as a single concise sentence, starting with a verb.
 * @param supporting the zero-based indices of the input candidate goals that this outcome was merged
 *                   from. Drives both the candidate→goal provenance and the inherited source snippet.
 */
public record ConsolidatedGoal(String text, List<Integer> supporting) {

    public ConsolidatedGoal {
        supporting = supporting == null ? List.of() : List.copyOf(supporting);
    }

    /** Convenience for callers/tests that do not track provenance. */
    public ConsolidatedGoal(String text) {
        this(text, List.of());
    }
}
