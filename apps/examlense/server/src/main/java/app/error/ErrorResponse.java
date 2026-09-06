package app.error;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The uniform error envelope every failing request returns.
 *
 * Documentation-only: the wire shape is produced by
 * {@link GlobalExceptionHandler} as a {@code Map.of("error", ...)}, and by the
 * 401/429 writers in {@code SecurityConfig} / {@code RateLimitFilter}. This
 * record exists so the OpenAPI spec can name one schema instead of describing a
 * free-form object at every error response. Keep it in step with those writers.
 */
@Schema(name = "ErrorResponse", description = "Error envelope returned by every failing request.")
public record ErrorResponse(
    @Schema(description = "Human-readable failure reason.", example = "Exam is not currently processing")
    String error
) {}
