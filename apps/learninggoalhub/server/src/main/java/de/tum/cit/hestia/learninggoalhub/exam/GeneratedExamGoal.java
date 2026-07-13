package de.tum.cit.hestia.learninggoalhub.exam;

/** One learning goal the LLM derived from an exam task (structured output of {@link ExamGoalGenerator}). */
public record GeneratedExamGoal(String text) {
}
