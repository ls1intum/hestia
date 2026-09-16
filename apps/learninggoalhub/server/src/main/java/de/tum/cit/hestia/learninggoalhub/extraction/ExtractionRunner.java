package de.tum.cit.hestia.learninggoalhub.extraction;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import de.tum.cit.hestia.learninggoalhub.document.Document;
import de.tum.cit.hestia.learninggoalhub.document.DocumentContent;
import de.tum.cit.hestia.learninggoalhub.document.DocumentContentRepository;
import de.tum.cit.hestia.learninggoalhub.document.DocumentKind;
import de.tum.cit.hestia.learninggoalhub.document.DocumentOrder;
import de.tum.cit.hestia.learninggoalhub.document.DocumentRepository;
import de.tum.cit.hestia.learninggoalhub.document.DocumentSection;
import de.tum.cit.hestia.learninggoalhub.document.DocumentSectionRepository;
import de.tum.cit.hestia.learninggoalhub.document.LanguageUtils;
import de.tum.cit.hestia.learninggoalhub.document.PageDescription;
import de.tum.cit.hestia.learninggoalhub.document.PageDescriptionRepository;
import de.tum.cit.hestia.learninggoalhub.document.PageDescriptionService;
import de.tum.cit.hestia.learninggoalhub.extraction.UnitExtractor.SessionExtraction;
import de.tum.cit.hestia.learninggoalhub.extraction.UnitExtractor.SessionUnit;
import de.tum.cit.hestia.learninggoalhub.extraction.UnitExtractor.SourceLineSelection;
import de.tum.cit.hestia.learninggoalhub.extraction.UnitExtractor.UnitExtraction;
import de.tum.cit.hestia.learninggoalhub.extraction.UnitExtractor.UnitGoal;
import de.tum.cit.hestia.learninggoalhub.extraction.UnitExtractor.Window;
import de.tum.cit.hestia.learninggoalhub.goal.BloomLevel;
import de.tum.cit.hestia.learninggoalhub.goal.GoalKind;
import de.tum.cit.hestia.learninggoalhub.goal.GoalOrigin;
import de.tum.cit.hestia.learninggoalhub.goal.GoalRole;
import de.tum.cit.hestia.learninggoalhub.goal.GoalSource;
import de.tum.cit.hestia.learninggoalhub.goal.GoalSourceId;
import de.tum.cit.hestia.learninggoalhub.goal.GoalSourceRepository;
import de.tum.cit.hestia.learninggoalhub.goal.GoalStatus;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoal;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoalRepository;
import de.tum.cit.hestia.learninggoalhub.goal.SoloLevel;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyLevel;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNode;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNodeRepository;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationship;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationshipRepository;
import de.tum.cit.hestia.learninggoalhub.relationships.RelationshipOrigin;
import de.tum.cit.hestia.learninggoalhub.relationships.RelationshipType;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyClassification;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyService;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ExtractionRunner {

    private static final Logger log = LoggerFactory.getLogger(ExtractionRunner.class);

    private final CourseRepository courseRepository;
    private final DocumentRepository documentRepository;
    private final DocumentContentRepository documentContentRepository;
    private final PageDescriptionService pageDescriptionService;
    private final PageDescriptionRepository pageDescriptionRepository;
    private final LearningGoalRepository goalRepository;
    private final GoalSourceRepository goalSourceRepository;
    private final GoalRelationshipRepository goalRelationshipRepository;
    private final UnitExtractor unitExtractor;
    private final ExtractionRunAuditService extractionRunAuditService;
    private final DocumentSectionRepository documentSectionRepository;
    private final TopicTreeSynthesizer topicTreeSynthesizer;
    private final HierarchyNodeRepository hierarchyNodeRepository;
    private final TaxonomyService taxonomyService;
    private final boolean keepEmptyUnits;
    private final ExtractionProgressTracker progressTracker;
    private final TransactionOperations extractionTransactions;
    private final int parallelism;
    private final int figureParallelism;
    private final String configuredDefaultModel;

    public ExtractionRunner(CourseRepository courseRepository,
                            DocumentRepository documentRepository,
                            DocumentContentRepository documentContentRepository,
                            PageDescriptionService pageDescriptionService,
                            PageDescriptionRepository pageDescriptionRepository,
                            LearningGoalRepository goalRepository,
                            GoalSourceRepository goalSourceRepository,
                            GoalRelationshipRepository goalRelationshipRepository,
                            UnitExtractor unitExtractor,
                            ExtractionRunAuditService extractionRunAuditService,
                            DocumentSectionRepository documentSectionRepository,
                            TopicTreeSynthesizer topicTreeSynthesizer,
                            HierarchyNodeRepository hierarchyNodeRepository,
                            TaxonomyService taxonomyService,
                            ExtractionProgressTracker progressTracker,
                            TransactionOperations extractionTransactions,
                            @Value("${hestia.extraction.parallelism:8}") int parallelism,
                            @Value("${hestia.figures.parallelism:4}") int figureParallelism,
                            @Value("${hestia.extraction.keep-empty-units:false}") boolean keepEmptyUnits,
                            @Value("${spring.ai.openai.chat.options.model:}") String configuredDefaultModel) {
        this.courseRepository = courseRepository;
        this.documentRepository = documentRepository;
        this.documentContentRepository = documentContentRepository;
        this.pageDescriptionService = pageDescriptionService;
        this.pageDescriptionRepository = pageDescriptionRepository;
        this.goalRepository = goalRepository;
        this.goalSourceRepository = goalSourceRepository;
        this.goalRelationshipRepository = goalRelationshipRepository;
        this.unitExtractor = unitExtractor;
        this.extractionRunAuditService = extractionRunAuditService;
        this.documentSectionRepository = documentSectionRepository;
        this.topicTreeSynthesizer = topicTreeSynthesizer;
        this.hierarchyNodeRepository = hierarchyNodeRepository;
        this.taxonomyService = taxonomyService;
        this.progressTracker = progressTracker;
        this.extractionTransactions = extractionTransactions;
        this.parallelism = parallelism;
        this.figureParallelism = figureParallelism;
        this.keepEmptyUnits = keepEmptyUnits;
        this.configuredDefaultModel = configuredDefaultModel;
    }

    public ExtractionSummary runForCourse(Long courseId) {
        return runForCourse(courseId, null, false);
    }

    public ExtractionSummary runForCourse(Long courseId, String modelOverride) {
        return runForCourse(courseId, modelOverride, false);
    }

    public ExtractionSummary runForCourse(Long courseId, String modelOverride, boolean force) {
        ExtractionProgressTracker.Run run = progressTracker.start(courseId, modelOverride);
        AtomicReference<Long> auditRunId = new AtomicReference<>();
        ExtractionStageSummary[] persistedStage = new ExtractionStageSummary[1];
        try {
            ExtractionStageSummary stage = extractionTransactions.execute(status -> {
                Course course = courseRepository.findById(courseId)
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Course not found: " + courseId));
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

                List<Document> documents = documentRepository.findByCourseId(courseId).stream()
                        .sorted(DocumentOrder.comparator())
                        .toList();
                String dominantLanguage = dominantLanguage(documents);
                String courseLanguage = resolveLanguage(course, null, dominantLanguage);
                String promptVersion = SessionExtractionService.PROMPT_VERSION;
                String effectiveModel = modelOverride == null || modelOverride.isBlank()
                        ? configuredDefaultModel : modelOverride;
                if (effectiveModel != null && effectiveModel.isBlank()) {
                    effectiveModel = null;
                }
                auditRunId.set(extractionRunAuditService.start(courseId, effectiveModel, promptVersion,
                        runParams(courseLanguage, course.isFiguresEnabled())));
                return extractAndPersist(course, documents, modelOverride,
                        LanguageUtils.englishName(courseLanguage), dominantLanguage, promptVersion, run);
            });
            if (stage == null) {
                throw new IllegalStateException("Extraction transaction completed without a result.");
            }
            persistedStage[0] = stage;
            run.checkpoint(new ExtractionSummary(stage.documentsProcessed(), stage.goalsCreated(), 0,
                    stage.textSources(), stage.figureSources(), stage.unsupportedSources()));

            run.phase(ExtractionProgressTracker.Phase.SYNTHESIZING, 1);
            CompetencyTreeResult competencyTree = extractionTransactions.execute(status -> {
                Course course = courseRepository.findById(courseId)
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Course not found: " + courseId));
                return buildCompetencyTree(course, modelOverride, stage.courseLanguageName());
            });
            if (competencyTree == null) {
                throw new IllegalStateException("Competency tree transaction completed without a result.");
            }
            run.increment();
            ExtractionSummary summary = new ExtractionSummary(stage.documentsProcessed(), stage.goalsCreated(),
                    competencyTree.competencies(), stage.textSources(), stage.figureSources(),
                    stage.unsupportedSources());
            extractionRunAuditService.finish(auditRunId.get(), ExtractionRun.Status.SUCCEEDED, null,
                    summary.goalsCreated(), run.failedSessions(), stage.promptVersion());
            return summary;
        } catch (RuntimeException ex) {
            ExtractionStageSummary stage = persistedStage[0];
            String error = stage == null
                    ? errorMessage(ex)
                    : "The learning goals were saved, but the competency tree could not be built. "
                            + "Retry only the competency tree. Details: " + errorMessage(ex);
            if (auditRunId.get() != null) {
                extractionRunAuditService.finish(auditRunId.get(), ExtractionRun.Status.FAILED, error,
                        stage == null ? null : stage.goalsCreated(), run.failedSessions(),
                        stage == null ? SessionExtractionService.PROMPT_VERSION : stage.promptVersion());
            }
            if (stage != null) {
                throw new IllegalStateException(error, ex);
            }
            throw ex;
        }
    }

    private ExtractionStageSummary extractAndPersist(Course course, List<Document> documents,
                                                     String modelOverride, String courseLanguageName,
                                                     String dominantLanguage, String promptVersion,
                                                     ExtractionProgressTracker.Run run) {
        Map<Long, List<PageDescriptionService.FigureDescription>> figuresByDocument;
        if (course.isFiguresEnabled()) {
            // The width is spent on batches, not documents: a document is taken at a time so it keeps
            // its own transaction and the commit-per-document guarantee, while the executor runs that
            // document's page batches concurrently. Spreading across documents instead capped a
            // two-PDF course at two calls in flight however wide the pool.
            run.phase(ExtractionProgressTracker.Phase.DESCRIBING_FIGURES, documents.size());
            ExecutorService figureExecutor = Executors.newFixedThreadPool(Math.max(1, figureParallelism));
            try {
                for (Document document : documents) {
                    try {
                        documentContentRepository.findById(document.getId())
                                .map(DocumentContent::getBytes)
                                .ifPresent(bytes -> {
                                    String languageCode = resolveLanguage(
                                            course, document.getLanguage(), dominantLanguage);
                                    pageDescriptionService.describeEligiblePages(document, bytes,
                                            languageCode, LanguageUtils.englishName(languageCode),
                                            figureExecutor);
                                });
                    } catch (RuntimeException e) {
                        log.warn("Could not prepare figure descriptions for document {}: {}",
                                document.getId(), e.getMessage());
                    }
                    run.increment();
                }
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

        // Each session gets the complete text range of its structural node.
        run.phase(ExtractionProgressTracker.Phase.PARSING, documents.size());
        List<SessionUnit<HierarchyNode>> sessions = new ArrayList<>();
        for (Document d : documents) {
            String text = d.getRawText();
            if (text != null && !text.isBlank()) {
                for (Unit unit : unitsByDocument.getOrDefault(d.getId(), List.of())) {
                    Window window = unit.window();
                    String unitText = text.substring(window.start(), Math.min(window.end(), text.length()));
                    if (!unitText.isBlank()) {
                        sessions.add(new SessionUnit<>(unit.node(), d, window, unit.node().getLabel(), unitText,
                                UnitExtractor.figureDescriptionsFor(d, window,
                                        figuresByDocument.getOrDefault(d.getId(), List.of()))));
                    }
                }
            }
            run.increment();
        }

        run.phase(ExtractionProgressTracker.Phase.EXTRACTING, sessions.size());
        List<SessionExtraction<HierarchyNode>> extractedSessions = extractSessions(
                course, sessions, dominantLanguage, modelOverride, run);
        if (run.failedSessions() > 0) {
            throw new IllegalStateException(incompleteExtractionMessage(run.failedSessionNames()));
        }
        List<UnitExtraction<HierarchyNode>> assembled = unitExtractor.assemble(extractedSessions);

        List<ClassifiedGoal> classified = classifyInParallel(assembled, modelOverride, run);

        run.phase(ExtractionProgressTracker.Phase.PERSISTING, classified.size());
        int goalsCreated = 0;
        int textSources = 0;
        int figureSources = 0;
        int unsupportedSources = 0;
        Map<ExtractedGoal, LearningGoal> persistedGoals = new IdentityHashMap<>();
        try (UnitExtractor.PdfCache pdfCache = unitExtractor.pdfCache()) {
            for (ClassifiedGoal classifiedGoal : classified) {
                ExtractedGoal e = classifiedGoal.extracted();
                Document document = classifiedGoal.document();

                LearningGoal goal = new LearningGoal(course, e.text(), e.kind());
                goal.setShortLabel(e.shortLabel());
                goal.setRole(classifiedGoal.role());
                goal.setLectureOrder(goalsCreated);
                if (classifiedGoal.node() != null) {
                    goal.setHierarchyNode(classifiedGoal.node());
                }
                if (classifiedGoal.classification() != null) {
                    goal.setBloomLevel(classifiedGoal.classification().bloom());
                    goal.setSoloLevel(classifiedGoal.classification().solo());
                }
                LearningGoal target = goalRepository.saveAndFlush(goal);
                persistedGoals.put(e, target);
                goalsCreated++;

                GoalSourceId sourceId = new GoalSourceId(target.getId(), document.getId());
                if (!goalSourceRepository.existsById(sourceId)) {
                    UnitExtractor.ResolvedSource source = unitExtractor.resolve(document, classifiedGoal.window(),
                            classifiedGoal.sourceLineSelection(), classifiedGoal.figures(), e.sourceSnippet());
                    unitExtractor.persistSource(target, document, source, pdfCache);
                    switch (source.evidenceKind()) {
                        case TEXT -> textSources++;
                        case FIGURE -> figureSources++;
                        case UNSUPPORTED -> unsupportedSources++;
                    }
                }

                run.increment();
            }
        }

        for (ClassifiedGoal classifiedGoal : classified) {
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

        return new ExtractionStageSummary(documents.size(), goalsCreated, textSources, figureSources,
                unsupportedSources, courseLanguageName, promptVersion);
    }

    /** Runs one extraction call per unit; every unit is within the direct path by construction. */
    private List<SessionExtraction<HierarchyNode>> extractSessions(Course course,
                                                                   List<SessionUnit<HierarchyNode>> sessions,
                                                                   String dominantLanguage, String modelOverride,
                                                                   ExtractionProgressTracker.Run run) {
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, parallelism));
        try {
            List<CompletableFuture<SessionExtraction<HierarchyNode>>> futures = sessions.stream()
                    .map(session -> CompletableFuture.supplyAsync(() -> {
                        try {
                            return unitExtractor.extract(course, session, dominantLanguage, modelOverride);
                        } catch (RuntimeException ex) {
                            // Keep collecting every failed name while the other workers finish. The
                            // caller then aborts the transaction, so no partial course tree is exposed.
                            log.warn("Session extraction failed for '{}'; the extraction will be aborted",
                                    session.title(), ex);
                            run.sessionFailed(session.title());
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

    private static String incompleteExtractionMessage(List<String> failedSessionNames) {
        int count = failedSessionNames.size();
        String sessions = failedSessionNames.stream().sorted().collect(Collectors.joining(", "));
        return count == 1
                ? "One session could not be analysed: " + sessions + ". Retry the extraction."
                : count + " sessions could not be analysed: " + sessions + ". Retry the extraction.";
    }

    private String runParams(String language, boolean figuresEnabled) {
        return "{\"unit-max-chars\":" + unitExtractor.unitMaxChars()
                + ",\"skill-target-chars\":" + unitExtractor.skillTargetChars()
                + ",\"keep-empty-units\":" + keepEmptyUnits
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

    /**
     * Deletes SESSION/EXERCISE units that ended up with no goals. Only leaf units are removed and only when no goal
     * references them, so nothing is orphaned; the MODULE root is always kept as the tree's anchor.
     */
    private int pruneEmptyUnits(Course course) {
        if (keepEmptyUnits) {
            // A unit that returned no skill is a finding, not debris: whether a lecture teaches any
            // performance at all is what the APPLY-or-above contract is being measured on, and a
            // pruned node cannot be told apart from one that was never uploaded.
            log.info("Keeping empty units in course {} (hestia.extraction.keep-empty-units)",
                    course.getId());
            return 0;
        }
        Set<Long> nodesWithGoals = goalRepository.findByCourseIdAndHierarchyNodeIsNotNull(course.getId()).stream()
                .map(g -> g.getHierarchyNode().getId())
                .collect(Collectors.toSet());
        List<HierarchyNode> empty = hierarchyNodeRepository.findByCourseId(course.getId()).stream()
                .filter(n -> n.getLevel() != HierarchyLevel.MODULE)
                .filter(n -> !nodesWithGoals.contains(n.getId()))
                .toList();
        if (!empty.isEmpty()) {
            hierarchyNodeRepository.deleteAll(empty);
            log.info("Pruned {} empty unit(s) from course {}", empty.size(), course.getId());
        }
        return empty.size();
    }

    /** Materializes the extraction's own knowledge to skill edges. */
    private int linkContributors(Collection<LearningGoal> supporters, LearningGoal targetGoal) {
        return linkRelationships(supporters, targetGoal, RelationshipType.CONTRIBUTES_TO,
                RelationshipOrigin.HIERARCHY);
    }

    /**
     * Materializes an edge laid down by tree synthesis. Marked {@link RelationshipOrigin#SYNTHESIS}
     * so a rebuild can delete exactly the edges it created: with elected sub-skills both ends of a
     * tree edge are surviving extracted goals, so nothing else distinguishes it from the extraction
     * edge beside it, and a rebuild would otherwise stack a second copy on every re-run.
     */
    private int linkSynthesized(Collection<LearningGoal> supporters, LearningGoal targetGoal,
                                RelationshipType type) {
        return linkRelationships(supporters, targetGoal, type, RelationshipOrigin.SYNTHESIS);
    }

    /** Materializes idempotent edges between goals. */
    private int linkRelationships(Collection<LearningGoal> supporters, LearningGoal targetGoal,
                                  RelationshipType type, RelationshipOrigin origin) {
        int created = 0;
        for (LearningGoal source : supporters) {
            if (source.getId().equals(targetGoal.getId())) {
                continue;
            }
            if (goalRelationshipRepository.existsBySourceIdAndTargetIdAndType(
                    source.getId(), targetGoal.getId(), type)) {
                continue;
            }
            goalRelationshipRepository.save(new GoalRelationship(
                    source, targetGoal, type, 1.0, origin));
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

    /**
     * What the competency tree came out as.
     *
     * @param competencies   how many terminal competencies were created.
     * @param unmatchedGoals source outcomes no topic covers, left outside the tree.
     */
    public record CompetencyTreeResult(int competencies, int unmatchedGoals) {
        static final CompetencyTreeResult NONE = new CompetencyTreeResult(0, 0);
    }

    /**
     * Throws away a course's competency tree and builds a fresh one from the goals it already has,
     * without re-reading a single document. Only topic naming, batched assignment, per-topic
     * structuring and classification run, so iterating on the tree costs less than a full extraction.
     *
     * <p>Refuses by default once the tree contains instructor work — a hand-added skill, a
     * hand-added child, a generated subtree, or an approved terminal — because a rebuild replaces
     * exactly those nodes and nothing records what they were. {@code force} overrides that and
     * deletes them. Extracted session/exercise goals are never touched either way: the rebuild only
     * removes the terminals and the tree edges, so instructor edits and approvals on the goals
     * themselves survive.
     *
     * @return the freshly built tree; synthesis failures leave the existing tree untouched.
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
        CompetencyTreePlan plan;
        try {
            plan = planFullCompetencyTree(course, modelOverride,
                    LanguageUtils.englishName(courseLanguage));
        } catch (RuntimeException ex) {
            String detail = errorMessage(ex);
            log.warn("Could not synthesise a replacement competency tree for course {}: {}",
                    courseId, detail, ex);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not synthesise a new competency tree; the existing one was left untouched. "
                            + "Details: " + detail, ex);
        }
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
        // Overfull extracted branches create source-less synthesized grouping nodes. They are fully
        // reproducible tree artefacts, not instructor work, so rebuilds remove them without tripping
        // the manual-work guard above.
        for (LearningGoal goal : goalRepository.findByCourseId(course.getId())) {
            boolean consolidatedGroup = goal.getOrigin() == GoalOrigin.SYNTHESIZED
                    && goal.getRole() == GoalRole.SKILL
                    && goal.getHierarchyNode() == null
                    && goal.getCreationProvenance() == null;
            if (consolidatedGroup && doomed.stream().noneMatch(g -> g.getId().equals(goal.getId()))) {
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
            // Anything synthesis laid down is reproducible and always goes. The type checks below
            // stay for trees built before edges carried that marker: SUPPORTS has no other producer,
            // and a CONTRIBUTES_TO that is not the extraction's own knowledge edge came from a tree.
            boolean treeEdge = allContributes
                    && (relationship.getOrigin() == RelationshipOrigin.SYNTHESIS
                        || relationship.getType() == RelationshipType.SUPPORTS
                        || (relationship.getType() == RelationshipType.CONTRIBUTES_TO && !extractionEdge));
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
     * Builds the competency-tree view ALONGSIDE the module goals (not a replacement) under its own
     * {@code COMPETENCY} root: topic → capability → extracted skill → knowledge, with skills that
     * form no capability directly under their topic.
     *
     * <p>Topics are named, skills assigned and topics structured before anything is written. A failed
     * call therefore leaves the source-backed outcomes untouched and available for a tree-only retry.
     *
     * <p>This method runs in a transaction separate from extraction persistence.
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
        log.info("Built topic competency tree for course {}: {} topics, {} unmatched goal(s)",
                course.getId(), plan.competencies().size(), plan.unmatchedGoals());
        return new CompetencyTreeResult(plan.competencies().size(), plan.unmatchedGoals());
    }

    /**
     * Runs all synthesis for a course's competency tree and returns the finished plan, or
     * {@code null} when there are no skill seeds. Touches nothing in the database, so the caller
     * decides when — and whether — to write. Synthesis failures are propagated to the caller.
     */
    private CompetencyTreePlan planFullCompetencyTree(Course course, String modelOverride,
                                                      String languageName) {
        // Only skill-tier session/exercise goals are tree candidates. Role is structural for V24+
        // data, while role-null legacy goals retain the Bloom fallback.
        List<LearningGoal> candidates = goalRepository.findByCourseIdAndHierarchyNodeIsNotNull(course.getId()).stream()
                .filter(g -> g.getHierarchyNode().getLevel() != HierarchyLevel.MODULE
                        && g.getHierarchyNode().getLevel() != HierarchyLevel.COMPETENCY)
                .filter(ExtractionRunner::isSkillTier)
                .sorted(Comparator.comparing(
                                LearningGoal::getLectureOrder,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(LearningGoal::getId))
                .toList();
        // The skill tier IS the seed set. Bloom must not narrow it any further: extraction now
        // decides the tier, and the session prompt deliberately keeps verbs low ("when in doubt,
        // prefer understand/know"), so a course whose skills all classify as UNDERSTAND would
        // otherwise produce no seeds and silently lose its whole competency tree. For role-null
        // legacy goals isSkillTier already means "high Bloom", so their seeds are unchanged.
        if (candidates.isEmpty()) {
            return null;
        }

        TopicTreeSynthesizer.Plan plan;
        try {
            plan = topicTreeSynthesizer.synthesize(
                    candidates.stream().map(LearningGoal::getText).toList(), languageName, modelOverride);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Topic tree synthesis failed: " + errorMessage(ex), ex);
        }
        if (plan.topics().isEmpty()) {
            throw new IllegalStateException("Topic tree synthesis placed none of the " + candidates.size()
                    + " source outcomes under a topic. Retry the competency tree.");
        }
        // Topics are noun phrases and carry no level of their own; only capability names state a
        // performance, so only they are classified. Every placed outcome keeps its extracted levels.
        List<String> capabilityNames = plan.topics().stream()
                .flatMap(topic -> topic.capabilities().stream())
                .map(TopicTreeSynthesizer.PlannedCapability::name)
                .toList();
        List<TaxonomyClassification> classifications = capabilityNames.isEmpty()
                ? List.of()
                : safeClassifyBatch(capabilityNames, modelOverride);
        List<PlannedCompetency> planned = new ArrayList<>();
        int capabilityIndex = 0;
        for (TopicTreeSynthesizer.PlannedTopic topic : plan.topics()) {
            List<PlannedCapability> capabilities = new ArrayList<>();
            for (TopicTreeSynthesizer.PlannedCapability capability : topic.capabilities()) {
                List<LearningGoal> members = capability.outcomes().stream().map(candidates::get).toList();
                capabilities.add(new PlannedCapability(capability.name(), capability.shortLabel(),
                        atLeastChildLevels(classifications.get(capabilityIndex++),
                                bloomLevels(members), soloLevels(members)),
                        members));
            }
            List<LearningGoal> direct = topic.direct().stream().map(candidates::get).toList();
            planned.add(new PlannedCompetency(topic.label(), capabilities, direct));
        }
        planned.sort(Comparator.comparingInt(ExtractionRunner::medianLectureOrder));
        int unmatched = plan.unmatched().size();
        // An outcome no topic covers is left out of the tree rather than forced under the nearest
        // label. The count is reported so a reviewer can tell how much of the course that is.
        if (unmatched > 0) {
            log.info("Competency tree for course {} leaves {} of {} source outcomes under no topic",
                    course.getId(), unmatched, candidates.size());
        }
        return new CompetencyTreePlan(planned, unmatched);
    }

    /**
     * The finished competency tree, computed entirely in memory so that every LLM call has already
     * succeeded before the first row is written.
     *
     * @param competencies   one entry per terminal competency, in tree order.
     * @param unmatchedGoals source goals no topic covers, left outside the tree. Reported to the
     *                       caller rather than suppressed.
     */
    private record CompetencyTreePlan(List<PlannedCompetency> competencies, int unmatchedGoals) {}

    /**
     * One terminal competency: a topic with the capabilities and outcomes beneath it.
     *
     * A topic is a noun phrase and carries no Bloom or SOLO level.
     *
     * @param text         the topic label.
     * @param capabilities generated capabilities, each over at least two extracted outcomes.
     * @param direct       extracted outcomes that hang directly under the topic.
     */
    private record PlannedCompetency(String text, List<PlannedCapability> capabilities,
                                     List<LearningGoal> direct) {}

    /**
     * A generated capability: its name, its levels, and the extracted outcomes beneath it. Both its
     * Bloom and its SOLO level are at least the highest among its members.
     */
    private record PlannedCapability(String text, String shortLabel, TaxonomyClassification classification,
                                     List<LearningGoal> members) {}

    private static List<BloomLevel> bloomLevels(List<LearningGoal> goals) {
        return goals.stream().map(LearningGoal::getBloomLevel).filter(java.util.Objects::nonNull).toList();
    }

    private static List<SoloLevel> soloLevels(List<LearningGoal> goals) {
        return goals.stream().map(LearningGoal::getSoloLevel).filter(java.util.Objects::nonNull).toList();
    }

    /**
     * A capability never sits below the outcomes beneath it.
     *
     * <p>Its levels are classified from generated text, the least reliable thing in the tree to read
     * a level off: a name can be classified by a clause at its tail, or come back below the outcomes
     * it groups, which inverts the tier the tree is built on. The members' levels are the trustworthy
     * half — each was classified during extraction with a source passage behind it — so Bloom is
     * raised to the highest level among them, and SOLO gets the same floor. A capability covers every
     * outcome beneath it, so its structure is at least as complex as the most complex of them. The
     * classified SOLO level is kept when it already reaches that floor, since a capability that
     * relates several simple outcomes can rightly sit above all of them. When classification failed,
     * the floor is the only SOLO level the capability gets.
     */
    static TaxonomyClassification atLeastChildLevels(TaxonomyClassification classified,
                                                        List<BloomLevel> childBlooms,
                                                        List<SoloLevel> childSolos) {
        BloomLevel bloom = atLeast(classified == null ? null : classified.bloom(), childBlooms);
        SoloLevel solo = atLeast(classified == null ? null : classified.solo(), childSolos);
        if (classified == null ? bloom == null && solo == null
                : bloom == classified.bloom() && solo == classified.solo()) {
            return classified;
        }
        return new TaxonomyClassification(bloom, solo);
    }

    private static <T extends Comparable<T>> T atLeast(T level, List<T> childLevels) {
        T floor = childLevels.stream().max(Comparator.naturalOrder()).orElse(null);
        if (floor == null || (level != null && level.compareTo(floor) >= 0)) {
            return level;
        }
        return floor;
    }

    /**
     * Where a terminal skill sits in the course. The MEDIAN of every source outcome beneath it, not
     * the earliest: a single stray outcome from lecture one would otherwise drag a whole late chapter
     * to the top of the tree.
     */
    private static int medianLectureOrder(PlannedCompetency competency) {
        List<LearningGoal> goals = new ArrayList<>(competency.direct());
        competency.capabilities().forEach(capability -> goals.addAll(capability.members()));
        return medianOrderOf(goals);
    }

    private static int medianOrderOf(List<LearningGoal> goals) {
        List<Integer> orders = goals.stream()
                .map(LearningGoal::getLectureOrder)
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();
        return orders.isEmpty() ? Integer.MAX_VALUE : orders.get(orders.size() / 2);
    }

    /**
     * Writes a planned tree: the {@code COMPETENCY} root, one terminal goal per topic, one generated
     * goal per capability, and the CONTRIBUTES_TO edges capability → topic, skill →
     * capability and ungrouped skill → topic. Knowledge → skill edges were created during extraction.
     * All LLM work is already done by the time this runs.
     */
    private void persistCompetencyTree(Course course, CompetencyTreePlan plan) {
        HierarchyNode competencyRoot = hierarchyNodeRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.COMPETENCY, "Terminal Competencies"));

        for (PlannedCompetency competency : plan.competencies()) {
            LearningGoal terminal = new LearningGoal(course, competency.text(), GoalKind.IMPLICIT);
            terminal.setShortLabel(competency.text());
            terminal.setOrigin(GoalOrigin.TERMINAL);
            terminal.setHierarchyNode(competencyRoot);
            int medianLectureOrder = medianLectureOrder(competency);
            terminal.setLectureOrder(medianLectureOrder == Integer.MAX_VALUE ? null : medianLectureOrder);
            goalRepository.saveAndFlush(terminal);

            for (PlannedCapability planned : competency.capabilities()) {
                // A generated grouping node without a source of its own. Its members keep their
                // quotes, pages and levels, and each keeps the knowledge extraction hung beneath it.
                LearningGoal capability = new LearningGoal(course, planned.text(), GoalKind.IMPLICIT);
                capability.setOrigin(GoalOrigin.SYNTHESIZED);
                capability.setRole(GoalRole.SKILL);
                capability.setShortLabel(planned.shortLabel());
                int capabilityOrder = medianOrderOf(planned.members());
                capability.setLectureOrder(capabilityOrder == Integer.MAX_VALUE ? null : capabilityOrder);
                applyLevels(capability, planned.classification());
                goalRepository.saveAndFlush(capability);
                linkSynthesized(List.of(capability), terminal, RelationshipType.CONTRIBUTES_TO);
                linkSynthesized(planned.members(), capability, RelationshipType.CONTRIBUTES_TO);
            }
            linkSynthesized(competency.direct(), terminal, RelationshipType.CONTRIBUTES_TO);
        }
    }

    private static void applyLevels(LearningGoal goal, TaxonomyClassification classification) {
        if (classification != null) {
            goal.setBloomLevel(classification.bloom());
            goal.setSoloLevel(classification.solo());
        }
    }

    /**
     * Pairs every extracted goal with the level extraction already gave it.
     *
     * <p>This used to be a phase of its own: goals were flattened, batched and sent back to a model
     * that read the Bloom level off the finished sentence. Extraction now returns bloom and solo with
     * the outcome, so the levels are simply carried across — one fewer call per twenty goals, and no
     * second opinion that can disagree with the verb the writer chose while the material was in view.
     * The phase is still reported so the progress bar keeps its shape.
     */
    private List<ClassifiedGoal> classifyInParallel(List<UnitExtraction<HierarchyNode>> extractions,
                                                    String modelOverride,
                                                    ExtractionProgressTracker.Run run) {
        List<ClassifiedGoal> classified = new ArrayList<>();
        for (UnitExtraction<HierarchyNode> extraction : extractions) {
            SessionUnit<HierarchyNode> session = extraction.session();
            for (UnitGoal goal : extraction.goals()) {
                classified.add(new ClassifiedGoal(session.document(), session.owner(), session.window(),
                        goal.extracted(), goal.role(), goal.parentSkill(), goal.sourceLineSelection(),
                        session.figures(), goal.classification()));
            }
        }
        run.phase(ExtractionProgressTracker.Phase.CLASSIFYING, classified.size());
        run.increment(classified.size());
        return List.copyOf(classified);
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
     * Creates one SESSION/EXERCISE hierarchy node per persisted structural section of the document
     * (each a character range of the raw text), under the course's module root. A document with no
     * sections (non-PDF, or a PDF without bookmarks) becomes a single session spanning its whole
     * text, titled by the filename. Returns the units with their text ranges so the parsing step can
     * route each complete range to the right node.
     *
     * <p>A section is normally one unit. A section too large for one extraction call is cut into
     * several, which is why unit and session are not the same thing: the windows below all belong to
     * the one session node their section created, and the split is invisible to the reader.
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
                    new HierarchyNode(course, moduleRoot, levelFor(document, s.getTitle()), s.getTitle(), document));
            unitExtractor.windows(node.getLabel(), document, text, start, end, s.getStartPage(), s.getEndPage())
                    .forEach(window -> units.add(new Unit(node, window)));
        }
        if (units.isEmpty()) {
            HierarchyNode node = hierarchyNodeRepository.save(new HierarchyNode(
                    course, moduleRoot, levelFor(document, document.getFilename()), document.getFilename(), document));
            int pageCount = document.getPageOffsets() == null ? 0 : document.getPageOffsets().length - 1;
            unitExtractor.windows(node.getLabel(), document, text, 0, text.length(), pageCount > 0 ? 1 : null,
                            pageCount > 0 ? pageCount : null)
                    .forEach(window -> units.add(new Unit(node, window)));
        }
        return units;
    }

    /**
     * The hierarchy level of a unit cut from {@code document}: EXERCISE for a document uploaded as an
     * exercise, SESSION for one uploaded as a lecture. Bookmarks and filenames carry no reliable
     * module signal, so the only MODULE node is the course root.
     *
     * <p>Only a document without a kind, uploaded before the choice existed, still has its level
     * guessed from the title/filename: exercise sheets, tutorials and assignments become EXERCISE,
     * everything else a SESSION. That keeps existing courses exactly as they were.
     */
    static HierarchyLevel levelFor(Document document, String title) {
        if (document != null && document.getKind() != null) {
            return document.getKind() == DocumentKind.EXERCISE ? HierarchyLevel.EXERCISE : HierarchyLevel.SESSION;
        }
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        if (t.contains("exercise") || t.contains("übung") || t.contains("uebung")
                || t.contains("tutorial") || t.contains("assignment")) {
            return HierarchyLevel.EXERCISE;
        }
        return HierarchyLevel.SESSION;
    }

    /** One session/exercise unit: its hierarchy node and the window of raw text it covers. */
    private record Unit(HierarchyNode node, Window window) {
    }

    private record ClassifiedGoal(Document document, HierarchyNode node, Window window, ExtractedGoal extracted,
                                  GoalRole role, ExtractedGoal parentSkill,
                                  SourceLineSelection sourceLineSelection,
                                  List<PageDescriptionService.FigureDescription> figures,
                                  TaxonomyClassification classification) {
    }

    /** Counts and language committed before the separately transactional competency-tree stage. */
    private record ExtractionStageSummary(int documentsProcessed, int goalsCreated, int textSources,
                                          int figureSources, int unsupportedSources,
                                          String courseLanguageName, String promptVersion) {
    }

    public record ExtractionSummary(int documentsProcessed, int goalsCreated, int terminalCompetencies,
                                    int textSources, int figureSources, int unsupportedSources) {

        public ExtractionSummary(int documentsProcessed, int goalsCreated, int terminalCompetencies) {
            this(documentsProcessed, goalsCreated, terminalCompetencies, 0, 0, 0);
        }
    }
}
