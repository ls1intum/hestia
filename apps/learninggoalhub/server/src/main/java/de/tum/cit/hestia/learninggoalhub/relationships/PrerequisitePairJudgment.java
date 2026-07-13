package de.tum.cit.hestia.learninggoalhub.relationships;

/**
 * The LLM's verdict for one numbered candidate pair: which goal comes first and how confident the
 * model is. The {@code index} is the pair's 1-based number from the prompt, so the
 * {@link PrerequisiteLinker} maps the verdict back to the right goal pair even if the model reorders
 * or drops entries.
 *
 * @param index      the pair's 1-based number from the prompt.
 * @param direction  the prerequisite direction, or {@link PrerequisiteDirection#NONE}.
 * @param confidence number in [0.0, 1.0] reflecting how clearly the dependency is implied.
 */
public record PrerequisitePairJudgment(int index, PrerequisiteDirection direction, double confidence) {
}
