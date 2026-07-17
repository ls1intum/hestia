package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.List;

/**
 * A course-level <em>terminal competency</em> — an applied capability a student should be able to
 * perform after completing the course ("deploy a cloud-native application"), derived by the
 * {@link TerminalCompetencySynthesizer} by clustering the course's higher-Bloom goals across topics.
 *
 * <p>It is not a conservative bottom-up summary of a session's topic; it is the top of a competency
 * tree, deliberately framed around a <em>doing</em> verb and built primarily from the course's
 * {@code APPLY}/{@code CREATE} goals (the {@code ANALYZE}/
 * {@code EVALUATE} "compare/understand" goals usually sit beneath it as supporting knowledge). It is
 * an abstraction over several sub-goals, so it carries no verbatim source snippet.
 *
 * @param text       the competency as a single concise sentence built around ONE action verb.
 * @param supporting the zero-based indices of the input candidate goals this competency genuinely
 *                   builds on; drives the later tree/CONTRIBUTES_TO links so each candidate attaches
 *                   only to the competency it actually serves.
 */
public record TerminalCompetency(String text, List<Integer> supporting) {

    public TerminalCompetency {
        supporting = supporting == null ? List.of() : List.copyOf(supporting);
    }

    /** Convenience for callers/tests that do not track provenance. */
    public TerminalCompetency(String text) {
        this(text, List.of());
    }
}
