package de.tum.cit.hestia.learninggoalhub.goal;

/**
 * How a goal that did NOT come out of the ordinary extraction pipeline was created.
 *
 * <p>Left {@code null} for every goal the pipeline itself produces (extracted, synthesized, exam,
 * terminal, gap) — {@code origin} already records their structural role. This field only
 * distinguishes goals an instructor introduced after the fact, which share an {@code origin} with
 * pipeline goals and would otherwise be indistinguishable.
 *
 * <ul>
 *   <li>{@code USER_CREATED} — an instructor added this goal by hand (e.g. a terminal skill typed in
 *       the post-extraction review).</li>
 *   <li>{@code WIZARD_AI_SUBTREE} — generated on demand for an instructor-added skill (reserved for a
 *       later stage that grows a full sub-skill/knowledge subtree).</li>
 * </ul>
 */
public enum GoalCreationProvenance {
    USER_CREATED,
    WIZARD_AI_SUBTREE
}
