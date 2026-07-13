package de.tum.cit.hestia.learninggoalhub.taxonomy;

import de.tum.cit.hestia.learninggoalhub.goal.BloomLevel;
import de.tum.cit.hestia.learninggoalhub.goal.SoloLevel;

/**
 * One entry of the taxonomy LLM's answer when classifying a batch of goals in a single call. The
 * {@code index} is the goal's 1-based position in the numbered list given in the prompt, so the
 * {@link TaxonomyService} can map each classification back to its goal even if the model reorders,
 * drops or duplicates entries.
 *
 * @param index the goal's 1-based number from the prompt's list.
 * @param bloom the chosen Bloom level, or null if the model could not decide.
 * @param solo  the chosen SOLO level, or null if the model could not decide.
 */
public record BatchTaxonomyItem(int index, BloomLevel bloom, SoloLevel solo) {
}
