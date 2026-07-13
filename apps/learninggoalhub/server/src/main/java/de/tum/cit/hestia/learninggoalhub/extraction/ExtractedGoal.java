package de.tum.cit.hestia.learninggoalhub.extraction;

import de.tum.cit.hestia.learninggoalhub.goal.GoalKind;

/**
 * A single learning goal as returned by the extraction LLM. Where the goal sits in the course
 * structure is not guessed here per chunk; it is determined by the document's structural section
 * (see {@link de.tum.cit.hestia.learninggoalhub.document.DocumentStructureService}) and attached
 * during {@link ExtractionRunner}.
 */
public record ExtractedGoal(String text, GoalKind kind, String sourceSnippet) {
}
