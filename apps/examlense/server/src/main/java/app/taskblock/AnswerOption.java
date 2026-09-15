package app.taskblock;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One choice within a {@link TaskBlock}'s {@code options} JSONB array.
 * Shape matches what the parse/solve pipeline stores: {@code {id, text, is_correct}}.
 */
public record AnswerOption(
        String id,
        String text,
        @JsonProperty("is_correct") boolean isCorrect
) {}
