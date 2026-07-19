package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.List;

/**
 * A course-level <em>terminal competency</em> — an applied capability a student should be able to
 * perform after completing the course ("deploy a cloud-native application"), derived by the
 * {@link TerminalCompetencySynthesizer} by clustering all course goals across topics in one call.
 *
 * <p>It is not a conservative bottom-up summary of a session's topic; it is the top of a competency
 * tree, deliberately framed around a <em>doing</em> verb and built primarily from the course's
 * {@code APPLY}/{@code CREATE} goals (the {@code ANALYZE}/
 * {@code EVALUATE} "compare/understand" goals usually sit beneath it as supporting knowledge). Its
 * complete {@code supporting} assignment also brings lower-Bloom knowledge goals into the tree. It
 * is an abstraction over several sub-goals, so it carries no verbatim source snippet.
 *
 * @param text       the competency as a single concise sentence built around ONE action verb.
 * @param shortLabel the compact noun phrase naming the competency's topic.
 * @param supporting the zero-based indices of all input candidate goals assigned to this competency;
 *                   drives the later tree/CONTRIBUTES_TO links so each candidate attaches only to
 *                   the competency it actually serves.
 */
public record TerminalCompetency(String text, String shortLabel, List<Integer> supporting) {

    public TerminalCompetency {
        supporting = supporting == null ? List.of() : List.copyOf(supporting);
    }

    public TerminalCompetency(String text, List<Integer> supporting) {
        this(text, null, supporting);
    }

    /** Convenience for callers/tests that do not track provenance. */
    public TerminalCompetency(String text) {
        this(text, null, List.of());
    }
}
