package de.tum.cit.hestia.ai;

import java.util.List;
import java.util.Map;

/** What a model call sends as the user turn, and the optional tool that forces structured output. */
public final class LlmContent {

    private LlmContent() {}

    /** A user message is either a plain string or a list of typed parts. */
    public sealed interface UserContent permits TextContent, MultipartContent {}

    public record TextContent(String text) implements UserContent {}

    public record MultipartContent(List<ContentPart> parts) implements UserContent {
        public MultipartContent {
            parts = List.copyOf(parts);
        }
    }

    public sealed interface ContentPart permits TextPart, ImageUrlPart, FilePart {}

    public record TextPart(String text) implements ContentPart {}

    /** An image as {@code data:<mime>;base64,<data>} URL (or a plain https URL). */
    public record ImageUrlPart(String url) implements ContentPart {}

    /** A whole file (for example a PDF), base64 or as data URL. Only the direct providers accept it. */
    public record FilePart(String filename, String fileData, String mediaType) implements ContentPart {}

    /** OpenAI-style function/tool definition for structured output. Only the direct providers accept it. */
    public record Tool(String name, String description, Map<String, Object> schema) {}
}
