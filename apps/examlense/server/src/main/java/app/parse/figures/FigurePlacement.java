package app.parse.figures;

import java.util.UUID;

/**
 * A figure block the parser created, carried from persistence to extraction.
 *
 * <p>{@code pageNumber} is the parser's own {@code page_number}, which the
 * persister otherwise drops on the floor. It is nullable: the model is not
 * required to supply it, and a figure without one is never given an image.
 */
public record FigurePlacement(UUID blockId, Integer pageNumber, String label, int order) {}
