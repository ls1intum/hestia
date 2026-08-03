package de.tum.cit.hestia.learninggoalhub.extraction;

/**
 * One goal's placement in the competency tree, as decided by {@link CompetencyAssignmentSynthesizer}
 * against the COMPLETE list of terminal competencies.
 *
 * @param goalIndex       zero-based position of the goal in the candidate list handed to the
 *                        synthesiser.
 * @param competencyIndex zero-based position of the competency the goal belongs to, or {@code null}
 *                        when the goal fits none of them. A null is a real answer, not a failure:
 *                        it says the competency list does not cover this goal, and the caller
 *                        collects such goals rather than forcing them under a competency they do not
 *                        serve.
 */
public record CompetencyAssignment(int goalIndex, Integer competencyIndex) {
}
