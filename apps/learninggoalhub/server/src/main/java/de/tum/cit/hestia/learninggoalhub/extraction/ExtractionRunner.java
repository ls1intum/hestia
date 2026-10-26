package de.tum.cit.hestia.learninggoalhub.extraction;

import de.tum.cit.hestia.learninggoalhub.competency.CompetencyTreeResult;
import de.tum.cit.hestia.learninggoalhub.competency.CompetencyTreeService;
import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import de.tum.cit.hestia.learninggoalhub.course.ExtractionStatus;
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
import de.tum.cit.hestia.learninggoalhub.goal.GoalOrigin;
import de.tum.cit.hestia.learninggoalhub.goal.GoalRole;
import de.tum.cit.hestia.learninggoalhub.goal.GoalSource;
import de.tum.cit.hestia.learninggoalhub.goal.GoalSourceId;
import de.tum.cit.hestia.learninggoalhub.goal.GoalSourceRepository;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoal;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoalRepository;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyLevel;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNode;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNodeRepository;
import de.tum.cit.hestia.learninggoalhub.goal.GoalRelationship;
import de.tum.cit.hestia.learninggoalhub.goal.GoalRelationshipRepository;
import de.tum.cit.hestia.learninggoalhub.goal.RelationshipOrigin;
import de.tum.cit.hestia.learninggoalhub.goal.RelationshipType;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyClassification;
import de.tum.cit.hestia.learninggoalhub.web.ExtractionErrors;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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
    private final CompetencyTreeService competencyTrees;
    private final HierarchyNodeRepository hierarchyNodeRepository;
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
                            CompetencyTreeService competencyTrees,
                            HierarchyNodeRepository hierarchyNodeRepository,
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
        this.competencyTrees = competencyTrees;
        this.hierarchyNodeRepository = hierarchyNodeRepository;
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
            ExtractionInput input = extractionTransactions.execute(status -> {
                Course course = courseRepository.findOrThrow(courseId);
                boolean hasExtractedGoals = !goalRepository.findByCourseIdAndOriginIn(
                        courseId, List.of(GoalOrigin.EXTRACTED)).isEmpty();
                boolean hasExtractionHierarchy = hierarchyNodeRepository.existsByCourseIdAndLevel(
                        courseId, HierarchyLevel.MODULE)
                        || hierarchyNodeRepository.existsByCourseIdAndLevel(courseId, HierarchyLevel.COMPETENCY);
                if ((hasExtractedGoals || hasExtractionHierarchy) && !force) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "A re-extraction replaces the existing extraction artefacts; pass force=true to discard them.");
                }

                List<Document> documents = documentRepository.findByCourseId(courseId).stream()
                        .sorted(DocumentOrder.comparator())
                        .toList();
                String dominantLanguage = LanguageUtils.dominantLanguage(documents);
                String courseLanguage = LanguageUtils.resolveLanguage(course, null, dominantLanguage);
                String promptVersion = SessionExtractionService.PROMPT_VERSION;
                String effectiveModel = modelOverride == null || modelOverride.isBlank()
                        ? configuredDefaultModel : modelOverride;
                if (effectiveModel != null && effectiveModel.isBlank()) {
                    effectiveModel = null;
                }
                auditRunId.set(extractionRunAuditService.start(courseId, effectiveModel, promptVersion,
                        runParams(courseLanguage, course.isFiguresEnabled())));
                return new ExtractionInput(course, documents, dominantLanguage,
                        LanguageUtils.englishName(courseLanguage), promptVersion);
            });
            if (input == null) {
                throw new IllegalStateException("Extraction setup completed without a result.");
            }
            // Every model call runs with no transaction open. A run can take many minutes, and a forced
            // one would otherwise hold the row locks of the goals it replaces for all of that time.
            ExtractedCourse extracted = extract(input.course(), input.documents(), modelOverride,
                    input.dominantLanguage(), run);
            // Checked before anything is stored: an empty result would otherwise succeed with nothing to
            // show, and a forced run would already have replaced the previous extraction with it.
            if (extracted.classified().isEmpty()) {
                throw new IllegalStateException("The extraction found no learning goals in the uploaded material. "
                        + "Check that the documents contain readable text, then retry the extraction.");
            }
            // The old artefacts are replaced in the same transaction that stores the new ones, so a
            // failed run still leaves the previous extraction in place.
            ExtractionStageSummary stage = extractionTransactions.execute(status -> {
                if (force) {
                    clearExtractionArtifacts(input.course());
                }
                return persist(input.course(), input.documents(), extracted, input.courseLanguageName(),
                        input.promptVersion(), run);
            });
            if (stage == null) {
                throw new IllegalStateException("Extraction transaction completed without a result.");
            }
            persistedStage[0] = stage;
            run.checkpoint(new ExtractionSummary(stage.documentsProcessed(), stage.goalsCreated(), 0,
                    stage.textSources(), stage.figureSources(), stage.unsupportedSources()));

            run.phase(ExtractionProgressTracker.Phase.SYNTHESIZING, 1);
            CompetencyTreeResult competencyTree = competencyTrees.buildCompetencyTree(courseId, modelOverride,
                    stage.courseLanguageName());
            run.increment();
            ExtractionSummary summary = new ExtractionSummary(stage.documentsProcessed(), stage.goalsCreated(),
                    competencyTree.competencies(), stage.textSources(), stage.figureSources(),
                    stage.unsupportedSources());
            extractionRunAuditService.finish(auditRunId.get(), ExtractionStatus.SUCCEEDED, null,
                    summary.goalsCreated(), run.failedSessions(), run.droppedOutcomes(), stage.promptVersion());
            return summary;
        } catch (RuntimeException ex) {
            ExtractionStageSummary stage = persistedStage[0];
            String error = stage == null
                    ? ExtractionErrors.describe(ex)
                    : "The learning goals were saved, but the Topic tree could not be built. "
                            + "Retry only the Topic tree. Details: " + ExtractionErrors.describe(ex);
            if (auditRunId.get() != null) {
                extractionRunAuditService.finish(auditRunId.get(), ExtractionStatus.FAILED, error,
                        stage == null ? null : stage.goalsCreated(), run.failedSessions(), run.droppedOutcomes(),
                        stage == null ? SessionExtractionService.PROMPT_VERSION : stage.promptVersion());
            }
            if (stage != null) {
                throw new ExtractionErrors.PublicFailure(error, ex);
            }
            throw ex;
        } finally {
            log.info("LLM token usage for extraction of course {}: {}", courseId, run.usageReport());
        }
    }

    /** What the setup transaction read: the course, its documents in order, and the run's language. */
    private record ExtractionInput(Course course, List<Document> documents, String dominantLanguage,
                                   String courseLanguageName, String promptVersion) {
    }

    /** The extracted goals and the not yet stored hierarchy nodes they belong to, module root first. */
    private record ExtractedCourse(List<HierarchyNode> nodes, List<ClassifiedGoal> classified) {
    }

    /**
     * Describes figures, cuts the documents into units, and runs the extraction calls. Stores only
     * the figure descriptions, each document in a transaction of its own; the hierarchy nodes are
     * built but left for {@link #persist} to store.
     */
    private ExtractedCourse extract(Course course, List<Document> documents, String modelOverride,
                                    String dominantLanguage, ExtractionProgressTracker.Run run) {
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
                                    String languageCode = LanguageUtils.resolveLanguage(
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

        // Structural pass: turn each document into its sessions, built as hierarchy nodes under one
        // module root and stored with the goals. Sessions come from the document's persisted structural sections (PDF
        // bookmarks, detected deterministically at upload); a document with none is one session.
        HierarchyNode moduleRoot = new HierarchyNode(course, null, HierarchyLevel.MODULE, course.getName());
        Map<Long, List<Unit>> unitsByDocument = new HashMap<>();
        Set<HierarchyNode> nodes = new LinkedHashSet<>(List.of(moduleRoot));
        for (Document d : documents) {
            List<Unit> units = buildUnits(course, moduleRoot, d);
            unitsByDocument.put(d.getId(), units);
            units.forEach(unit -> nodes.add(unit.node()));
        }

        // Each session gets the complete text range of its structural node.
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
        }

        run.phase(ExtractionProgressTracker.Phase.EXTRACTING, sessions.size());
        List<SessionExtraction<HierarchyNode>> extractedSessions = extractSessions(
                course, sessions, dominantLanguage, modelOverride, run);
        if (run.failedSessions() > 0) {
            throw new IllegalStateException(incompleteExtractionMessage(run.failedSessionNames()));
        }
        List<UnitExtraction<HierarchyNode>> assembled = unitExtractor.assemble(extractedSessions);

        return new ExtractedCourse(List.copyOf(nodes), classifyInParallel(assembled, modelOverride, run));
    }

    /** Stores the hierarchy nodes, goals, sources and knowledge edges of an extracted course. */
    private ExtractionStageSummary persist(Course course, List<Document> documents, ExtractedCourse extracted,
                                           String courseLanguageName, String promptVersion,
                                           ExtractionProgressTracker.Run run) {
        hierarchyNodeRepository.saveAll(extracted.nodes());
        List<ClassifiedGoal> classified = extracted.classified();
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
                + ",\"exercise-skill-target-chars\":" + unitExtractor.exerciseSkillTargetChars()
                + ",\"keep-empty-units\":" + keepEmptyUnits
                + ",\"parallelism\":" + parallelism
                + ",\"output-language\":\"" + language + "\""
                + ",\"figures-enabled\":" + figuresEnabled
                + ",\"figure-prompt-version\":\"" + PageDescriptionService.FIGURE_PROMPT_VERSION + "\"}";
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

        competencyTrees.clearTree(course);

        if (!extracted.isEmpty()) {
            List<GoalSource> sources = goalSourceRepository.findByGoalIdIn(extractedIds);
            goalSourceRepository.deleteAll(sources);
            goalSourceRepository.flush();
            competencyTrees.clearRelationshipsForGoals(courseGoalIds, extractedIds, false);
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

    /**
     * Pairs every extracted goal with the level extraction already gave it.
     *
     * <p>This used to be a phase of its own: goals were flattened, batched and sent back to a model
     * that read the Bloom level off the finished sentence. Extraction now returns bloom and solo with
     * the outcome, so the levels are simply carried across — one fewer call per twenty goals, and no
     * second opinion that can disagree with the verb the writer chose while the material was in view.
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
        return List.copyOf(classified);
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
            HierarchyNode node =
                    new HierarchyNode(course, moduleRoot, levelFor(document, s.getTitle()), s.getTitle(), document);
            unitExtractor.windows(node.getLabel(), document, text, start, end, s.getStartPage(), s.getEndPage())
                    .forEach(window -> units.add(new Unit(node, window)));
        }
        if (units.isEmpty()) {
            HierarchyNode node = new HierarchyNode(
                    course, moduleRoot, levelFor(document, document.getFilename()), document.getFilename(), document);
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
