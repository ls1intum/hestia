package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.List;

/**
 * The result of the competency-tree <em>expansion</em> pass (LAYER 2, pass B) for ONE terminal
 * competency. The caller has already split that competency's assigned goals by Bloom into its
 * <em>sub-skills</em> (the {@code APPLY}/{@code CREATE} doing-goals) and its candidate <em>knowledge</em>
 * goals (the lower-Bloom ones). This pass arranges them into the fixed three-tier shape
 * {@code terminal → sub-skill → knowledge}: it attaches each knowledge goal under the sub-skill it
 * supports, and — because the lowest node must always be a knowledge aspect — names the knowledge a
 * sub-skill clearly needs but the material does NOT cover, as gap nodes.
 *
 * <p>Indices are positional into the per-competency lists handed to the pass: {@code subSkillIndex}
 * into the sub-skill list, {@code knowledgeIndex} into the knowledge list.
 *
 * <p>Gaps come in two tiers. A {@link Gap} is missing <em>knowledge</em> beneath an existing
 * sub-skill (tier 3). A {@link MissingSubSkill} is a missing <em>doing-capability</em> the competency
 * needs but no sub-skill covers (tier 2); to keep the lowest node a knowledge aspect it must bottom
 * out in knowledge — existing knowledge re-mapped under it and/or its own tier-3 knowledge gaps.
 *
 * @param knowledge        each grounded knowledge goal attached under the sub-skill it supports.
 * @param gaps             "should-be-taught" knowledge an existing sub-skill needs but the material
 *                         lacks; persisted as unanchored {@code GAP}-origin goals.
 * @param missingSubSkills doing-capabilities the competency needs but no sub-skill covers; each a
 *                         {@code GAP}-origin sub-skill node bottoming out in knowledge.
 */
public record CompetencyExpansion(List<KnowledgeLink> knowledge, List<Gap> gaps,
                                  List<MissingSubSkill> missingSubSkills) {

    public CompetencyExpansion {
        knowledge = knowledge == null ? List.of() : List.copyOf(knowledge);
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
        missingSubSkills = missingSubSkills == null ? List.of() : List.copyOf(missingSubSkills);
    }

    /**
     * Attaches a grounded knowledge goal under the sub-skill it supports.
     *
     * @param knowledgeIndex the knowledge goal's index in the per-competency knowledge list.
     * @param subSkillIndex  the sub-skill's index in the per-competency sub-skill list.
     */
    public record KnowledgeLink(int knowledgeIndex, int subSkillIndex) {
    }

    /**
     * A missing knowledge aspect beneath a sub-skill.
     *
     * @param subSkillIndex the sub-skill this knowledge belongs under, by index.
     * @param knowledge     the missing knowledge, phrased as a learning goal (e.g. "Explain the
     *                      idempotency of HTTP methods"); rendered as an unanchored gap node.
     */
    public record Gap(int subSkillIndex, String knowledge) {
    }

    /**
     * A missing doing-capability the competency needs but no existing sub-skill covers, rendered as an
     * unanchored {@code GAP} sub-skill node. It must bottom out in knowledge so the lowest node stays a
     * knowledge aspect: at least one of {@code knowledgeIndices} or {@code knowledgeGaps} is non-empty.
     *
     * @param subSkill         the missing capability, phrased as an applied learning goal (e.g.
     *                         "Implement a retry policy for a failed HTTP request").
     * @param knowledgeIndices indices into the per-competency knowledge list of grounded knowledge that
     *                         underpins this missing sub-skill (the course taught it but never the doing).
     * @param knowledgeGaps    foundational knowledge the missing sub-skill needs that is ALSO missing,
     *                         each phrased as a recall/understanding learning goal; rendered as gap nodes.
     */
    public record MissingSubSkill(String subSkill, List<Integer> knowledgeIndices,
                                  List<String> knowledgeGaps) {
        public MissingSubSkill {
            knowledgeIndices = knowledgeIndices == null ? List.of() : List.copyOf(knowledgeIndices);
            knowledgeGaps = knowledgeGaps == null ? List.of() : List.copyOf(knowledgeGaps);
        }
    }
}
