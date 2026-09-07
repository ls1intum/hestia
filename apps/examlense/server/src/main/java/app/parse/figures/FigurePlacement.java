package app.parse.figures;

import java.util.UUID;

/**
 * A figure block the parser created, carried from persistence to extraction.
 *
 * <p>{@code pageNumber} is the parser's own {@code page_number}, which the
 * persister otherwise drops on the floor. It is nullable: the model is not
 * required to supply it, and a figure without one is never given an image.
 *
 * <p>{@code caption} rides along for the same reason: the figure row is created
 * asynchronously by extraction, long after the parse payload is gone, and this
 * is the only channel that reaches it.
 */
public record FigurePlacement(UUID blockId, Integer pageNumber, String label, String caption, int order) {}
