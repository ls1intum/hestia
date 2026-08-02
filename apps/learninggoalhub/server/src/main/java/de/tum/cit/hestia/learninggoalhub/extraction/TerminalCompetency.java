package de.tum.cit.hestia.learninggoalhub.extraction;

/**
 * A course-level <em>terminal competency</em> — an applied capability a student should be able to
 * perform after completing the course ("deploy a cloud-native application"), derived by the
 * {@link TerminalCompetencySynthesizer} by clustering all course goals across topics in one call.
 *
 * <p>It is not a conservative bottom-up summary of a session's topic; it is the top of a competency
 * tree, deliberately framed around a <em>doing</em> verb and built primarily from the course's
 * {@code APPLY}/{@code CREATE} goals (the {@code ANALYZE}/{@code EVALUATE} "compare/understand"
 * goals usually sit beneath it as supporting knowledge). It is an abstraction over several
 * sub-goals, so it carries no verbatim source snippet.
 *
 * <p>Naming a competency and deciding which goals belong to it are deliberately <b>separate</b>
 * steps, so this record carries no goal assignment. The assignment is a second call
 * ({@link CompetencyAssignmentSynthesizer}) that sees the COMPLETE competency list; naming and
 * assigning in one pass let a goal be committed to a competency before the competency that actually
 * fits it had been named.
 *
 * @param text       the competency as a single concise sentence built around ONE action verb.
 * @param shortLabel the compact noun phrase naming the competency's topic.
 */
public record TerminalCompetency(String text, String shortLabel) {

    /** Convenience for callers/tests that do not carry a short label. */
    public TerminalCompetency(String text) {
        this(text, null);
    }
}
