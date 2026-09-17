package de.tum.cit.hestia.learninggoalhub.extraction;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.document.Document;
import de.tum.cit.hestia.learninggoalhub.document.DocumentContentRepository;
import de.tum.cit.hestia.learninggoalhub.document.HighlightGeometryService;
import de.tum.cit.hestia.learninggoalhub.document.HighlightRect;
import de.tum.cit.hestia.learninggoalhub.document.LanguageUtils;
import de.tum.cit.hestia.learninggoalhub.document.PageDescriptionService;
import de.tum.cit.hestia.learninggoalhub.goal.EvidenceKind;
import de.tum.cit.hestia.learninggoalhub.goal.GoalRole;
import de.tum.cit.hestia.learninggoalhub.goal.GoalSource;
import de.tum.cit.hestia.learninggoalhub.goal.GoalSourceRepository;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoal;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyClassification;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * What a full extraction run and a topic search both do with one slice of a document: cut it into
 * windows, extract skills and knowledge from each window, order them into goals, resolve each goal's
 * quote to a page and highlight, and store that source.
 *
 * <p>Everything here is independent of how the slice was chosen. The full run picks bookmark
 * sections and hangs the result under hierarchy nodes; a topic search picks the pages an instructor
 * ticked. The owner type parameter carries whatever the caller needs to route a unit's goals back.
 */
@Service
public class UnitExtractor {

    private static final Logger log = LoggerFactory.getLogger(UnitExtractor.class);

    private final SessionExtractionService sessionExtractionService;
    private final DocumentContentRepository documentContentRepository;
    private final HighlightGeometryService highlightGeometryService;
    private final GoalSourceRepository goalSourceRepository;
    private final int unitMaxChars;
    private final int skillTargetChars;

    public UnitExtractor(SessionExtractionService sessionExtractionService,
                         DocumentContentRepository documentContentRepository,
                         HighlightGeometryService highlightGeometryService,
                         GoalSourceRepository goalSourceRepository,
                         @Value("${hestia.extraction.unit-max-chars:12000}") int unitMaxChars,
                         @Value("${hestia.extraction.skill-target-chars:3000}") int skillTargetChars) {
        this.sessionExtractionService = sessionExtractionService;
        this.documentContentRepository = documentContentRepository;
        this.highlightGeometryService = highlightGeometryService;
        this.goalSourceRepository = goalSourceRepository;
        this.unitMaxChars = unitMaxChars;
        this.skillTargetChars = skillTargetChars;
    }

    int unitMaxChars() {
        return unitMaxChars;
    }

    int skillTargetChars() {
        return skillTargetChars;
    }

    /**
     * Splits one section's range into consecutive windows that each fit the direct extraction call.
     *
     * <p>Structure decides the units wherever the document offers any: a PDF is cut only at page
     * boundaries, greedily packing whole pages up to the budget, so no slide is ever torn in half and
     * every window keeps a true page range for figure routing. A document with no page offsets is cut
     * at line boundaries instead. A single page larger than the budget is left whole and logged —
     * cutting inside it would corrupt the line numbering the model grounds its quotes in.
     *
     * <p>This IS the granularity mechanism. It was written as a safety net at 80,000 characters,
     * which no real section ever reached, so every section became exactly one extraction call however
     * large it was — a fifty-page deck and an eleven-page handout each got one call and one allowance.
     * At a budget sized to a lecture instead, a long section becomes several units and earns
     * proportionally more outcomes, while a short one still becomes exactly one.
     *
     * <p>Windows never cross a section boundary. A unit holding the tail of one lecture and the head
     * of the next would be asked for the outcomes of a slice that teaches two unrelated things, which
     * is how conjunction outcomes ("X und Y") get produced; keeping the boundary hard is what stops
     * the granularity change from pushing that pressure onto a new seam.
     *
     * @param label the section's name, only for logging.
     */
    public List<Window> windows(String label, Document document, String text,
                                int start, int end, Integer startPage, Integer endPage) {
        if (unitMaxChars <= 0 || end - start <= unitMaxChars) {
            return List.of(new Window(start, end, startPage, endPage));
        }
        List<Window> windows = new ArrayList<>();
        int[] pageOffsets = document.getPageOffsets();
        if (pageOffsets != null && pageOffsets.length >= 2) {
            int pageCount = pageOffsets.length - 1;
            int firstPage = startPage == null ? 1 : Math.max(1, startPage);
            int lastPage = endPage == null ? pageCount : Math.min(pageCount, endPage);
            int windowStart = start;
            int windowStartPage = firstPage;
            for (int page = firstPage; page <= lastPage; page++) {
                int pageEnd = clamp(pageOffsets[page], start, end);
                if (pageEnd - windowStart > unitMaxChars && page > windowStartPage) {
                    int cut = clamp(pageOffsets[page - 1], start, end);
                    windows.add(new Window(windowStart, cut, windowStartPage, page - 1));
                    windowStart = cut;
                    windowStartPage = page;
                }
            }
            windows.add(new Window(windowStart, end, windowStartPage, lastPage));
        } else {
            int windowStart = start;
            while (end - windowStart > unitMaxChars) {
                int limit = windowStart + unitMaxChars;
                int newline = text.lastIndexOf('\n', limit);
                int cut = newline > windowStart ? newline + 1 : limit;
                windows.add(new Window(windowStart, cut, null, null));
                windowStart = cut;
            }
            windows.add(new Window(windowStart, end, null, null));
        }
        if (windows.size() == 1) {
            log.warn("Section '{}' spans {} characters, above the {}-character extraction budget, but "
                            + "offers no boundary to split on; extracting it whole",
                    label, end - start, unitMaxChars);
        } else {
            log.info("Section '{}' spans {} characters, above the {}-character extraction budget; "
                            + "split into {} windows sharing its session",
                    label, end - start, unitMaxChars, windows.size());
        }
        return List.copyOf(windows);
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(value, high));
    }

    /** Runs the session extraction prompt over one unit. */
    public <T> SessionExtraction<T> extract(Course course, SessionUnit<T> session, String dominantLanguage,
                                            String modelOverride) {
        String languageCode = ExtractionRunner.resolveLanguage(
                course, session.document().getLanguage(), dominantLanguage);
        String languageName = LanguageUtils.englishName(languageCode);
        // The allowance follows the unit's own size, so a one-page problem sheet is not asked for as
        // many outcomes as a fifty-page lecture. Units are already split at the granularity budget
        // above, so this only has to scale what is left.
        int budget = SessionExtractionService.skillBudget(session.text().length(), skillTargetChars);
        List<ExtractedSkill> skills = sessionExtractionService.extract(
                session.title(), session.text(), languageCode, languageName,
                modelOverride, session.figures(), budget);
        if (skills != null && skills.size() > budget) {
            log.warn("Session '{}' returned {} skills, above its allowance of {}; keeping them all",
                    session.title(), skills.size(), budget);
        }
        return new SessionExtraction<>(session, skills == null ? List.of() : skills);
    }

    /**
     * Orders each unit's skills by where their quotes start and lists every skill followed by its
     * knowledge. Units that returned nothing are dropped.
     */
    public <T> List<UnitExtraction<T>> assemble(List<SessionExtraction<T>> extractedSessions) {
        List<UnitExtraction<T>> sessionGoals = new ArrayList<>();
        for (SessionExtraction<T> extraction : extractedSessions) {
            List<UnitGoal> goals = new ArrayList<>();
            List<ExtractedSkill> orderedSkills = extraction.skills().stream()
                    .sorted(Comparator.comparing(
                            ExtractedSkill::sourceStartLine,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            for (ExtractedSkill skill : orderedSkills) {
                ExtractedGoal skillGoal = new ExtractedGoal(
                        skill.text(), skill.shortLabel(), skill.kind(),
                        "");
                goals.add(new UnitGoal(skillGoal, GoalRole.SKILL, null,
                        new SourceLineSelection(skill.sourceStartLine(), skill.sourceEndLine(),
                                skill.sourceFigure()),
                        new TaxonomyClassification(skill.bloom(), skill.solo())));
                List<ExtractedSkill.Knowledge> orderedKnowledge = skill.knowledge().stream()
                        .sorted(Comparator.comparing(
                                ExtractedSkill.Knowledge::sourceStartLine,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .toList();
                for (ExtractedSkill.Knowledge knowledge : orderedKnowledge) {
                    ExtractedGoal knowledgeGoal = new ExtractedGoal(
                            knowledge.text(), knowledge.shortLabel(), knowledge.kind(),
                            "");
                    goals.add(new UnitGoal(knowledgeGoal, GoalRole.KNOWLEDGE, skillGoal,
                            new SourceLineSelection(knowledge.sourceStartLine(), knowledge.sourceEndLine(),
                                    knowledge.sourceFigure()),
                            new TaxonomyClassification(knowledge.bloom(), knowledge.solo())));
                }
            }
            if (!goals.isEmpty()) {
                sessionGoals.add(new UnitExtraction<>(extraction.session(), goals));
            }
        }
        return sessionGoals;
    }

    /**
     * Resolves where a goal's evidence sits: the quoted line span inside its window, else the figure
     * it names, else nothing. A goal without a line selection falls back to locating its raw snippet.
     *
     * @param window the window the goal was extracted from, or null for the whole document.
     */
    public ResolvedSource resolve(Document document, Window window, SourceLineSelection selection,
                                  List<PageDescriptionService.FigureDescription> figures, String sourceSnippet) {
        if (selection != null) {
            DirectSourceResolution direct = resolveDirectSource(document, window, selection, figures);
            return new ResolvedSource(direct.resolution(), direct.evidenceKind(), "");
        }
        String rawText = document.getRawText();
        int textLength = rawText == null ? 0 : rawText.length();
        int unitStart = window == null ? 0 : window.start();
        int unitEnd = window == null ? textLength : window.end();
        SourcePageResolver.Resolution resolution = SourcePageResolver.resolve(
                rawText, document.getPageOffsets(), unitStart, unitEnd,
                sourceSnippet);
        EvidenceKind evidenceKind = resolution.grounded() ? EvidenceKind.TEXT : EvidenceKind.UNSUPPORTED;
        return new ResolvedSource(resolution, evidenceKind, sourceSnippet);
    }

    private DirectSourceResolution resolveDirectSource(
            Document document, Window unit, SourceLineSelection selection,
            List<PageDescriptionService.FigureDescription> figures) {
        String rawText = document.getRawText();
        if (rawText != null && unit != null && selection.startLine() != null && selection.endLine() != null) {
            int unitStart = Math.max(0, Math.min(unit.start(), rawText.length()));
            int unitEnd = Math.max(unitStart, Math.min(unit.end(), rawText.length()));
            String sessionText = rawText.substring(unitStart, unitEnd);
            NumberedLines numberedLines = NumberedLines.of(sessionText);
            Optional<NumberedLines.Span> span = numberedLines.span(selection.startLine(), selection.endLine());
            if (span.isPresent()) {
                int matchStart = unitStart + span.get().start();
                int matchEnd = unitStart + span.get().end();
                Integer page = SourcePageResolver.pageForOffset(document.getPageOffsets(), matchStart).orElse(null);
                return new DirectSourceResolution(
                        new SourcePageResolver.Resolution(page, SourceMatchQuality.EXACT_IN_SESSION,
                                matchStart, matchEnd), EvidenceKind.TEXT);
            }
            log.info("Rejected source line selection [{}..{}] in document {}: {}",
                    selection.startLine(), selection.endLine(), document.getId(),
                    numberedLines.rejectionReason(selection.startLine(), selection.endLine()));
        } else {
            log.info("Rejected source line selection [{}..{}] in document {}: selection incomplete",
                    selection.startLine(), selection.endLine(), document.getId());
        }

        if (selection.figure() != null && selection.figure() >= 0 && selection.figure() < figures.size()) {
            PageDescriptionService.FigureDescription figure = figures.get(selection.figure());
            return new DirectSourceResolution(
                    new SourcePageResolver.Resolution(figure.page(), SourceMatchQuality.NONE, null, null),
                    EvidenceKind.FIGURE);
        }
        if (selection.figure() != null) {
            log.info("Rejected source figure index {} in document {}: outside the {} offered figure descriptions",
                    selection.figure(), document.getId(), figures.size());
        }
        return new DirectSourceResolution(noneResolution(), EvidenceKind.UNSUPPORTED);
    }

    private static SourcePageResolver.Resolution noneResolution() {
        return new SourcePageResolver.Resolution(null, SourceMatchQuality.NONE, null, null);
    }

    /** The quoted text of a resolved source: the grounded span of the raw text, else the model's snippet. */
    public static String snippetOf(Document document, ResolvedSource source) {
        String rawText = document.getRawText();
        SourcePageResolver.Resolution resolution = source.resolution();
        if (resolution.grounded() && resolution.matchStart() != null && resolution.matchEnd() != null
                && rawText != null && resolution.matchStart() >= 0
                && resolution.matchStart() <= resolution.matchEnd()
                && resolution.matchEnd() <= rawText.length()) {
            return rawText.substring(resolution.matchStart(), resolution.matchEnd());
        }
        return source.modelSnippet();
    }

    /** Stores a goal's source, with highlight geometry when the quote is grounded in a PDF page. */
    public void persistSource(LearningGoal goal, Document document, ResolvedSource source, PdfCache pdfCache) {
        SourcePageResolver.Resolution resolution = source.resolution();
        String persistedSnippet = snippetOf(document, source);
        GoalSource goalSource = source.evidenceKind() == EvidenceKind.FIGURE
                ? GoalSource.figure(goal, document, resolution.page())
                : new GoalSource(goal, document, persistedSnippet, resolution.page(), resolution.quality());
        if (resolution.grounded() && resolution.page() != null
                && resolution.matchStart() != null && resolution.matchEnd() != null
                && document.getPageOffsets() != null
                && resolution.page() >= 1
                && resolution.page() < document.getPageOffsets().length) {
            PDDocument pdf = pdfCache.open(document);
            if (pdf != null) {
                int pageStart = document.getPageOffsets()[resolution.page() - 1];
                int pageLocalStart = resolution.matchStart() - pageStart;
                int pageLocalEnd = resolution.matchEnd() - pageStart;
                try {
                    List<HighlightRect> rects = highlightGeometryService.findHighlightRects(
                            pdf, resolution.page(), pageLocalStart, pageLocalEnd);
                    // An empty result is "no geometry", not "highlight nothing": leave the
                    // column null so the client can fall back to its own text match.
                    goalSource.setHighlightRects(rects.isEmpty() ? null : rects);
                } catch (IOException | RuntimeException geometryFailure) {
                    log.warn("Could not compute source highlight geometry for document {}: {}",
                            document.getId(), geometryFailure.getMessage());
                }
            }
        }
        goalSourceRepository.save(goalSource);
    }

    /** Opens each document's PDF at most once while sources are persisted; close it when done. */
    public PdfCache pdfCache() {
        return new PdfCache(documentContentRepository);
    }

    /** The figure descriptions whose pages fall inside a window. */
    public static List<PageDescriptionService.FigureDescription> figureDescriptionsFor(
            Document document, Window unit, List<PageDescriptionService.FigureDescription> descriptions) {
        if (descriptions.isEmpty()) {
            return List.of();
        }
        if (unit.startPage() != null && unit.endPage() != null) {
            return descriptions.stream()
                    .filter(d -> d.page() >= unit.startPage() && d.page() <= unit.endPage())
                    .toList();
        }
        int[] pageOffsets = document.getPageOffsets();
        String rawText = document.getRawText();
        if (pageOffsets == null || pageOffsets.length < 2 || rawText == null) {
            return List.of();
        }
        int start = Math.max(0, Math.min(unit.start(), rawText.length()));
        int end = Math.max(start, Math.min(unit.end(), rawText.length()));
        return descriptions.stream()
                .filter(description -> pageOverlaps(description.page(), pageOffsets, start, end))
                .toList();
    }

    private static boolean pageOverlaps(int page, int[] pageOffsets, int sectionStart, int sectionEnd) {
        if (page < 1 || page >= pageOffsets.length) {
            return false;
        }
        int pageStart = pageOffsets[page - 1];
        int pageEnd = pageOffsets[page];
        return pageEnd > sectionStart && pageStart < sectionEnd
                || pageStart == pageEnd && pageStart == sectionStart;
    }

    /** The raw-text range [start, end) of one extraction unit, with its page range when it has one. */
    public record Window(int start, int end, Integer startPage, Integer endPage) {
    }

    /** One unit ready for extraction: its window's text and the figures on its pages. */
    public record SessionUnit<T>(T owner, Document document, Window window, String title, String text,
                                 List<PageDescriptionService.FigureDescription> figures) {
    }

    /** One extraction unit's skills, still keyed to the unit whose text they were read from. */
    public record SessionExtraction<T>(SessionUnit<T> session, List<ExtractedSkill> skills) {

        /** A session whose extraction failed: it contributes nothing. */
        public static <T> SessionExtraction<T> empty(SessionUnit<T> session) {
            return new SessionExtraction<>(session, List.of());
        }
    }

    /** One unit's goals in reading order. */
    public record UnitExtraction<T>(SessionUnit<T> session, List<UnitGoal> goals) {
    }

    /**
     * One extracted goal: a skill, or a knowledge item with the skill it belongs to.
     *
     * @param classification the levels extraction returned with the goal.
     */
    public record UnitGoal(ExtractedGoal extracted, GoalRole role, ExtractedGoal parentSkill,
                           SourceLineSelection sourceLineSelection,
                           TaxonomyClassification classification) {
    }

    public record SourceLineSelection(Integer startLine, Integer endLine, Integer figure) {
    }

    private record DirectSourceResolution(SourcePageResolver.Resolution resolution,
                                          EvidenceKind evidenceKind) {
    }

    /**
     * Where a goal's evidence was found.
     *
     * @param modelSnippet the snippet to store when the quote is not grounded; empty for line selections.
     */
    public record ResolvedSource(SourcePageResolver.Resolution resolution, EvidenceKind evidenceKind,
                                 String modelSnippet) {
    }

    /** PDFs opened for highlight geometry, each loaded at most once. */
    public static final class PdfCache implements AutoCloseable {

        private final DocumentContentRepository documentContentRepository;
        private final Map<Long, PDDocument> pdfDocuments = new HashMap<>();
        private final Set<Long> attemptedPdfDocuments = new HashSet<>();

        private PdfCache(DocumentContentRepository documentContentRepository) {
            this.documentContentRepository = documentContentRepository;
        }

        PDDocument open(Document document) {
            Long documentId = document.getId();
            if (!attemptedPdfDocuments.add(documentId)) {
                return pdfDocuments.get(documentId);
            }
            if (!isPdf(document)) {
                pdfDocuments.put(documentId, null);
                return null;
            }
            try {
                byte[] bytes = documentContentRepository.findById(documentId)
                        .map(content -> content.getBytes())
                        .orElse(null);
                if (bytes == null) {
                    log.debug("No PDF bytes available for document {}, skipping source geometry", documentId);
                    pdfDocuments.put(documentId, null);
                    return null;
                }
                PDDocument pdf = Loader.loadPDF(bytes);
                pdfDocuments.put(documentId, pdf);
                return pdf;
            } catch (IOException | RuntimeException loadFailure) {
                log.warn("Could not open PDF document {} for source geometry: {}",
                        documentId, loadFailure.getMessage());
                pdfDocuments.put(documentId, null);
                return null;
            }
        }

        @Override
        public void close() {
            for (PDDocument pdf : pdfDocuments.values()) {
                if (pdf == null) {
                    continue;
                }
                try {
                    pdf.close();
                } catch (IOException closeFailure) {
                    log.warn("Could not close PDF document used for source geometry: {}",
                            closeFailure.getMessage());
                }
            }
        }

        private static boolean isPdf(Document document) {
            return (document.getContentType() != null
                    && document.getContentType().toLowerCase(Locale.ROOT).contains("pdf"))
                    || (document.getFilename() != null
                    && document.getFilename().toLowerCase(Locale.ROOT).endsWith(".pdf"));
        }
    }
}
