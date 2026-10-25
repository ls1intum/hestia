package de.tum.cit.hestia.learninggoalhub.extraction;

/**
 * What the competency tree came out as.
 *
 * @param competencies   how many terminal competencies were created.
 * @param unmatchedGoals source outcomes no topic covers, left outside the tree.
 */
public record CompetencyTreeResult(int competencies, int unmatchedGoals) {
    static final CompetencyTreeResult NONE = new CompetencyTreeResult(0, 0);
}
