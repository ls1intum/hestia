package de.tum.cit.hestia.learninggoalhub.extraction;

/**
 * One verdict from the competency-tree <em>assignment</em> pass (LAYER 2, pass A): the course's
 * terminal competencies are fixed, and every session-/exercise-level goal is routed to the single
 * competency whose tree it belongs under. This gives the competency tree FULL coverage — unlike the
 * sparse {@link TerminalCompetency#supporting()} hints from the top-down clustering, every relevant
 * goal lands somewhere — and brings the lower-Bloom knowledge goals (which the terminal synthesis
 * never saw) into the tree as candidate knowledge leaves.
 *
 * @param goal       the zero-based index of the goal in the candidate list handed to the pass.
 * @param competency the zero-based index of the terminal competency this goal belongs under, or a
 *                   negative value when the goal fits no competency (course administration, logistics
 *                   or one-off trivia) and should stay out of the tree.
 */
public record CompetencyAssignment(int goal, int competency) {
}
