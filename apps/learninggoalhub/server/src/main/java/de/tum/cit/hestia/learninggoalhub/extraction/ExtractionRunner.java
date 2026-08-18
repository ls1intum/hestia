package de.tum.cit.hestia.learninggoalhub.extraction;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import de.tum.cit.hestia.learninggoalhub.document.Document;
import de.tum.cit.hestia.learninggoalhub.document.DocumentContent;
import de.tum.cit.hestia.learninggoalhub.document.DocumentContentRepository;
import de.tum.cit.hestia.learninggoalhub.document.DocumentRepository;
import de.tum.cit.hestia.learninggoalhub.document.DocumentSection;
import de.tum.cit.hestia.learninggoalhub.document.DocumentSectionRepository;
import de.tum.cit.hestia.learninggoalhub.document.HighlightGeometryService;
import de.tum.cit.hestia.learninggoalhub.document.HighlightRect;
import de.tum.cit.hestia.learninggoalhub.document.LanguageUtils;
import de.tum.cit.hestia.learninggoalhub.document.PageDescription;
import de.tum.cit.hestia.learninggoalhub.document.PageDescriptionRepository;
import de.tum.cit.hestia.learninggoalhub.document.PageDescriptionService;
import de.tum.cit.hestia.learninggoalhub.embedding.EmbeddingService;
import de.tum.cit.hestia.learninggoalhub.goal.BloomLevel;
import de.tum.cit.hestia.learninggoalhub.goal.GoalKind;
import de.tum.cit.hestia.learninggoalhub.goal.GoalOrigin;
import de.tum.cit.hestia.learninggoalhub.goal.GoalRole;
import de.tum.cit.hestia.learninggoalhub.goal.EvidenceKind;
import de.tum.cit.hestia.learninggoalhub.goal.GoalSource;
import de.tum.cit.hestia.learninggoalhub.goal.GoalSourceId;
import de.tum.cit.hestia.learninggoalhub.goal.GoalSourceRepository;
import de.tum.cit.hestia.learninggoalhub.goal.GoalStatus;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoal;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoalRepository;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyLevel;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNode;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNodeRepository;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationship;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationshipRepository;
import de.tum.cit.hestia.learninggoalhub.relationships.RelationshipOrigin;
import de.tum.cit.hestia.learninggoalhub.relationships.RelationshipType;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyClassification;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.HashSet;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ExtractionRunner {

    private static final Logger log = LoggerFactory.getLogger(ExtractionRunner.class);
    private static final String FALLBACK_PROMPT_VERSION = "chunked-v5";

    private final CourseRepository courseRepository;
    private final DocumentRepository documentRepository;
    private final DocumentContentRepository documentContentRepository;
    private final PageDescriptionService pageDescriptionService;
    private final PageDescriptionRepository pageDescriptionRepository;
    private final LearningGoalRepository goalRepository;
    private final GoalSourceRepository goalSourceRepository;
    private final GoalRelationshipRepository goalRelationshipRepository;
    private final ExtractionService extractionService;
    private final SessionExtractionService sessionExtractionService;
    private final SessionGoalConsolidator sessionGoalConsolidator;
    private final ExtractionRunAuditService extractionRunAuditService;
    private final GoalCandidateRepository goalCandidateRepository;
    private final DocumentSectionRepository documentSectionRepository;
    private final TerminalCompetencySynthesizer terminalCompetencySynthesizer;
    private final CompetencyAssignmentSynthesizer competencyAssignmentSynthesizer;
    private final DocumentChunker documentChunker;
    private final HierarchyNodeRepository hierarchyNodeRepository;
    private final TaxonomyService taxonomyService;
    private final EmbeddingService embeddingService;
    private final ExtractionProgressTracker progressTracker;
    private final int parallelism;
    private final int figureParallelism;
    private final int directMaxChars;
    private final String configuredDefaultModel;
    private final int taxonomyBatchSize;
    private final int embeddingBatchSize;
    private final HighlightGeometryService highlightGeometryService;

    public ExtractionRunner(CourseRepository courseRepository,
                            DocumentRepository documentRepository,
                            DocumentContentRepository documentContentRepository,
                            PageDescriptionService pageDescriptionService,
                            PageDescriptionRepository pageDescriptionRepository,
                            LearningGoalRepository goalRepository,
                            GoalSourceRepository goalSourceRepository,
                            GoalRelationshipRepository goalRelationshipRepository,
                            ExtractionService extractionService,
                            SessionExtractionService sessionExtractionService,
                            SessionGoalConsolidator sessionGoalConsolidator,
                            ExtractionRunAuditService extractionRunAuditService,
                            GoalCandidateRepository goalCandidateRepository,
                            DocumentSectionRepository documentSectionRepository,
                            TerminalCompetencySynthesizer terminalCompetencySynthesizer,
                            CompetencyAssignmentSynthesizer competencyAssignmentSynthesizer,
                            DocumentChunker documentChunker,
                            HierarchyNodeRepository hierarchyNodeRepository,
                            TaxonomyService taxonomyService,
                            EmbeddingService embeddingService,
                            ExtractionProgressTracker progressTracker,
                            @Value("${hestia.extraction.parallelism:8}") int parallelism,
                            @Value("${hestia.figures.parallelism:4}") int figureParallelism,
                            @Value("${hestia.extraction.direct-max-chars:80000}") int directMaxChars,
                            @Value("${spring.ai.openai.chat.options.model:}") String configuredDefaultModel,
                            @Value("${hestia.taxonomy.batch-size:20}") int taxonomyBatchSize,
                            @Value("${hestia.embedding.batch-size:64}") int embeddingBatchSize,
                            HighlightGeometryService highlightGeometryService) {
        this.courseRepository = courseRepository;
        this.documentRepository = documentRepository;
        this.documentContentRepository = documentContentRepository;
        this.pageDescriptionService = pageDescriptionService;
        this.pageDescriptionRepository = pageDescriptionRepository;
        this.goalRepository = goalRepository;
        this.goalSourceRepository = goalSourceRepository;
        this.goalRelationshipRepository = goalRelationshipRepository;
        this.extractionService = extractionService;
        this.sessionExtractionService = sessionExtractionService;
        this.sessionGoalConsolidator = sessionGoalConsolidator;
        this.extractionRunAuditService = extractionRunAuditService;
        this.goalCandidateRepository = goalCandidateRepository;
        this.documentSectionRepository = documentSectionRepository;
        this.terminalCompetencySynthesizer = terminalCompetencySynthesizer;
        this.competencyAssignmentSynthesizer = competencyAssignmentSynthesizer;
        this.documentChunker = documentChunker;
        this.hierarchyNodeRepository = hierarchyNodeRepository;
        this.taxonomyService = taxonomyService;
        this.embeddingService = embeddingService;
        this.progressTracker = progressTracker;
        this.parallelism = parallelism;
        this.figureParallelism = figureParallelism;
        this.directMaxChars = directMaxChars;
        this.configuredDefaultModel = configuredDefaultModel;
        this.taxonomyBatchSize = taxonomyBatchSize;
        this.embeddingBatchSize = embeddingBatchSize;
        this.highlightGeometryService = highlightGeometryService;
    }

    @Transactional
    public ExtractionSummary runForCourse(Long courseId) {
        return runForCourse(courseId, null, false);
    }

    @Transactional
    public ExtractionSummary runForCourse(Long courseId, String modelOverride) {
        return runForCourse(courseId, modelOverride, false);
    }

    @Transactional
    public ExtractionSummary runForCourse(Long courseId, String modelOverride, boolean force) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found: " + courseId));

        boolean hasExtractedGoals = !goalRepository.findByCourseIdAndOriginIn(
                courseId, List.of(GoalOrigin.EXTRACTED)).isEmpty();
        boolean hasExtractionHierarchy = hierarchyNodeRepository.existsByCourseIdAndLevel(
                courseId, HierarchyLevel.MODULE)
                || hierarchyNodeRepository.existsByCourseIdAndLevel(courseId, HierarchyLevel.COMPETENCY);
        if ((hasExtractedGoals || hasExtractionHierarchy) && !force) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A re-extraction replaces the existing extraction artefacts; pass force=true to discard them.");
        }
        if (force) {
            clearExtractionArtifacts(course);
        }

        List<Document> documents = documentRepository.findByCourseId(courseId);
        String dominantLanguage = dominantLanguage(documents);
        String courseLanguage = resolveLanguage(course, null, dominantLanguage);
        String promptVersion = promptVersionFor(documents);
        String effectiveModel = modelOverride == null || modelOverride.isBlank()
                ? configuredDefaultModel : modelOverride;
        if (effectiveModel != null && effectiveModel.isBlank()) {
            effectiveModel = null;
        }
        Long auditRunId = extractionRunAuditService.start(courseId, effectiveModel, promptVersion,
                runParams(courseLanguage, course.isFiguresEnabled()));
        // Only the per-phase counters are published from in here. The terminal SUCCEEDED/FAILED
        // status is set by the caller once this transaction has committed — marking it from inside
        // would let a poller see "done" while the goals are still invisible to other connections.
        ExtractionProgressTracker.Run run = progressTracker.start(courseId, modelOverride);
        try {
            ExtractionSummary summary = doRun(course, documents, modelOverride,
                    LanguageUtils.englishName(courseLanguage), dominantLanguage, run);
            extractionRunAuditService.finish(auditRunId, ExtractionRun.Status.SUCCEEDED, null,
                    summary.goalsCreated(), run.failedSessions(), promptVersion);
            return summary;
        } catch (RuntimeException ex) {
            String error = errorMessage(ex);
            extractionRunAuditService.finish(auditRunId, ExtractionRun.Status.FAILED, error, null,
                    run.failedSessions(), promptVersion);
            throw ex;
        }
    }

    private ExtractionSummary doRun(Course course, List<Document> documents, String modelOverride,
                                    String courseLanguageName, String dominantLanguage,
                                    ExtractionProgressTracker.Run run) {
        Map<Long, List<PageDescriptionService.FigureDescription>> figuresByDocument;
        if (course.isFiguresEnabled()) {
            // Documents are described concurrently, but at a much lower width than the text phases: each
            // call carries several rendered pages, so the provider rejects a wide burst of them, and every
            // document opens its own transaction for the commit-per-document guarantee.
            run.phase(ExtractionProgressTracker.Phase.DESCRIBING_FIGURES, documents.size());
            ExecutorService figureExecutor = Executors.newFixedThreadPool(Math.max(1, figureParallelism));
            try {
                List<CompletableFuture<Void>> futures = documents.stream()
                        .map(document -> CompletableFuture.runAsync(() -> {
                            try {
                                documentContentRepository.findById(document.getId())
                                        .map(DocumentContent::getBytes)
                                        .ifPresent(bytes -> {
                                            String languageCode = resolveLanguage(
                                                    course, document.getLanguage(), dominantLanguage);
                                            pageDescriptionService.describeEligiblePages(document, bytes,
                                                    languageCode, LanguageUtils.englishName(languageCode));
                                        });
                            } catch (RuntimeException e) {
                                log.warn("Could not prepare figure descriptions for document {}: {}",
                                        document.getId(), e.getMessage());
                            }
                            run.increment();
                        }, figureExecutor))
                        .toList();
                futures.forEach(CompletableFuture::join);
            } finally {
                figureExecutor.shutdown();
            }
            // Pages the model marked as carrying no subject matter of their own (title slides, section
            // headers, agendas, blank answer pages) stay stored but are never offered as evidence.
            figuresByDocument = documents.stream()
                    .collect(Collectors.toMap(Document::getId,
                            document -> pageDescriptionRepository.findByDocumentId(document.getId()).stream()
                                    .filter(PageDescription::isTeachesContent)
                                    .sorted(Comparator.comparingInt(PageDescription::getPage))
                                    .map(description -> new PageDescriptionService.FigureDescription(
                                            description.getPage(), description.getDescription()))
                                    .toList()));
        } else {
            figuresByDocument = Map.of();
        }

        // Structural pass: turn each document into its sessions, materialized as hierarchy nodes under
        // one module root. Sessions come from the document's persisted structural sections (PDF
        // bookmarks, detected deterministically at upload); a document with none is one session.
        run.phase(ExtractionProgressTracker.Phase.OUTLINING, documents.size());
        HierarchyNode moduleRoot = hierarchyNodeRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.MODULE, course.getName()));
        Map<Long, List<Unit>> unitsByDocument = new HashMap<>();
        for (Document d : documents) {
            unitsByDocument.put(d.getId(), buildUnits(course, moduleRoot, d));
            run.increment();
        }
        Map<Long, Unit> unitsByNode = new HashMap<>();
        for (List<Unit> units : unitsByDocument.values()) {
            for (Unit unit : units) {
                unitsByNode.put(unit.node().getId(), unit);
            }
        }

        // Each session gets the complete text range of its structural node. The extraction phase below
        // chooses the direct or legacy chunked path for each session independently.
        run.phase(ExtractionProgressTracker.Phase.PARSING, documents.size());
        List<SessionUnit> sessions = new ArrayList<>();
        for (Document d : documents) {
            String text = d.getRawText();
            if (text != null && !text.isBlank()) {
                for (Unit unit : unitsByDocument.getOrDefault(d.getId(), List.of())) {
                    String unitText = text.substring(unit.start(), Math.min(unit.end(), text.length()));
                    if (!unitText.isBlank()) {
                        sessions.add(new SessionUnit(d, unit.node(), unit.node().getLabel(), unitText,
                                figureDescriptionsFor(d, unit,
                                        figuresByDocument.getOrDefault(d.getId(), List.of()))));
                    }
                }
            }
            run.increment();
        }

        run.phase(ExtractionProgressTracker.Phase.EXTRACTING, sessions.size());
        List<SessionExtraction> extractedSessions = extractSessions(
                course, sessions, dominantLanguage, modelOverride, run);
        SessionAssembly assembly = assembleSessions(course, extractedSessions);

        List<ClassifiedGoal> classified = classifyInParallel(assembly.sessionGoals(), modelOverride, run);
        List<EnrichedGoal> enriched = embedInParallel(classified, run);

        run.phase(ExtractionProgressTracker.Phase.PERSISTING, enriched.size());
        int goalsCreated = 0;
        int textSources = 0;
        int figureSources = 0;
        int unsupportedSources = 0;
        Map<ExtractedGoal, LearningGoal> persistedGoals = new IdentityHashMap<>();
        Map<Long, PDDocument> pdfDocuments = new HashMap<>();
        Set<Long> attemptedPdfDocuments = new HashSet<>();
        try {
            for (EnrichedGoal eg : enriched) {
                ExtractedGoal e = eg.classified().extracted();
                Document document = eg.classified().document();

                LearningGoal goal = new LearningGoal(course, e.text(), e.kind());
                goal.setShortLabel(e.shortLabel());
                goal.setRole(eg.classified().role());
                HierarchyNode node = eg.classified().node();
                if (node != null) {
                    goal.setHierarchyNode(node);
                }
                if (eg.classified().classification() != null) {
                    goal.setBloomLevel(eg.classified().classification().bloom());
                    goal.setSoloLevel(eg.classified().classification().solo());
                }
                if (eg.embedding() != null) {
                    goal.setEmbedding(eg.embedding());
                }
                LearningGoal target = goalRepository.saveAndFlush(goal);
                persistedGoals.put(e, target);
                goalsCreated++;

                GoalSourceId sourceId = new GoalSourceId(target.getId(), document.getId());
                if (!goalSourceRepository.existsById(sourceId)) {
                    HierarchyNode sourceNode = eg.classified().node();
                    Unit unit = sourceNode == null ? null : unitsByNode.get(sourceNode.getId());
                    SourcePageResolver.Resolution resolution;
                    EvidenceKind evidenceKind;
                    String modelSnippet;
                    if (eg.classified().sourceLineSelection() != null) {
                        DirectSourceResolution direct = resolveDirectSource(document, unit,
                                eg.classified().sourceLineSelection(), eg.classified().figures());
                        resolution = direct.resolution();
                        evidenceKind = direct.evidenceKind();
                        modelSnippet = "";
                    } else {
                        String rawText = document.getRawText();
                        int textLength = rawText == null ? 0 : rawText.length();
                        int unitStart = unit == null ? 0 : unit.start();
                        int unitEnd = unit == null ? textLength : unit.end();
                        resolution = SourcePageResolver.resolve(
                                rawText, document.getPageOffsets(), unitStart, unitEnd,
                                e.sourceSnippet());
                        evidenceKind = resolution.grounded() ? EvidenceKind.TEXT : EvidenceKind.UNSUPPORTED;
                        modelSnippet = e.sourceSnippet();
                    }
                    persistGoalSource(target, document, modelSnippet, resolution, evidenceKind,
                            pdfDocuments, attemptedPdfDocuments);
                    switch (evidenceKind) {
                        case TEXT -> textSources++;
                        case FIGURE -> figureSources++;
                        case UNSUPPORTED -> unsupportedSources++;
                    }
                }

                // Only fallback outcomes have raw candidates to connect to their surfaced goal.
                List<GoalCandidate> supporters = assembly.provenance().get(e);
                if (supporters != null) {
                    for (GoalCandidate candidate : supporters) {
                        candidate.setConsolidatedGoal(target);
                        goalCandidateRepository.save(candidate);
                    }
                }
                run.increment();
            }
        } finally {
            closePdfDocuments(pdfDocuments);
        }

        for (EnrichedGoal eg : enriched) {
            ClassifiedGoal classifiedGoal = eg.classified();
            if (classifiedGoal.role() != GoalRole.KNOWLEDGE || classifiedGoal.parentSkill() == null) {
                continue;
            }
            LearningGoal knowledge = persistedGoals.get(classifiedGoal.extracted());
            LearningGoal skill = persistedGoals.get(classifiedGoal.parentSkill());
            if (knowledge != null && skill != null) {
                linkContributors(List.of(knowledge), skill);
            }
        }

        // Drop units the outline detected but the extraction routed no goals to (e.g. a session with
        // no extractable outcomes). Without this the tree carries empty phantom sections.
        pruneEmptyUnits(course);

        // Top-down competency view: a three-tier tree (terminal competency → sub-skill → knowledge)
        // under its own COMPETENCY root, with CONTRIBUTES_TO edges threading the tiers.
        run.phase(ExtractionProgressTracker.Phase.SYNTHESIZING, 1);
        CompetencyTreeResult competencyTree = buildCompetencyTree(course, modelOverride, courseLanguageName);

        return new ExtractionSummary(documents.size(), goalsCreated, competencyTree.competencies(),
                textSources, figureSources, unsupportedSources);
    }

    /** Runs one direct call per small session, or the complete legacy pipeline for oversized sessions. */
    private List<SessionExtraction> extractSessions(Course course, List<SessionUnit> sessions,
                                                     String dominantLanguage, String modelOverride,
                                                     ExtractionProgressTracker.Run run) {
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, parallelism));
        try {
            List<CompletableFuture<SessionExtraction>> futures = sessions.stream()
                    .map(session -> CompletableFuture.supplyAsync(() -> {
                        try {
                            return extractSession(course, session, dominantLanguage, modelOverride);
                        } catch (RuntimeException ex) {
                            // One unusable model reply costs its own session, not the run: the course
                            // keeps the outcomes of every other session and the empty unit is pruned.
                            // The count is carried to the review screen and the audit row, so a
                            // silently thinner tree is never mistaken for a complete one.
                            log.warn("Session extraction failed for '{}'; the session yields no outcomes",
                                    session.title(), ex);
                            run.sessionFailed();
                            return SessionExtraction.empty(session);
                        } finally {
                            run.increment();
                        }
                    }, executor))
                    .toList();
            return futures.stream().map(CompletableFuture::join).toList();
        } finally {
            executor.shutdown();
        }
    }

    private SessionExtraction extractSession(Course course, SessionUnit session, String dominantLanguage,
                                             String modelOverride) {
        String languageCode = resolveLanguage(course, session.document().getLanguage(), dominantLanguage);
        String languageName = LanguageUtils.englishName(languageCode);
        if (usesDirectPath(session.text())) {
            List<ExtractedSkill> skills = session.figures().isEmpty()
                    ? sessionExtractionService.extract(
                            session.title(), session.text(), languageCode, languageName,
                            modelOverride)
                    : sessionExtractionService.extract(
                            session.title(), session.text(), languageCode, languageName,
                            modelOverride, session.figures());
            if (skills != null && skills.size() > 7) {
                log.warn("Session '{}' returned {} skills, above the hard cap of seven; keeping them all",
                        session.title(), skills.size());
            }
            return SessionExtraction.direct(session, skills == null ? List.of() : skills);
        }

        // Oversized-session fallback intentionally remains flat and role-null; it keeps
        // the existing Bloom-based split for pre-V24 and threshold-routed sessions.
        List<ExtractedGoal> candidates = new ArrayList<>();
        for (String chunk : documentChunker.chunk(session.text())) {
            List<ExtractedGoal> extracted = extractionService.extract(
                    chunk, languageName, modelOverride);
            if (extracted != null) {
                candidates.addAll(extracted);
            }
        }
        List<ConsolidatedGoal> consolidated = candidates.isEmpty()
                ? List.of()
                : safeConsolidate(session.title(),
                        candidates.stream().map(ExtractedGoal::text).toList(),
                        languageName, modelOverride);
        return SessionExtraction.fallback(session, candidates, consolidated);
    }

    private boolean usesDirectPath(String text) {
        return directMaxChars > 0 && text.length() <= directMaxChars;
    }

    private String promptVersionFor(List<Document> documents) {
        if (directMaxChars <= 0) {
            return FALLBACK_PROMPT_VERSION;
        }
        for (Document document : documents) {
            String text = document.getRawText();
            if (text == null || text.isBlank()) {
                continue;
            }
            List<DocumentSection> sections = documentSectionRepository
                    .findByDocumentIdOrderByOrdinal(document.getId());
            if (sections.isEmpty() && usesDirectPath(text)) {
                return SessionExtractionService.PROMPT_VERSION;
            }
            for (DocumentSection section : sections) {
                int start = Math.max(0, Math.min(section.getStartOffset(), text.length()));
                int end = Math.max(start, Math.min(section.getEndOffset(), text.length()));
                if (start < end && usesDirectPath(text.substring(start, end))) {
                    return SessionExtractionService.PROMPT_VERSION;
                }
            }
        }
        return FALLBACK_PROMPT_VERSION;
    }

    private String runParams(String language, boolean figuresEnabled) {
        return "{\"chunk-size\":" + documentChunker.getChunkSize()
                + ",\"direct-max-chars\":" + directMaxChars
                + ",\"parallelism\":" + parallelism
                + ",\"output-language\":\"" + language + "\""
                + ",\"figures-enabled\":" + figuresEnabled
                + ",\"figure-prompt-version\":\"" + PageDescriptionService.FIGURE_PROMPT_VERSION + "\"}";
    }

    static String resolveLanguage(Course course, String documentLanguage, String dominantLanguage) {
        if (course != null && course.getOutputLanguage() != null) {
            return course.getOutputLanguage();
        }
        if (documentLanguage != null && !documentLanguage.isBlank()) {
            return documentLanguage;
        }
        if (dominantLanguage != null && !dominantLanguage.isBlank()) {
            return dominantLanguage;
        }
        return "en";
    }

    static String dominantLanguage(List<Document> documents) {
        Map<String, Long> weights = new LinkedHashMap<>();
        for (Document document : documents) {
            String language = document.getLanguage();
            if (language == null || language.isBlank()) {
                continue;
            }
            String text = document.getRawText();
            long weight = text == null ? 0 : text.length();
            if (weight > 0) {
                weights.merge(language, weight, Long::sum);
            }
        }
        String dominant = null;
        long highestWeight = -1;
        for (Map.Entry<String, Long> entry : weights.entrySet()) {
            if (entry.getValue() > highestWeight) {
                dominant = entry.getKey();
                highestWeight = entry.getValue();
            }
        }
        return dominant;
    }

    private static String errorMessage(RuntimeException ex) {
        Throwable cause = ex;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
            return cause.getMessage();
        }
        return cause.getClass().getSimpleName();
    }

    private SessionAssembly assembleSessions(Course course, List<SessionExtraction> extractedSessions) {
        List<ChunkExtraction> sessionGoals = new ArrayList<>();
        Map<ExtractedGoal, List<GoalCandidate>> provenance = new IdentityHashMap<>();
        for (SessionExtraction extraction : extractedSessions) {
            if (extraction.direct()) {
                List<SessionGoal> goals = new ArrayList<>();
                for (ExtractedSkill skill : extraction.skills()) {
                    ExtractedGoal skillGoal = new ExtractedGoal(
                            skill.text(), skill.shortLabel(), skill.kind(),
                            "");
                    goals.add(new SessionGoal(skillGoal, GoalRole.SKILL, null,
                            new SourceLineSelection(skill.sourceStartLine(), skill.sourceEndLine(),
                                    skill.sourceFigure())));
                    for (ExtractedSkill.Knowledge knowledge : skill.knowledge()) {
                        ExtractedGoal knowledgeGoal = new ExtractedGoal(
                                knowledge.text(), knowledge.shortLabel(), knowledge.kind(),
                                "");
                        goals.add(new SessionGoal(knowledgeGoal, GoalRole.KNOWLEDGE, skillGoal,
                                new SourceLineSelection(knowledge.sourceStartLine(), knowledge.sourceEndLine(),
                                        knowledge.sourceFigure())));
                    }
                }
                if (!goals.isEmpty()) {
                    sessionGoals.add(new ChunkExtraction(extraction.document(), extraction.node(),
                            goals, extraction.figures()));
                }
                continue;
            }

            List<GoalCandidate> saved = new ArrayList<>(extraction.candidates().size());
            for (ExtractedGoal c : extraction.candidates()) {
                saved.add(goalCandidateRepository.save(new GoalCandidate(course, extraction.node(), c.text(),
                        c.kind(), c.sourceSnippet())));
            }
            List<SessionGoal> outcomes = new ArrayList<>();
            for (ConsolidatedGoal cg : extraction.consolidated()) {
                if (cg.text() == null || cg.text().isBlank()) {
                    continue;
                }
                List<GoalCandidate> supporters = supportersFor(
                        cg.supporting() == null ? List.of() : cg.supporting(), saved);
                ExtractedGoal outcome = new ExtractedGoal(
                        cg.text(), cg.shortLabel(), deriveKind(supporters), snippetFor(supporters));
                outcomes.add(new SessionGoal(outcome, null, null, null));
                provenance.put(outcome, supporters);
            }
            if (!outcomes.isEmpty()) {
                sessionGoals.add(new ChunkExtraction(extraction.document(), extraction.node(), outcomes, List.of()));
            }
        }
        return new SessionAssembly(sessionGoals, provenance);
    }

    private List<ConsolidatedGoal> safeConsolidate(String sessionTitle, List<String> candidates,
                                                   String languageName, String modelOverride) {
        try {
            List<ConsolidatedGoal> result = sessionGoalConsolidator.consolidate(
                    sessionTitle, candidates, languageName, modelOverride);
            return result == null ? List.of() : result;
        } catch (RuntimeException ex) {
            log.warn("Session goal consolidation failed for '{}', keeping its candidates unconsolidated: {}",
                    sessionTitle, ex.getMessage());
            // Fall back to passing the raw candidates through as their own outcomes so nothing is lost.
            List<ConsolidatedGoal> fallback = new ArrayList<>(candidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                fallback.add(new ConsolidatedGoal(candidates.get(i), List.of(i)));
            }
            return fallback;
        }
    }

    /** Maps the synthesiser's supporting indices back to candidate entities, dropping out-of-range ones. */
    private static List<GoalCandidate> supportersFor(List<Integer> supporting, List<GoalCandidate> candidates) {
        List<GoalCandidate> result = new ArrayList<>();
        for (int index : supporting.stream().distinct().toList()) {
            if (index >= 0 && index < candidates.size()) {
                result.add(candidates.get(index));
            }
        }
        return result;
    }

    /** A consolidated goal is EXPLICIT when any candidate it was merged from was explicitly stated. */
    private static GoalKind deriveKind(List<GoalCandidate> supporters) {
        return supporters.stream().anyMatch(c -> c.getKind() == GoalKind.EXPLICIT)
                ? GoalKind.EXPLICIT : GoalKind.IMPLICIT;
    }

    /** Inherits a verbatim snippet from the first supporting candidate that has one; "" if none. */
    private static String snippetFor(List<GoalCandidate> supporters) {
        return supporters.stream()
                .map(GoalCandidate::getSourceSnippet)
                .filter(s -> s != null && !s.isBlank())
                .findFirst()
                .orElse("");
    }

    /** One session's direct result or its legacy candidates and reduced outcomes. */
    private record SessionExtraction(Document document, HierarchyNode node, boolean direct,
                                     List<ExtractedSkill> skills, List<ExtractedGoal> candidates,
                                     List<ConsolidatedGoal> consolidated,
                                     List<PageDescriptionService.FigureDescription> figures) {

        private static SessionExtraction direct(SessionUnit session, List<ExtractedSkill> skills) {
            return new SessionExtraction(session.document(), session.node(), true, skills, List.of(), List.of(),
                    session.figures());
        }

        private static SessionExtraction fallback(SessionUnit session, List<ExtractedGoal> candidates,
                                                  List<ConsolidatedGoal> consolidated) {
            return new SessionExtraction(session.document(), session.node(), false, List.of(), candidates,
                    consolidated, List.of());
        }

        /** A session whose extraction failed: it contributes nothing and its unit is pruned. */
        private static SessionExtraction empty(SessionUnit session) {
            return new SessionExtraction(session.document(), session.node(), true, List.of(), List.of(),
                    List.of(), List.of());
        }
    }

    private record SessionAssembly(List<ChunkExtraction> sessionGoals,
                                   Map<ExtractedGoal, List<GoalCandidate>> provenance) {
    }

    /**
     * Deletes SESSION/EXERCISE units that ended up with no goals. Only leaf units are removed and only when no goal
     * references them, so nothing is orphaned; the MODULE root is always kept as the tree's anchor.
     * The pruned units' goal candidates are
     * deleted first — they reference the node and, although the DB cascades on delete, Hibernate would
     * otherwise choke flushing those still-managed rows against a removed node.
     */
    private int pruneEmptyUnits(Course course) {
        Set<Long> nodesWithGoals = goalRepository.findByCourseIdAndHierarchyNodeIsNotNull(course.getId()).stream()
                .map(g -> g.getHierarchyNode().getId())
                .collect(Collectors.toSet());
        List<HierarchyNode> empty = hierarchyNodeRepository.findByCourseId(course.getId()).stream()
                .filter(n -> n.getLevel() != HierarchyLevel.MODULE)
                .filter(n -> !nodesWithGoals.contains(n.getId()))
                .toList();
        if (!empty.isEmpty()) {
            Set<Long> emptyIds = empty.stream().map(HierarchyNode::getId).collect(Collectors.toSet());
            List<GoalCandidate> orphanedCandidates = goalCandidateRepository.findByCourseId(course.getId()).stream()
                    .filter(c -> emptyIds.contains(c.getHierarchyNode().getId()))
                    .toList();
            goalCandidateRepository.deleteAll(orphanedCandidates);
            goalCandidateRepository.flush();
            hierarchyNodeRepository.deleteAll(empty);
            log.info("Pruned {} empty unit(s) from course {}", empty.size(), course.getId());
        }
        return empty.size();
    }

    /** Materializes idempotent CONTRIBUTES_TO edges between competency-tree goals. */
    private int linkContributors(Collection<LearningGoal> supporters, LearningGoal targetGoal) {
        int created = 0;
        for (LearningGoal source : supporters) {
            if (source.getId().equals(targetGoal.getId())) {
                continue;
            }
            if (goalRelationshipRepository.existsBySourceIdAndTargetIdAndType(
                    source.getId(), targetGoal.getId(), RelationshipType.CONTRIBUTES_TO)) {
                continue;
            }
            goalRelationshipRepository.save(new GoalRelationship(
                    source, targetGoal, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));
            created++;
        }
        return created;
    }

    /** Legacy Bloom fallback for role-null pre-V24 goals. */
    private static final Set<BloomLevel> SUB_SKILL_BLOOM =
            EnumSet.of(BloomLevel.APPLY, BloomLevel.ANALYZE, BloomLevel.EVALUATE, BloomLevel.CREATE);

    static boolean isSkillTier(LearningGoal goal) {
        return goal.getRole() == GoalRole.SKILL
                || (goal.getRole() == null && SUB_SKILL_BLOOM.contains(goal.getBloomLevel()));
    }

    /** How many terminal competencies the competency tree produced. */
    /**
     * What the competency tree came out as.
     *
     * @param competencies   how many terminal competencies were created, including the catch-all.
     * @param unmatchedGoals how many goals the assignment step placed under no competency and that
     *                       therefore sit in the catch-all. Zero is the healthy case; a rising number
     *                       says the named competencies do not cover the course.
     */
    public record CompetencyTreeResult(int competencies, int unmatchedGoals) {
        static final CompetencyTreeResult NONE = new CompetencyTreeResult(0, 0);
    }

    /**
     * Throws away a course's competency tree and builds a fresh one from the goals it already has,
     * without re-reading a single document. Only the three tree synthesis calls run, so iterating on
     * the tree costs a fraction of a full extraction.
     *
     * <p>Refuses by default once the tree contains instructor work — a hand-added skill, a
     * hand-added child, a generated subtree, or an approved terminal — because a rebuild replaces
     * exactly those nodes and nothing records what they were. {@code force} overrides that and
     * deletes them. Extracted session/exercise goals are never touched either way: the rebuild only
     * removes the terminals and the tree edges, so instructor edits and approvals on the goals
     * themselves survive.
     *
     * @return the freshly built tree, or {@link CompetencyTreeResult#NONE} when synthesis failed —
     *         in which case the course is left with no competency tree rather than the old one.
     */
    @Transactional
    public CompetencyTreeResult rebuildCompetencyTree(Long courseId, String modelOverride, boolean force) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found: " + courseId));

        List<LearningGoal> terminals = goalRepository.findByCourseIdAndOriginIn(courseId, List.of(GoalOrigin.TERMINAL));
        List<LearningGoal> manual = manualTreeGoals(courseId, terminals);
        if (!manual.isEmpty() && !force) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The competency tree contains " + manual.size() + " hand-made or approved node(s). "
                            + "Rebuilding replaces them; pass force=true to discard them.");
        }
        // Synthesise the replacement BEFORE destroying the old tree. Synthesis failures are swallowed
        // into an empty plan, so clearing first would let a transient model outage delete a course's
        // tree and leave nothing in its place.
        List<Document> documents = documentRepository.findByCourseId(courseId);
        String courseLanguage = resolveLanguage(course, null, dominantLanguage(documents));
        CompetencyTreePlan plan = planFullCompetencyTree(course, modelOverride,
                LanguageUtils.englishName(courseLanguage));
        if (plan == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not synthesise a new competency tree; the existing one was left untouched.");
        }

        clearCompetencyTree(course, terminals, manual);
        persistCompetencyTree(course, plan);
        log.info("Rebuilt competency tree for course {}: {} competencies, {} unmatched goal(s)",
                courseId, plan.competencies().size(), plan.unmatchedGoals());
        return new CompetencyTreeResult(plan.competencies().size(), plan.unmatchedGoals());
    }

    /**
     * The tree nodes a rebuild would destroy and cannot recreate: terminals an instructor typed or
     * approved, plus the hand-added and wizard-generated children hanging beneath the tree (those
     * carry a creation provenance and, unlike extracted goals, no hierarchy node of their own).
     */
    private List<LearningGoal> manualTreeGoals(Long courseId, List<LearningGoal> terminals) {
        List<LearningGoal> manual = new ArrayList<>();
        for (LearningGoal terminal : terminals) {
            if (terminal.getCreationProvenance() != null || terminal.getStatus() == GoalStatus.APPROVED) {
                manual.add(terminal);
            }
        }
        for (LearningGoal goal : goalRepository.findByCourseId(courseId)) {
            if (goal.getHierarchyNode() == null && goal.getCreationProvenance() != null) {
                manual.add(goal);
            }
        }
        return manual;
    }

    /**
     * Removes the terminals, the given manual nodes and the tree CONTRIBUTES_TO edges in the course,
     * then the now-empty {@code COMPETENCY} root. Extracted goals keep existing; the rebuild draws
     * their skill → terminal edges again.
     *
     * <p>Knowledge → skill edges are created during extraction and survive a tree rebuild. On a
     * pre-V24 course no goal has a role, so every edge is still deleted — identical to today's
     * behaviour, with no legacy multi-parent stacking. Hand-added ({@code USER_CREATED}) and wizard
     * ({@code WIZARD_AI_SUBTREE}) nodes also have no role, so their edges keep being cleared exactly
     * as today. Do not use "target is a TERMINAL" as the predicate: legacy knowledge → sub-skill
     * edges must not survive.
     */
    private void clearCompetencyTree(Course course, List<LearningGoal> terminals, List<LearningGoal> manual) {
        List<LearningGoal> doomed = new ArrayList<>(terminals);
        for (LearningGoal goal : manual) {
            if (doomed.stream().noneMatch(g -> g.getId().equals(goal.getId()))) {
                doomed.add(goal);
            }
        }
        // Load the edges before deleting anything: Hibernate keeps loaded edges managed and would
        // fail the flush against a removed goal.
        List<Long> courseGoalIds = goalRepository.findByCourseId(course.getId()).stream()
                .map(LearningGoal::getId)
                .toList();
        Set<Long> doomedIds = doomed.stream().map(LearningGoal::getId).collect(Collectors.toSet());
        clearRelationshipsForGoals(courseGoalIds, doomedIds, true);
        goalRepository.deleteAll(doomed);
        goalRepository.flush();

        List<HierarchyNode> competencyRoots = hierarchyNodeRepository.findByCourseId(course.getId()).stream()
                .filter(n -> n.getLevel() == HierarchyLevel.COMPETENCY)
                .toList();
        hierarchyNodeRepository.deleteAll(competencyRoots);
        hierarchyNodeRepository.flush();
    }

    /**
     * Clears every artefact owned by a previous full extraction while leaving uploaded documents in place.
     * The competency tree is cleared first through the same path as the standalone tree rebuild; the
     * remaining extracted goals then lose their sources, candidates, relationships and module hierarchy.
     */
    private void clearExtractionArtifacts(Course course) {
        List<LearningGoal> extracted = goalRepository.findByCourseIdAndOriginIn(
                course.getId(), List.of(GoalOrigin.EXTRACTED));
        List<Long> courseGoalIds = goalRepository.findByCourseId(course.getId()).stream()
                .map(LearningGoal::getId)
                .toList();
        Set<Long> extractedIds = extracted.stream().map(LearningGoal::getId).collect(Collectors.toSet());

        List<LearningGoal> terminals = goalRepository.findByCourseIdAndOriginIn(
                course.getId(), List.of(GoalOrigin.TERMINAL));
        clearCompetencyTree(course, terminals, manualTreeGoals(course.getId(), terminals));

        List<GoalCandidate> candidates = goalCandidateRepository.findByCourseId(course.getId());
        goalCandidateRepository.deleteAll(candidates);
        goalCandidateRepository.flush();

        if (!extracted.isEmpty()) {
            List<GoalSource> sources = goalSourceRepository.findByGoalIdIn(extractedIds);
            goalSourceRepository.deleteAll(sources);
            goalSourceRepository.flush();
            clearRelationshipsForGoals(courseGoalIds, extractedIds, false);
            goalRepository.deleteAll(extracted);
            goalRepository.flush();
        }

        List<HierarchyNode> moduleHierarchy = hierarchyNodeRepository.findByCourseId(course.getId()).stream()
                .filter(node -> node.getLevel() == HierarchyLevel.MODULE
                        || node.getLevel() == HierarchyLevel.SESSION
                        || node.getLevel() == HierarchyLevel.EXERCISE)
                .sorted(java.util.Comparator.comparing(HierarchyNode::getLevel).reversed())
                .toList();
        hierarchyNodeRepository.deleteAll(moduleHierarchy);
        hierarchyNodeRepository.flush();
    }

    /** Deletes loaded relationship entities before deleting any of their goals. */
    private void clearRelationshipsForGoals(Collection<Long> courseGoalIds, Set<Long> doomedIds,
                                             boolean allContributes) {
        List<GoalRelationship> edges = new ArrayList<>();
        for (GoalRelationship relationship : goalRelationshipRepository.findBySourceIdIn(courseGoalIds)) {
            boolean extractionEdge = relationship.getType() == RelationshipType.CONTRIBUTES_TO
                    && relationship.getSource().getRole() == GoalRole.KNOWLEDGE
                    && relationship.getTarget().getRole() == GoalRole.SKILL;
            boolean treeEdge = allContributes
                    && relationship.getType() == RelationshipType.CONTRIBUTES_TO
                    && !extractionEdge;
            boolean touchesDoomed = doomedIds.contains(relationship.getSource().getId())
                    || doomedIds.contains(relationship.getTarget().getId());
            if (treeEdge || touchesDoomed) {
                edges.add(relationship);
            }
        }
        // A doomed node may be the target of an edge whose source sits outside the course goal list.
        for (Long doomedId : doomedIds) {
            for (GoalRelationship relationship : goalRelationshipRepository.findByTargetId(doomedId)) {
                if (edges.stream().noneMatch(edge -> edge.getId().equals(relationship.getId()))) {
                    edges.add(relationship);
                }
            }
        }
        if (!edges.isEmpty()) {
            goalRelationshipRepository.deleteAll(edges);
            goalRelationshipRepository.flush();
        }
    }

    /**
     * Builds the competency-tree view ALONGSIDE the module goals (not a replacement) in a fixed three
     * tiers — terminal competency → sub-skill → knowledge — under its own {@code COMPETENCY} root.
     *
     * <p>Two course-wide synthesis calls run BEFORE anything is written: competencies are named
     * ({@link TerminalCompetencySynthesizer}) and every extracted skill is assigned to one of them
     * against the finished list ({@link CompetencyAssignmentSynthesizer}). Persisting only after all
     * calls succeed also means a failed call leaves the course untouched instead of half-built.
     *
     * <p>Terminal goals use batched taxonomy classification. Extraction already links knowledge →
     * skill; this stage links skills → terminal. Any synthesis failure is swallowed so it never
     * breaks a run.
     */
    private CompetencyTreeResult buildCompetencyTree(Course course, String modelOverride,
                                                     String languageName) {
        if (hierarchyNodeRepository.existsByCourseIdAndLevel(course.getId(), HierarchyLevel.COMPETENCY)) {
            return CompetencyTreeResult.NONE;
        }
        CompetencyTreePlan plan = planFullCompetencyTree(course, modelOverride, languageName);
        if (plan == null) {
            return CompetencyTreeResult.NONE;
        }
        persistCompetencyTree(course, plan);
        log.info("Built competency tree for course {}: {} terminal competencies, {} goal(s) matched no "
                        + "competency and sit under the catch-all",
                course.getId(), plan.competencies().size(), plan.unmatchedGoals());
        return new CompetencyTreeResult(plan.competencies().size(), plan.unmatchedGoals());
    }

    /**
     * Runs all synthesis for a course's competency tree and returns the finished plan, or
     * {@code null} when the tree cannot be built (no seeds, or a synthesis call failed). Touches
     * nothing in the database, so the caller decides when — and whether — to write.
     */
    private CompetencyTreePlan planFullCompetencyTree(Course course, String modelOverride,
                                                      String languageName) {
        // Only skill-tier session/exercise goals are tree candidates. Role is structural for V24+
        // data, while role-null legacy goals retain the Bloom fallback.
        List<LearningGoal> candidates = goalRepository.findByCourseIdAndHierarchyNodeIsNotNull(course.getId()).stream()
                .filter(g -> g.getHierarchyNode().getLevel() != HierarchyLevel.MODULE
                        && g.getHierarchyNode().getLevel() != HierarchyLevel.COMPETENCY)
                .filter(ExtractionRunner::isSkillTier)
                .toList();
        // The skill tier IS the seed set. Bloom must not narrow it any further: extraction now
        // decides the tier, and the session prompt deliberately keeps verbs low ("when in doubt,
        // prefer understand/know"), so a course whose skills all classify as UNDERSTAND would
        // otherwise produce no seeds and silently lose its whole competency tree. For role-null
        // legacy goals isSkillTier already means "high Bloom", so their seeds are unchanged.
        if (candidates.isEmpty()) {
            return null;
        }

        // Call 1 — name the competencies. Every candidate goes in, not just the seeds: a capability
        // carried only by ANALYZE/EVALUATE goals would otherwise never be named.
        List<TerminalCompetency> competencies;
        try {
            List<TerminalCompetencySynthesizer.Candidate> input = candidates.stream()
                    .map(g -> new TerminalCompetencySynthesizer.Candidate(
                            g.getText(), g.getBloomLevel() == null ? null : g.getBloomLevel().name()))
                    .toList();
            competencies = terminalCompetencySynthesizer.synthesize(input, languageName, modelOverride);
        } catch (RuntimeException ex) {
            log.warn("Terminal competency synthesis failed, continuing without a competency tree: {}",
                    ex.getMessage());
            return null;
        }
        if (competencies == null || competencies.isEmpty()) {
            return null;
        }

        // Drop unusable competencies BEFORE assigning, so the indices the model answers with cannot
        // point at a competency that later disappears.
        List<TerminalCompetency> usableCompetencies = competencies.stream()
                .filter(tc -> tc != null && tc.text() != null && !tc.text().isBlank())
                .toList();
        if (usableCompetencies.isEmpty()) {
            return null;
        }
        List<String> competencyTexts = usableCompetencies.stream().map(TerminalCompetency::text).toList();

        // Call 2 — assign every goal against the complete competency list.
        Map<Integer, Integer> assignment;
        try {
            List<CompetencyAssignmentSynthesizer.Candidate> input = candidates.stream()
                    .map(g -> new CompetencyAssignmentSynthesizer.Candidate(
                            g.getText(),
                            g.getBloomLevel() == null ? null : g.getBloomLevel().name(),
                            g.getHierarchyNode() == null ? null : g.getHierarchyNode().getLabel()))
                    .toList();
            assignment = competencyAssignmentSynthesizer.assign(competencyTexts, input, modelOverride);
        } catch (RuntimeException ex) {
            log.warn("Competency assignment failed, continuing without a competency tree: {}",
                    ex.getMessage());
            return null;
        }
        if (assignment.isEmpty()) {
            // Without a single placement the tree would be one bucket holding everything, which is
            // worse than no tree at all.
            log.warn("Competency assignment returned no placements for course {}, skipping the tree",
                    course.getId());
            return null;
        }

        return planCompetencyTree(candidates, usableCompetencies, assignment, modelOverride, languageName);
    }

    /**
     * The finished competency tree, computed entirely in memory so that every LLM call has already
     * succeeded before the first row is written.
     *
     * @param competencies   one entry per terminal competency, in tree order.
     * @param unmatchedGoals how many goals the assignment call placed under no competency and that
     *                       therefore ended up in the catch-all. This is the quality signal for the
     *                       assignment step: a healthy run leaves it at zero.
     */
    private record CompetencyTreePlan(List<PlannedCompetency> competencies, int unmatchedGoals) {}

    /**
     * One terminal competency with everything that hangs beneath it.
     *
     * @param text           the competency sentence.
     * @param shortLabel     its compact verb phrase, or {@code null}.
     * @param classification its Bloom/SOLO levels, or {@code null} to persist it unclassified. The
     *                       catch-all is a container, not a capability, so it stays unclassified
     *                       rather than carrying a meaningless Bloom level.
     * @param subSkills      the skill goals assigned to this competency, in order.
     */
    private record PlannedCompetency(String text, String shortLabel, TaxonomyClassification classification,
                                     List<LearningGoal> subSkills) {}

    /**
     * Turns the per-skill assignment into the tree's shape. Goals the assignment left unplaced are
     * gathered into a single catch-all competency rather than force-fitted under a competency they do not serve —
     * the client only renders goals reachable from a terminal, so dropping them would make them
     * invisible, and picking a "nearest" terminal would just restate the force-fit this whole split
     * exists to remove.
     */
    private CompetencyTreePlan planCompetencyTree(List<LearningGoal> candidates,
                                                  List<TerminalCompetency> competencies,
                                                  Map<Integer, Integer> assignment,
                                                  String modelOverride, String languageName) {
        List<List<LearningGoal>> goalsByCompetency = new ArrayList<>();
        for (int i = 0; i < competencies.size(); i++) {
            goalsByCompetency.add(new ArrayList<>());
        }
        List<LearningGoal> unmatched = new ArrayList<>();
        for (int goalIndex = 0; goalIndex < candidates.size(); goalIndex++) {
            Integer competencyIndex = assignment.get(goalIndex);
            if (competencyIndex == null) {
                // Either an explicit "fits none" or a goal the model never answered for.
                unmatched.add(candidates.get(goalIndex));
                continue;
            }
            goalsByCompetency.get(competencyIndex).add(candidates.get(goalIndex));
        }

        // The catch-all only exists when it holds something, so a clean run shows no trace of it.
        List<String> texts = new ArrayList<>(competencies.stream().map(TerminalCompetency::text).toList());
        List<String> labels = new ArrayList<>();
        for (TerminalCompetency tc : competencies) {
            labels.add(tc.shortLabel());
        }
        int classifiableCount = competencies.size();
        if (!unmatched.isEmpty()) {
            goalsByCompetency.add(unmatched);
            texts.add(catchAllText(languageName));
            labels.add(catchAllLabel(languageName));
        }

        // Call 4 — classify the real competencies. Still before any write, so a taxonomy failure
        // cannot leave a half-written tree behind; it only costs the levels.
        List<TaxonomyClassification> classifications =
                safeClassifyBatch(texts.subList(0, classifiableCount), modelOverride);

        List<PlannedCompetency> planned = new ArrayList<>();
        for (int ci = 0; ci < goalsByCompetency.size(); ci++) {
            if (goalsByCompetency.get(ci).isEmpty()) {
                // The assignment gave this competency nothing, so the course does not actually build
                // toward it. Persisting it would put a childless node at the top of the tree.
                continue;
            }
            List<LearningGoal> subSkills = goalsByCompetency.get(ci);
            planned.add(new PlannedCompetency(texts.get(ci), labels.get(ci),
                    ci < classifiableCount ? classifications.get(ci) : null,
                    subSkills));
        }
        return new CompetencyTreePlan(planned, unmatched.size());
    }

    /**
     * Writes a planned tree: the {@code COMPETENCY} root, one terminal goal per competency, and the
     * skill → terminal CONTRIBUTES_TO edges. Knowledge → skill edges were created during extraction.
     * All LLM work is already done by the time this runs.
     */
    private void persistCompetencyTree(Course course, CompetencyTreePlan plan) {
        HierarchyNode competencyRoot = hierarchyNodeRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.COMPETENCY, "Terminal Competencies"));

        List<LearningGoal> terminalGoals = new ArrayList<>();
        for (PlannedCompetency competency : plan.competencies()) {
            LearningGoal goal = new LearningGoal(course, competency.text(), GoalKind.IMPLICIT);
            goal.setShortLabel(competency.shortLabel());
            goal.setOrigin(GoalOrigin.TERMINAL);
            goal.setHierarchyNode(competencyRoot);
            if (competency.classification() != null) {
                goal.setBloomLevel(competency.classification().bloom());
                goal.setSoloLevel(competency.classification().solo());
            }
            goalRepository.saveAndFlush(goal);
            terminalGoals.add(goal);
        }

        for (int ci = 0; ci < plan.competencies().size(); ci++) {
            PlannedCompetency competency = plan.competencies().get(ci);
            LearningGoal terminal = terminalGoals.get(ci);
            linkContributors(competency.subSkills(), terminal);
        }
    }

    /** Sentence for the catch-all terminal that collects goals matching no competency. */
    private static String catchAllText(String languageName) {
        return "German".equals(languageName)
                ? "Weitere Lernziele dieses Kurses."
                : "Further learning goals of this course.";
    }

    /** Short label for the catch-all terminal. */
    private static String catchAllLabel(String languageName) {
        return "German".equals(languageName) ? "Weitere Kursziele" : "Additional Course Outcomes";
    }

    /**
     * Classifies every extracted goal along Bloom + SOLO. Goals are flattened into one ordered list
     * and grouped into fixed-size batches ({@code hestia.taxonomy.batch-size}); each batch is a single
     * LLM call and the batches run in parallel. Batching instead of one call per goal cuts request
     * count (and rate-limit pressure) and lets the model grade goals relative to each other.
     */
    private List<ClassifiedGoal> classifyInParallel(List<ChunkExtraction> extractions, String modelOverride,
                                                    ExtractionProgressTracker.Run run) {
        // Flatten goals while remembering each one's owning chunk, so classifications map back to the
        // right document + hierarchy node after the (order-preserving) batch calls.
        List<ChunkExtraction> owners = new ArrayList<>();
        List<SessionGoal> goals = new ArrayList<>();
        for (ChunkExtraction de : extractions) {
            for (SessionGoal e : de.goals()) {
                owners.add(de);
                goals.add(e);
            }
        }
        run.phase(ExtractionProgressTracker.Phase.CLASSIFYING, goals.size());
        if (goals.isEmpty()) {
            return List.of();
        }

        int batchSize = Math.max(1, taxonomyBatchSize);
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, parallelism));
        try {
            List<CompletableFuture<List<TaxonomyClassification>>> futures = new ArrayList<>();
            for (int start = 0; start < goals.size(); start += batchSize) {
                int from = start;
                int to = Math.min(start + batchSize, goals.size());
                List<String> texts = goals.subList(from, to).stream()
                        .map(e -> e.extracted().text()).toList();
                futures.add(CompletableFuture.supplyAsync(
                        () -> {
                            List<TaxonomyClassification> result = safeClassifyBatch(texts, modelOverride);
                            run.increment(to - from);
                            return result;
                        },
                        executor));
            }

            List<ClassifiedGoal> classified = new ArrayList<>(goals.size());
            int i = 0;
            for (CompletableFuture<List<TaxonomyClassification>> future : futures) {
                List<TaxonomyClassification> batch = future.join();
                for (TaxonomyClassification c : batch) {
                    SessionGoal goal = goals.get(i);
                    classified.add(new ClassifiedGoal(owners.get(i).document(), owners.get(i).node(),
                            goal.extracted(), goal.role(), goal.parentSkill(), goal.sourceLineSelection(),
                            owners.get(i).figures(), c));
                    i++;
                }
            }
            return classified;
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Classifies one batch, returning a list aligned to {@code texts} (null entries where the model
     * gave no usable level). On failure the whole batch falls back to nulls so the goals still persist
     * without levels, matching the per-goal behaviour.
     */
    private List<TaxonomyClassification> safeClassifyBatch(List<String> texts, String modelOverride) {
        try {
            List<TaxonomyClassification> result = taxonomyService.classifyBatch(texts, modelOverride);
            if (result.size() == texts.size()) {
                return result;
            }
            log.warn("Taxonomy batch returned {} results for {} goals, persisting batch without levels",
                    result.size(), texts.size());
        } catch (RuntimeException ex) {
            log.warn("Taxonomy classification failed for batch, persisting without levels: {}", ex.getMessage());
        }
        return new ArrayList<>(Collections.nCopies(texts.size(), null));
    }

    /**
     * Embeds every goal, batched ({@code hestia.embedding.batch-size}) so the embedding endpoint
     * receives many texts per request instead of one HTTP round trip per goal; batches run in
     * parallel. The result is aligned back to {@code classified}.
     */
    private List<EnrichedGoal> embedInParallel(List<ClassifiedGoal> classified, ExtractionProgressTracker.Run run) {
        run.phase(ExtractionProgressTracker.Phase.EMBEDDING, classified.size());
        if (classified.isEmpty()) {
            return List.of();
        }
        int size = Math.max(1, embeddingBatchSize);
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, parallelism));
        try {
            List<CompletableFuture<List<float[]>>> futures = new ArrayList<>();
            for (int start = 0; start < classified.size(); start += size) {
                int from = start;
                int to = Math.min(start + size, classified.size());
                List<String> texts = classified.subList(from, to).stream()
                        .map(cg -> cg.extracted().text())
                        .toList();
                futures.add(CompletableFuture.supplyAsync(
                        () -> {
                            List<float[]> result = safeEmbedBatch(texts);
                            run.increment(to - from);
                            return result;
                        },
                        executor));
            }
            List<EnrichedGoal> enriched = new ArrayList<>(classified.size());
            int i = 0;
            for (CompletableFuture<List<float[]>> future : futures) {
                for (float[] embedding : future.join()) {
                    enriched.add(new EnrichedGoal(classified.get(i), embedding));
                    i++;
                }
            }
            return enriched;
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Embeds one batch, returning a list aligned to {@code texts} (null entries on failure so the
     * goals still persist without a vector, matching the per-goal behaviour).
     */
    private List<float[]> safeEmbedBatch(List<String> texts) {
        try {
            List<float[]> result = embeddingService.embedAll(texts);
            if (result.size() == texts.size()) {
                return result;
            }
            log.warn("Embedding batch returned {} vectors for {} texts, persisting batch without vectors",
                    result.size(), texts.size());
        } catch (RuntimeException ex) {
            log.warn("Embedding failed for batch of {} texts, persisting without vectors: {}",
                    texts.size(), ex.getMessage());
        }
        return new ArrayList<>(Collections.nCopies(texts.size(), null));
    }

    private float[] safeEmbed(String text) {
        try {
            return embeddingService.embed(text);
        } catch (RuntimeException ex) {
            log.warn("Embedding failed for goal, persisting without vector: {}", ex.getMessage());
            return null;
        }
    }

    private DirectSourceResolution resolveDirectSource(
            Document document, Unit unit, SourceLineSelection selection,
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

    private void persistGoalSource(LearningGoal goal, Document document, String modelSnippet,
                                   SourcePageResolver.Resolution resolution,
                                   EvidenceKind evidenceKind,
                                   Map<Long, PDDocument> pdfDocuments,
                                   Set<Long> attemptedPdfDocuments) {
        String rawText = document.getRawText();
        String persistedSnippet = modelSnippet;
        if (resolution.grounded() && resolution.matchStart() != null && resolution.matchEnd() != null
                && rawText != null && resolution.matchStart() >= 0
                && resolution.matchStart() <= resolution.matchEnd()
                && resolution.matchEnd() <= rawText.length()) {
            persistedSnippet = rawText.substring(resolution.matchStart(), resolution.matchEnd());
        }
        GoalSource source = evidenceKind == EvidenceKind.FIGURE
                ? GoalSource.figure(goal, document, resolution.page())
                : new GoalSource(goal, document, persistedSnippet, resolution.page(), resolution.quality());
        if (resolution.grounded() && resolution.page() != null
                && resolution.matchStart() != null && resolution.matchEnd() != null
                && document.getPageOffsets() != null
                && resolution.page() >= 1
                && resolution.page() < document.getPageOffsets().length) {
            PDDocument pdf = openPdf(document, pdfDocuments, attemptedPdfDocuments);
            if (pdf != null) {
                int pageStart = document.getPageOffsets()[resolution.page() - 1];
                int pageLocalStart = resolution.matchStart() - pageStart;
                int pageLocalEnd = resolution.matchEnd() - pageStart;
                try {
                    List<HighlightRect> rects = highlightGeometryService.findHighlightRects(
                            pdf, resolution.page(), pageLocalStart, pageLocalEnd);
                    // An empty result is "no geometry", not "highlight nothing": leave the
                    // column null so the client can fall back to its own text match.
                    source.setHighlightRects(rects.isEmpty() ? null : rects);
                } catch (IOException | RuntimeException geometryFailure) {
                    log.warn("Could not compute source highlight geometry for document {}: {}",
                            document.getId(), geometryFailure.getMessage());
                }
            }
        }
        goalSourceRepository.save(source);
    }

    private PDDocument openPdf(Document document, Map<Long, PDDocument> pdfDocuments,
                               Set<Long> attemptedPdfDocuments) {
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

    private static void closePdfDocuments(Map<Long, PDDocument> pdfDocuments) {
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

    private static List<PageDescriptionService.FigureDescription> figureDescriptionsFor(
            Document document, Unit unit, List<PageDescriptionService.FigureDescription> descriptions) {
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

    /**
     * Creates one SESSION/EXERCISE hierarchy node per persisted structural section of the document
     * (each a character range of the raw text), under the course's module root. A document with no
     * sections (non-PDF, or a PDF without bookmarks) becomes a single session spanning its whole
     * text, titled by the filename. Returns the units with their text ranges so the parsing step can
     * route each complete range to the right node.
     */
    private List<Unit> buildUnits(Course course, HierarchyNode moduleRoot, Document document) {
        String text = document.getRawText();
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Unit> units = new ArrayList<>();
        for (DocumentSection s : documentSectionRepository.findByDocumentIdOrderByOrdinal(document.getId())) {
            int start = Math.max(0, Math.min(s.getStartOffset(), text.length()));
            int end = Math.max(start, Math.min(s.getEndOffset(), text.length()));
            HierarchyNode node = hierarchyNodeRepository.save(
                    new HierarchyNode(course, moduleRoot, levelFor(s.getTitle()), s.getTitle(), document));
            units.add(new Unit(node, start, end, s.getStartPage(), s.getEndPage()));
        }
        if (units.isEmpty()) {
            HierarchyNode node = hierarchyNodeRepository.save(new HierarchyNode(
                    course, moduleRoot, levelFor(document.getFilename()), document.getFilename(), document));
            int pageCount = document.getPageOffsets() == null ? 0 : document.getPageOffsets().length - 1;
            units.add(new Unit(node, 0, text.length(), pageCount > 0 ? 1 : null,
                    pageCount > 0 ? pageCount : null));
        }
        return units;
    }

    /**
     * Deterministic level from a title/filename: exercise sheets, tutorials and assignments become
     * EXERCISE; everything else is a SESSION (lecture/chapter). Bookmarks and filenames carry no
     * reliable module signal, so the only MODULE node is the course root.
     */
    private static HierarchyLevel levelFor(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        if (t.contains("exercise") || t.contains("übung") || t.contains("uebung")
                || t.contains("tutorial") || t.contains("assignment")) {
            return HierarchyLevel.EXERCISE;
        }
        return HierarchyLevel.SESSION;
    }

    /** One session/exercise unit: its hierarchy node and the raw-text range [start, end) it covers. */
    private record Unit(HierarchyNode node, int start, int end, Integer startPage, Integer endPage) {
    }

    private record SessionUnit(Document document, HierarchyNode node, String title, String text,
                               List<PageDescriptionService.FigureDescription> figures) {
    }

    private record ChunkExtraction(Document document, HierarchyNode node, List<SessionGoal> goals,
                                   List<PageDescriptionService.FigureDescription> figures) {
    }

    private record SessionGoal(ExtractedGoal extracted, GoalRole role, ExtractedGoal parentSkill,
                               SourceLineSelection sourceLineSelection) {
    }

    private record ClassifiedGoal(Document document, HierarchyNode node, ExtractedGoal extracted,
                                  GoalRole role, ExtractedGoal parentSkill,
                                  SourceLineSelection sourceLineSelection,
                                  List<PageDescriptionService.FigureDescription> figures,
                                  TaxonomyClassification classification) {
    }

    private record SourceLineSelection(Integer startLine, Integer endLine, Integer figure) {
    }

    private record DirectSourceResolution(SourcePageResolver.Resolution resolution,
                                          EvidenceKind evidenceKind) {
    }

    private record EnrichedGoal(ClassifiedGoal classified, float[] embedding) {
    }

    public record ExtractionSummary(int documentsProcessed, int goalsCreated, int terminalCompetencies,
                                    int textSources, int figureSources, int unsupportedSources) {

        public ExtractionSummary(int documentsProcessed, int goalsCreated, int terminalCompetencies) {
            this(documentsProcessed, goalsCreated, terminalCompetencies, 0, 0, 0);
        }
    }
}
