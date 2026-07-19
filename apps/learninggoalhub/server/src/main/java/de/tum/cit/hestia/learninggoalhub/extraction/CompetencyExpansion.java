package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.List;

/**
 * The result of the course-wide competency-tree <em>expansion</em> pass for ONE terminal competency.
 * The caller has already split that competency's assigned goals by Bloom into its
 * <em>sub-skills</em> (the {@code APPLY}/{@code ANALYZE}/{@code EVALUATE}/{@code CREATE} doing-goals)
 * and its candidate <em>knowledge</em> goals (the lower-Bloom ones). This pass arranges them into the
 * fixed three-tier shape {@code terminal → sub-skill → knowledge} by attaching each knowledge goal
 * under the sub-skill it supports.
 *
 * <p>Indices are positional into the per-competency lists handed to the pass: {@code subSkillIndex}
 * into the sub-skill list, {@code knowledgeIndex} into the knowledge list.
 *
 * @param competencyIndex the zero-based index of the terminal competency in the expansion request.
 * @param knowledge        each grounded knowledge goal attached under the sub-skill it supports.
 */
public record CompetencyExpansion(int competencyIndex, List<KnowledgeLink> knowledge) {

    public CompetencyExpansion {
        knowledge = knowledge == null ? List.of() : List.copyOf(knowledge);
    }

    /**
     * Attaches a grounded knowledge goal under the sub-skill it supports.
     *
     * @param knowledgeIndex the knowledge goal's index in the per-competency knowledge list.
     * @param subSkillIndex  the sub-skill's index in the per-competency sub-skill list.
     */
    public record KnowledgeLink(int knowledgeIndex, int subSkillIndex) {
    }
}
