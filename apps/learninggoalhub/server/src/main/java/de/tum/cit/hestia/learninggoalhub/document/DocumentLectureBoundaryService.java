package de.tum.cit.hestia.learninggoalhub.document;

import de.tum.cit.hestia.learninggoalhub.llm.LenientJson;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/** Detects lecture/chapter boundaries in a large PDF that has no usable bookmark outline. */
@Service
public class DocumentLectureBoundaryService {

    private static final Logger log = LoggerFactory.getLogger(DocumentLectureBoundaryService.class);
    private static final int PAGE_PREVIEW_CHARS = 500;

    record Boundary(int startPage, String title) {}
    record DetectedOutline(List<Boundary> sections) {
        DetectedOutline {
            sections = sections == null ? List.of() : List.copyOf(sections);
        }
    }

    static final String PROMPT = """
            Determine whether this single university-course PDF contains MULTIPLE separate teaching
            units such as lectures, chapters, seminars or exercise sessions. The subject and language
            may be anything.

            Return {"sections":[]} when the PDF is one teaching unit, when apparent headings are only
            its internal agenda/topics, or when the evidence is uncertain. Otherwise return
            {"sections":[{"startPage":1,"title":"..."}, ...]} with one entry per genuinely separate
            unit in physical page order.

            Rules:
              - Return at least two sections when splitting, and make the first startPage exactly 1
                so front matter remains attached to the first unit.
              - startPage values are strictly increasing page numbers from the supplied excerpts.
              - A section title is concise, specific, and written in the document's own language.
              - Do not split a single slide deck at agenda items, theorem headings, examples, topic
                transitions, or PowerPoint sections.
              - Do not assume that repeated headers alone mark boundaries. Look for strong resets such
                as a new lecture/chapter title, numbering sequence, date/week, or self-contained unit.
              - Return only structured JSON.

            Filename: %s
            Pages: %d

            Page-opening excerpts:
            ---
            %s
            ---
            """;

    private static final String RETRY = """

            Your previous outline was invalid. Regenerate the COMPLETE response with either an empty
            sections array or at least two strictly ordered, in-range sections beginning at page 1.
            """;

    private final ChatClient chatClient;
    private final String model;
    private final int minPages;

    public DocumentLectureBoundaryService(
            ChatClient.Builder chatClientBuilder,
            @Value("${hestia.document-outline.model:openai-gpt-oss-120b}") String model,
            @Value("${hestia.document-outline.min-pages:8}") int minPages) {
        this.chatClient = chatClientBuilder.build();
        this.model = model;
        this.minPages = minPages;
    }

    public List<DocumentStructureService.SectionSpan> detect(
            String filename, String rawText, int[] pageOffsets) {
        int pageCount = pageOffsets == null ? 0 : pageOffsets.length - 1;
        if (pageCount < Math.max(2, minPages) || rawText == null || rawText.isBlank()) {
            return List.of();
        }
        String prompt = PROMPT.formatted(filename, pageCount, pagePreviews(rawText, pageOffsets));
        try {
            DetectedOutline first = call(prompt);
            try {
                return validate(first, rawText.length(), pageOffsets);
            } catch (IllegalArgumentException invalid) {
                log.warn("Document outline for '{}' was invalid; retrying once: {}", filename,
                        invalid.getMessage());
                return validate(call(prompt + RETRY + "\nSpecific failure: " + invalid.getMessage()),
                        rawText.length(), pageOffsets);
            }
        } catch (RuntimeException ex) {
            log.warn("Could not detect lecture boundaries for '{}'; keeping one session: {}",
                    filename, ex.getMessage());
            return List.of();
        }
    }

    private DetectedOutline call(String prompt) {
        return chatClient.prompt()
                .options(ChatOptions.builder().model(model).temperature(0.0).build())
                .user(prompt)
                .call()
                .entity(LenientJson.converter(new ParameterizedTypeReference<DetectedOutline>() {}));
    }

    static List<DocumentStructureService.SectionSpan> validate(
            DetectedOutline outline, int textLength, int[] pageOffsets) {
        if (outline == null || outline.sections().isEmpty() || outline.sections().size() == 1) {
            return List.of();
        }
        int pageCount = pageOffsets == null ? 0 : pageOffsets.length - 1;
        List<Boundary> boundaries = outline.sections();
        if (boundaries.getFirst() == null || boundaries.getFirst().startPage() != 1) {
            throw new IllegalArgumentException("the first section must start at page 1");
        }
        Set<String> titles = new HashSet<>();
        int previousPage = 0;
        for (Boundary boundary : boundaries) {
            if (boundary == null || boundary.startPage() <= previousPage || boundary.startPage() > pageCount) {
                throw new IllegalArgumentException("section pages must be strictly increasing and in range");
            }
            if (boundary.title() == null || boundary.title().isBlank()
                    || !titles.add(boundary.title().strip().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("section titles must be non-blank and distinct");
            }
            previousPage = boundary.startPage();
        }

        List<DocumentStructureService.SectionSpan> result = new ArrayList<>(boundaries.size());
        for (int index = 0; index < boundaries.size(); index++) {
            Boundary boundary = boundaries.get(index);
            int start = Math.max(0, Math.min(pageOffsets[boundary.startPage() - 1], textLength));
            int endPage = index + 1 < boundaries.size()
                    ? boundaries.get(index + 1).startPage() - 1
                    : pageCount;
            int end = Math.max(start, Math.min(pageOffsets[endPage], textLength));
            result.add(new DocumentStructureService.SectionSpan(
                    boundary.title().strip(), start, end, boundary.startPage(), endPage));
        }
        return List.copyOf(result);
    }

    private static String pagePreviews(String rawText, int[] pageOffsets) {
        StringBuilder result = new StringBuilder();
        for (int page = 1; page < pageOffsets.length; page++) {
            int start = Math.max(0, Math.min(pageOffsets[page - 1], rawText.length()));
            int end = Math.max(start, Math.min(pageOffsets[page], rawText.length()));
            String preview = rawText.substring(start, end).replaceAll("\\s+", " ").strip();
            if (preview.length() > PAGE_PREVIEW_CHARS) {
                preview = preview.substring(0, PAGE_PREVIEW_CHARS).strip() + "…";
            }
            result.append("[page ").append(page).append("] ").append(preview).append('\n');
        }
        return result.toString();
    }
}
