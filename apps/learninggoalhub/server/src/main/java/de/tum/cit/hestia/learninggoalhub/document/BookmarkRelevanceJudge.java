package de.tum.cit.hestia.learninggoalhub.document;

import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Decides whether a PDF's top-level bookmarks mark <em>separate</em> course units (each its own
 * session) or are just the internal outline of a <em>single</em> lecture/slide deck. The bookmark
 * heuristic in {@link DocumentStructureService} was built for a combined lecture script (one file,
 * ten chapter bookmarks = ten lectures); on one-file-per-lecture courses the same bookmarks are
 * PowerPoint's own section/slide markers ("Standardabschnitt", "Slide 7: …") and splitting on them
 * shatters one lecture into dozens of phantom sessions.
 *
 * <p>The split boundaries themselves stay deterministic (from the bookmarks); only this keep/discard
 * verdict is judged. The decision runs in two stages: a cheap mechanical pre-filter catches the
 * unambiguous PowerPoint-export shapes without any model call, and only the genuine grey zone (≥3
 * custom-named sections over many pages — a combined script vs. a deck with renamed sections) is sent
 * to a text model that weighs the filename and titles. On model failure it falls back to splitting,
 * which is the original behaviour and keeps a real combined script working when the LLM is down.
 */
@Service
public class BookmarkRelevanceJudge {

    private static final Logger log = LoggerFactory.getLogger(BookmarkRelevanceJudge.class);

    /** Fewer than this many top-level bookmarks is never a multi-lecture script (e.g. a guest talk
     * with a 2-entry agenda) — treat as one session. */
    private static final int MIN_SECTIONS_TO_SPLIT = 3;
    /** "Slide 7: …"/"Folie 7: …" — PowerPoint exports one bookmark per slide. */
    private static final Pattern PER_SLIDE = Pattern.compile("^(slide|folie)\\s*\\d+\\b.*",
            Pattern.CASE_INSENSITIVE);
    /** PowerPoint's auto-generated section name (German/English) — a sure sign of a single deck. */
    private static final List<String> DEFAULT_SECTION_NAMES = List.of("standardabschnitt", "default section");
    /** When bookmark count reaches this fraction of the page count, they are per-slide markers. */
    private static final double PER_SLIDE_RATIO = 0.8;

    static final String PROMPT = """
            A single PDF was uploaded from a university course. Decide whether its top-level bookmarks \
            mark SEPARATE course units — distinct lectures or chapters that each deserve their own \
            session — or are merely the INTERNAL outline of ONE lecture or slide deck (agenda items \
            like "Intro", "Motivation", "Summary").

            Guidance:
            - A combined script/reader covering a whole course has bookmarks that read like distinct, \
            self-contained topics or numbered chapters, each typically spanning many pages.
            - A single lecture's slide deck has bookmarks that read like the agenda of one talk, and \
            its filename usually names ONE topic or week (e.g. "W06 Continuous Delivery").

            Filename: %s
            Pages: %d
            Top-level bookmarks (%d):
            %s

            Reply with exactly one word: SPLIT or SINGLE.""";

    private final ChatClient chatClient;
    private final String judgeModel;

    public BookmarkRelevanceJudge(ChatClient.Builder chatClientBuilder,
                                  @Value("${hestia.bookmark.judge-model:openai-gpt-oss-120b}") String judgeModel) {
        this.chatClient = chatClientBuilder.build();
        this.judgeModel = judgeModel;
    }

    /**
     * Returns {@code true} when the document should be split into one session per top-level bookmark,
     * {@code false} when the bookmarks are a single lecture's internal structure and the document is
     * one session.
     */
    public boolean shouldSplit(String filename, int pageCount, List<String> bookmarkTitles) {
        Boolean mechanical = mechanicalVerdict(pageCount, bookmarkTitles);
        if (mechanical != null) {
            log.debug("Bookmark split verdict for {} decided mechanically: {}", filename, mechanical);
            return mechanical;
        }
        return askModel(filename, pageCount, bookmarkTitles);
    }

    /**
     * The cheap, deterministic pre-filter. Returns a verdict for the unambiguous shapes and
     * {@code null} for the grey zone that warrants a model call.
     */
    private Boolean mechanicalVerdict(int pageCount, List<String> titles) {
        if (titles.size() < MIN_SECTIONS_TO_SPLIT) {
            return false;
        }
        if (titles.stream().anyMatch(t -> DEFAULT_SECTION_NAMES.contains(t.strip().toLowerCase()))) {
            return false;
        }
        long perSlide = titles.stream().filter(t -> PER_SLIDE.matcher(t.strip()).matches()).count();
        if (perSlide * 2 > titles.size()) {
            return false;
        }
        if (pageCount > 0 && titles.size() >= PER_SLIDE_RATIO * pageCount) {
            return false;
        }
        return null;
    }

    private boolean askModel(String filename, int pageCount, List<String> titles) {
        String numbered = numberedList(titles);
        try {
            String reply = chatClient.prompt()
                    .options(ChatOptions.builder().model(judgeModel).build())
                    .user(PROMPT.formatted(filename, pageCount, titles.size(), numbered))
                    .call()
                    .content();
            Boolean verdict = parseVerdict(reply);
            if (verdict == null) {
                log.warn("Bookmark judge gave an unclear reply for {} ({}), falling back to split: {}",
                        filename, titles.size(), reply);
                return true;
            }
            log.debug("Bookmark split verdict for {} from model: {}", filename, verdict);
            return verdict;
        } catch (RuntimeException e) {
            log.warn("Bookmark judge unavailable for {}, falling back to split: {}", filename, e.getMessage());
            return true;
        }
    }

    private static String numberedList(List<String> titles) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < titles.size(); i++) {
            sb.append(i + 1).append(". ").append(titles.get(i)).append('\n');
        }
        return sb.toString().strip();
    }

    /** {@code true} for SPLIT, {@code false} for SINGLE, {@code null} when neither word is present. */
    static Boolean parseVerdict(String reply) {
        if (reply == null) {
            return null;
        }
        String upper = reply.toUpperCase();
        boolean single = upper.contains("SINGLE");
        boolean split = upper.contains("SPLIT");
        if (single == split) {
            return null;
        }
        return split;
    }
}
