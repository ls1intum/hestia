package de.tum.cit.hestia.learninggoalhub.goal;

/**
 * How a learning goal came to exist.
 *
 * <ul>
 *   <li>{@code EXTRACTED} — read directly from document material by the extraction LLM (grounded in
 *       a verbatim source snippet).</li>
 *   <li>{@code SYNTHESIZED} — derived bottom-up from a course's session-/exercise-level goals as an
 *       overarching module outcome; an abstraction without a single source snippet.</li>
 *   <li>{@code EXAM} — derived from an exam task submitted by an API consumer (ExamLens); grounded in
 *       the task's wording rather than uploaded course material, so it has no source snippet. Attached
 *       to the course's EXAM hierarchy root. See {@code ExamGoalService}.</li>
 *   <li>{@code TERMINAL} — a course-level terminal competency clustered top-down from the course's
 *       higher-Bloom goals (the root of a competency tree); an abstraction without a source snippet.
 *       See {@code TerminalCompetencySynthesizer}.</li>
 *   <li>{@code GAP} — a "should-be-taught" knowledge aspect the gap analysis judges MISSING from the
 *       material beneath a sub-skill of a terminal competency; deliberately UNANCHORED (no source
 *       snippet) and rendered distinctly so an instructor sees what the course does not yet cover.
 *       See {@code CompetencyTreeSynthesizer}.</li>
 * </ul>
 */
public enum GoalOrigin {
    EXTRACTED,
    SYNTHESIZED,
    EXAM,
    TERMINAL,
    GAP
}
