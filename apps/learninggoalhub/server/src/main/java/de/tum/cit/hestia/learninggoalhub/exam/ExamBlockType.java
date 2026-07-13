package de.tum.cit.hestia.learninggoalhub.exam;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

/** Kind of an exam block: shared context for later tasks, or a task to derive goals for. */
public enum ExamBlockType {
    CONTEXT,
    TASK;

    /** Accepts any casing ("task", "TASK", …) since the consumer sends lower-case values. */
    @JsonCreator
    public static ExamBlockType parse(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
