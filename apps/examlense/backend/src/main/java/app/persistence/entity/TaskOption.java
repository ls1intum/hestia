package app.persistence.entity;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One choice within a {@link Task}'s {@code options} JSONB array.
 * Shape matches what the parse/solve pipeline stores: {@code {id, text, is_correct}}.
 */
public record TaskOption(
        String id,
        String text,
        @JsonProperty("is_correct") boolean isCorrect
) {}
