package de.tum.cit.hestia.learninggoalhub.exam;

/**
 * One learning goal the LLM derived from an exam task (structured output of {@link ExamGoalGenerator}).
 *
 * @param shortLabel a compact label reusing the verb of {@code text}; may be missing.
 */
public record GeneratedExamGoal(String text, String shortLabel) {

    public GeneratedExamGoal(String text) {
        this(text, null);
    }
}
