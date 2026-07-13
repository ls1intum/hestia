package de.tum.cit.hestia.learninggoalhub.relationships;

import java.util.List;

/** Wrapper for the LLM's batch answer, so structured output binds to a named JSON field. */
public record PrerequisitePairJudgments(List<PrerequisitePairJudgment> judgments) {
}
