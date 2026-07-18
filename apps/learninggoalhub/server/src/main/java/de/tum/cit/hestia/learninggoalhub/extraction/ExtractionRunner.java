package de.tum.cit.hestia.learninggoalhub.extraction;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import de.tum.cit.hestia.learninggoalhub.document.Document;
import de.tum.cit.hestia.learninggoalhub.document.DocumentRepository;
import de.tum.cit.hestia.learninggoalhub.document.DocumentSection;
import de.tum.cit.hestia.learninggoalhub.document.DocumentSectionRepository;
import de.tum.cit.hestia.learninggoalhub.embedding.EmbeddingService;
import de.tum.cit.hestia.learninggoalhub.goal.BloomLevel;
import de.tum.cit.hestia.learninggoalhub.goal.GoalKind;
import de.tum.cit.hestia.learninggoalhub.goal.GoalOrigin;
import de.tum.cit.hestia.learninggoalhub.goal.GoalSource;
import de.tum.cit.hestia.learninggoalhub.goal.GoalSourceId;
import de.tum.cit.hestia.learninggoalhub.goal.GoalSourceRepository;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
import java.util.stream.Collectors;
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
    private static final String FALLBACK_PROMPT_VERSION = "chunked-v1";

    private final CourseRepository courseRepository;
    private final DocumentRepository documentRepository;
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
    private final CompetencyTreeSynthesizer competencyTreeSynthesizer;
    private final DocumentChunker documentChunker;
    private final HierarchyNodeRepository hierarchyNodeRepository;
    private final TaxonomyService taxonomyService;
    private final EmbeddingService embeddingService;
    private final ExtractionProgressTracker progressTracker;
    private final int parallelism;
    private final int directMaxChars;
    private final String configuredDefaultModel;
    private final int taxonomyBatchSize;
    private final int embeddingBatchSize;

    public ExtractionRunner(CourseRepository courseRepository,
                            DocumentRepository documentRepository,
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
                            CompetencyTreeSynthesizer competencyTreeSynthesizer,
                            DocumentChunker documentChunker,
                            HierarchyNodeRepository hierarchyNodeRepository,
                            TaxonomyService taxonomyService,
                            EmbeddingService embeddingService,
                            ExtractionProgressTracker progressTracker,
                            @Value("${hestia.extraction.parallelism:8}") int parallelism,
                            @Value("${hestia.extraction.direct-max-chars:80000}") int directMaxChars,
                            @Value("${spring.ai.openai.chat.options.model:}") String configuredDefaultModel,
                            @Value("${hestia.taxonomy.batch-size:20}") int taxonomyBatchSize,
                            @Value("${hestia.embedding.batch-size:64}") int embeddingBatchSize) {
        this.courseRepository = courseRepository;
        this.documentRepository = documentRepository;
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
        this.competencyTreeSynthesizer = competencyTreeSynthesizer;
        this.documentChunker = documentChunker;
        this.hierarchyNodeRepository = hierarchyNodeRepository;
        this.taxonomyService = taxonomyService;
        this.embeddingService = embeddingService;
        this.progressTracker = progressTracker;
        this.parallelism = parallelism;
        this.directMaxChars = directMaxChars;
        this.configuredDefaultModel = configuredDefaultModel;
        this.taxonomyBatchSize = taxonomyBatchSize;
        this.embeddingBatchSize = embeddingBatchSize;
    }

    @Transactional
    public ExtractionSummary runForCourse(Long courseId) {
        return runForCourse(courseId, null);
    }

    @Transactional
    public ExtractionSummary runForCourse(Long courseId, String modelOverride) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found: " + courseId));

        List<Document> documents = documentRepository.findByCourseId(courseId);
        String promptVersion = promptVersionFor(documents);
        String effectiveModel = modelOverride == null || modelOverride.isBlank()
                ? configuredDefaultModel : modelOverride;
        if (effectiveModel != null && effectiveModel.isBlank()) {
            effectiveModel = null;
        }
        Long auditRunId = extractionRunAuditService.start(courseId, effectiveModel, promptVersion, runParams());
        ExtractionProgressTracker.Run run = progressTracker.start(courseId, modelOverride);
        try {
            ExtractionSummary summary = doRun(course, documents, modelOverride, run);
            run.succeed(summary);
            extractionRunAuditService.finish(auditRunId, ExtractionRun.Status.SUCCEEDED, null,
                    summary.goalsCreated(), promptVersion);
            return summary;
        } catch (RuntimeException ex) {
            String error = errorMessage(ex);
            run.fail(error);
            extractionRunAuditService.finish(auditRunId, ExtractionRun.Status.FAILED, error, null, promptVersion);
            throw ex;
        }
    }

    private ExtractionSummary doRun(Course course, List<Document> documents, String modelOverride,
                                    ExtractionProgressTracker.Run run) {
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
                        sessions.add(new SessionUnit(d, unit.node(), unit.node().getLabel(), unitText));
                    }
                }
            }
            run.increment();
        }

        run.phase(ExtractionProgressTracker.Phase.EXTRACTING, sessions.size());
        List<SessionExtraction> extractedSessions = extractSessions(sessions, modelOverride, run);
        SessionAssembly assembly = assembleSessions(course, extractedSessions);

        List<ClassifiedGoal> classified = classifyInParallel(assembly.sessionGoals(), modelOverride, run);
        List<EnrichedGoal> enriched = embedInParallel(classified, run);

        run.phase(ExtractionProgressTracker.Phase.PERSISTING, enriched.size());
        int goalsCreated = 0;
        for (EnrichedGoal eg : enriched) {
            ExtractedGoal e = eg.classified().extracted();
            Document document = eg.classified().document();

            LearningGoal goal = new LearningGoal(course, e.text(), e.kind());
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
            goalsCreated++;

            GoalSourceId sourceId = new GoalSourceId(target.getId(), document.getId());
            if (!goalSourceRepository.existsById(sourceId)) {
                HierarchyNode sourceNode = eg.classified().node();
                Unit unit = sourceNode == null ? null : unitsByNode.get(sourceNode.getId());
                int textLength = document.getRawText() == null ? 0 : document.getRawText().length();
                int unitStart = unit == null ? 0 : unit.start();
                int unitEnd = unit == null ? textLength : unit.end();
                Integer page = SourcePageResolver.resolve(
                        document.getRawText(), document.getPageOffsets(), unitStart, unitEnd,
                        e.sourceSnippet()).orElse(null);
                goalSourceRepository.save(new GoalSource(target, document, e.sourceSnippet(), page));
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

        // Drop units the outline detected but the extraction routed no goals to (e.g. a session with
        // no extractable outcomes). Without this the tree carries empty phantom sections.
        pruneEmptyUnits(course);

        // Top-down competency view: a three-tier tree (terminal competency → sub-skill → knowledge)
        // under its own COMPETENCY root, with CONTRIBUTES_TO edges threading the tiers.
        CompetencyTreeResult competencyTree = buildCompetencyTree(course, modelOverride);

        return new ExtractionSummary(documents.size(), goalsCreated, competencyTree.competencies(),
                competencyTree.gaps());
    }

    /** Runs one direct call per small session, or the complete legacy pipeline for oversized sessions. */
    private List<SessionExtraction> extractSessions(List<SessionUnit> sessions, String modelOverride,
                                                     ExtractionProgressTracker.Run run) {
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, parallelism));
        try {
            List<CompletableFuture<SessionExtraction>> futures = sessions.stream()
                    .map(session -> CompletableFuture.supplyAsync(() -> {
                        if (usesDirectPath(session.text())) {
                            List<ExtractedGoal> goals = sessionExtractionService.extract(
                                    session.title(), session.text(), modelOverride);
                            run.increment();
                            return SessionExtraction.direct(session, goals == null ? List.of() : goals);
                        }

                        List<ExtractedGoal> candidates = new ArrayList<>();
                        for (String chunk : documentChunker.chunk(session.text())) {
                            List<ExtractedGoal> extracted = extractionService.extract(chunk, modelOverride);
                            if (extracted != null) {
                                candidates.addAll(extracted);
                            }
                        }
                        List<ConsolidatedGoal> consolidated = candidates.isEmpty()
                                ? List.of()
                                : safeConsolidate(session.title(),
                                        candidates.stream().map(ExtractedGoal::text).toList(), modelOverride);
                        run.increment();
                        return SessionExtraction.fallback(session, candidates, consolidated);
                    }, executor))
                    .toList();
            return futures.stream().map(CompletableFuture::join).toList();
        } finally {
            executor.shutdown();
        }
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

    private String runParams() {
        return "{\"chunk-size\":" + documentChunker.getChunkSize()
                + ",\"direct-max-chars\":" + directMaxChars
                + ",\"parallelism\":" + parallelism + "}";
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
                if (!extraction.goals().isEmpty()) {
                    sessionGoals.add(new ChunkExtraction(extraction.document(), extraction.node(),
                            extraction.goals()));
                }
                continue;
            }

            List<GoalCandidate> saved = new ArrayList<>(extraction.candidates().size());
            for (ExtractedGoal c : extraction.candidates()) {
                saved.add(goalCandidateRepository.save(new GoalCandidate(course, extraction.node(), c.text(),
                        c.kind(), c.sourceSnippet())));
            }
            List<ExtractedGoal> outcomes = new ArrayList<>();
            for (ConsolidatedGoal cg : extraction.consolidated()) {
                if (cg.text() == null || cg.text().isBlank()) {
                    continue;
                }
                List<GoalCandidate> supporters = supportersFor(
                        cg.supporting() == null ? List.of() : cg.supporting(), saved);
                ExtractedGoal outcome = new ExtractedGoal(cg.text(), deriveKind(supporters), snippetFor(supporters));
                outcomes.add(outcome);
                provenance.put(outcome, supporters);
            }
            if (!outcomes.isEmpty()) {
                sessionGoals.add(new ChunkExtraction(extraction.document(), extraction.node(), outcomes));
            }
        }
        return new SessionAssembly(sessionGoals, provenance);
    }

    private List<ConsolidatedGoal> safeConsolidate(String sessionTitle, List<String> candidates, String modelOverride) {
        try {
            List<ConsolidatedGoal> result = sessionGoalConsolidator.consolidate(sessionTitle, candidates, modelOverride);
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
                                     List<ExtractedGoal> goals, List<ExtractedGoal> candidates,
                                     List<ConsolidatedGoal> consolidated) {

        private static SessionExtraction direct(SessionUnit session, List<ExtractedGoal> goals) {
            return new SessionExtraction(session.document(), session.node(), true, goals, List.of(), List.of());
        }

        private static SessionExtraction fallback(SessionUnit session, List<ExtractedGoal> candidates,
                                                  List<ConsolidatedGoal> consolidated) {
            return new SessionExtraction(session.document(), session.node(), false, List.of(), candidates,
                    consolidated);
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

    /** Bloom levels worth clustering into terminal competencies (the higher-Bloom doing/judgement goals). */
    private static final Set<BloomLevel> HIGH_BLOOM =
            EnumSet.of(BloomLevel.APPLY, BloomLevel.ANALYZE, BloomLevel.EVALUATE, BloomLevel.CREATE);

    /**
     * Bloom levels that make a goal a SUB-SKILL — a doing/judgement capability. Only the pure
     * knowledge levels ({@code REMEMBER}/{@code UNDERSTAND}) become tier-3 knowledge leaves, so the
     * lowest tier stays declarative knowledge.
     */
    private static final Set<BloomLevel> SUB_SKILL_BLOOM =
            EnumSet.of(BloomLevel.APPLY, BloomLevel.ANALYZE, BloomLevel.EVALUATE, BloomLevel.CREATE);

    /** How many terminal competencies and gap nodes the competency tree produced. */
    public record CompetencyTreeResult(int competencies, int gaps) {
        static final CompetencyTreeResult NONE = new CompetencyTreeResult(0, 0);
    }

    /**
     * Builds the competency-tree view ALONGSIDE the module goals (not a replacement) in a fixed three
     * tiers — terminal competency → sub-skill → knowledge — under its own {@code COMPETENCY} root:
     *
     * <ol>
     *   <li>cluster the course's higher-Bloom goals into terminal competencies (top-down), each a
     *       {@code TERMINAL}-origin {@link LearningGoal} under the root;</li>
     *   <li>route EVERY session/exercise goal under the competency it serves (full coverage, unlike the
     *       clustering's sparse hints) and split each competency's goals by Bloom into sub-skills
     *       ({@code APPLY}/{@code ANALYZE}/{@code EVALUATE}/{@code CREATE}) and knowledge
     *       ({@code REMEMBER}/{@code UNDERSTAND});</li>
     *   <li>per competency, attach knowledge under the sub-skill it underpins and name the knowledge a
     *       sub-skill needs but the material lacks, as unanchored {@code GAP} goals — so the lowest node
     *       under every sub-skill is a knowledge aspect.</li>
     * </ol>
     *
     * CONTRIBUTES_TO edges thread the tiers (knowledge/gap → sub-skill → terminal). Idempotent: skips
     * when a COMPETENCY root already exists; any synthesis failure is swallowed so it never breaks a run.
     */
    /**
     * Rebuilds ONLY the competency tree for a course from its already-extracted goals — no document
     * re-parsing, extraction, classification or embedding. Tears the existing tree down first so the
     * rebuild starts clean, then runs the same synthesis {@link #buildCompetencyTree} does at the end
     * of a full extraction. Lets the tree be re-tuned cheaply without re-running the costly pipeline.
     *
     * <p>Runs in one transaction so the teardown and rebuild are atomic and the goals loaded here stay
     * managed (their lazy {@code hierarchyNode} is read during synthesis); the transaction spans the
     * synthesis LLM calls, which is acceptable for this occasional admin/tuning operation.
     */
    @Transactional
    public CompetencyTreeResult rebuildCompetencyTree(Long courseId, String modelOverride) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found: " + courseId));
        clearCompetencyTree(course);
        return buildCompetencyTree(course, modelOverride);
    }

    /**
     * Removes a course's competency tree so it can be rebuilt: its {@code TERMINAL}/{@code GAP} goals,
     * their {@code COMPETENCY} root node and the tree's {@code CONTRIBUTES_TO} edges — leaving the
     * extracted/synthesized goals and the module-goal edges untouched. A tree edge is a
     * {@code CONTRIBUTES_TO}/{@code HIERARCHY} link whose target is NOT a synthesized module goal:
     * module synthesis (target always {@code SYNTHESIZED}) is the only other producer of such links,
     * so this partitions them cleanly. Order matters — edges first, then the now-unreferenced tree
     * goals, then their empty root node.
     */
    private void clearCompetencyTree(Course course) {
        List<LearningGoal> treeGoals = goalRepository.findByCourseIdAndOriginIn(
                course.getId(), List.of(GoalOrigin.TERMINAL, GoalOrigin.GAP));

        List<Long> courseGoalIds = goalRepository.findByCourseId(course.getId()).stream()
                .map(LearningGoal::getId).toList();
        List<GoalRelationship> treeEdges = goalRelationshipRepository.findBySourceIdInWithTarget(courseGoalIds).stream()
                .filter(r -> r.getType() == RelationshipType.CONTRIBUTES_TO
                        && r.getOrigin() == RelationshipOrigin.HIERARCHY
                        && r.getTarget().getOrigin() != GoalOrigin.SYNTHESIZED)
                .toList();
        goalRelationshipRepository.deleteAll(treeEdges);

        goalRepository.deleteAll(treeGoals);
        hierarchyNodeRepository.findByCourseId(course.getId()).stream()
                .filter(n -> n.getLevel() == HierarchyLevel.COMPETENCY)
                .forEach(hierarchyNodeRepository::delete);
    }

    private CompetencyTreeResult buildCompetencyTree(Course course, String modelOverride) {
        if (hierarchyNodeRepository.existsByCourseIdAndLevel(course.getId(), HierarchyLevel.COMPETENCY)) {
            return CompetencyTreeResult.NONE;
        }
        // All session/exercise goals are tree candidates: the higher-Bloom ones seed competencies and
        // become sub-skills, the lower-Bloom ones become knowledge leaves.
        List<LearningGoal> candidates = goalRepository.findByCourseIdAndHierarchyNodeIsNotNull(course.getId()).stream()
                .filter(g -> g.getHierarchyNode().getLevel() != HierarchyLevel.MODULE
                        && g.getHierarchyNode().getLevel() != HierarchyLevel.COMPETENCY)
                .toList();
        List<LearningGoal> seeds = candidates.stream()
                .filter(g -> HIGH_BLOOM.contains(g.getBloomLevel()))
                .toList();
        if (seeds.isEmpty()) {
            return CompetencyTreeResult.NONE;
        }

        // Tier 1: cluster the higher-Bloom goals into terminal competencies.
        List<TerminalCompetency> competencies;
        try {
            List<TerminalCompetencySynthesizer.Candidate> seedInput = seeds.stream()
                    .map(g -> new TerminalCompetencySynthesizer.Candidate(g.getText(), g.getBloomLevel().name()))
                    .toList();
            competencies = terminalCompetencySynthesizer.synthesize(seedInput, modelOverride);
        } catch (RuntimeException ex) {
            log.warn("Terminal competency synthesis failed, continuing without a competency tree: {}",
                    ex.getMessage());
            return CompetencyTreeResult.NONE;
        }
        if (competencies == null || competencies.isEmpty()) {
            return CompetencyTreeResult.NONE;
        }

        HierarchyNode competencyRoot = hierarchyNodeRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.COMPETENCY, "Terminal Competencies"));
        List<String> competencyTexts = new ArrayList<>();
        List<LearningGoal> terminalGoals = new ArrayList<>();
        for (TerminalCompetency tc : competencies) {
            if (tc.text() == null || tc.text().isBlank()) {
                continue;
            }
            LearningGoal goal = new LearningGoal(course, tc.text(), GoalKind.IMPLICIT);
            goal.setOrigin(GoalOrigin.TERMINAL);
            goal.setHierarchyNode(competencyRoot);
            TaxonomyClassification classification = safeClassify(tc.text(), modelOverride);
            if (classification != null) {
                goal.setBloomLevel(classification.bloom());
                goal.setSoloLevel(classification.solo());
            }
            goalRepository.saveAndFlush(goal);
            competencyTexts.add(tc.text());
            terminalGoals.add(goal);
        }
        if (terminalGoals.isEmpty()) {
            return CompetencyTreeResult.NONE;
        }

        // Tiers 2/3: assign every candidate under a competency, then expand each into sub-skill→knowledge.
        int gaps = expandCompetencyTree(
                course, competencyRoot, modelOverride, candidates, competencyTexts, terminalGoals);
        log.info("Built competency tree for course {}: {} terminal competencies, {} gaps",
                course.getId(), terminalGoals.size(), gaps);
        return new CompetencyTreeResult(terminalGoals.size(), gaps);
    }

    /**
     * Tiers 2/3 of {@link #buildCompetencyTree}. Pass A routes every candidate goal under the one
     * competency it serves; per competency, the goals are split by Bloom into sub-skills and knowledge,
     * sub-skills are linked to the terminal, and pass B attaches knowledge under sub-skills and emits
     * the gap nodes: tier-2 gaps (missing doing-capabilities, rendered as {@code GAP} sub-skills that
     * bottom out in re-mapped knowledge and/or tier-3 gaps) and tier-3 gaps (missing knowledge beneath
     * an existing sub-skill). All tree edges (knowledge/gap → sub-skill → terminal) originate here, so
     * each goal sits under exactly one competency. Returns the number of gap nodes created.
     */
    private int expandCompetencyTree(Course course, HierarchyNode competencyRoot, String modelOverride,
                                     List<LearningGoal> candidates, List<String> competencyTexts,
                                     List<LearningGoal> terminalGoals) {
        List<CompetencyTreeSynthesizer.Candidate> input = candidates.stream()
                .map(g -> new CompetencyTreeSynthesizer.Candidate(
                        g.getText(), g.getBloomLevel() == null ? null : g.getBloomLevel().name()))
                .toList();
        List<CompetencyAssignment> assignments;
        try {
            assignments = competencyTreeSynthesizer.assign(competencyTexts, input, modelOverride);
        } catch (RuntimeException ex) {
            log.warn("Competency assignment failed, tree will have terminals only: {}", ex.getMessage());
            return 0;
        }

        // Group each goal under the one competency it was assigned to (best fit; -1 = unassigned).
        Map<Integer, List<LearningGoal>> goalsByCompetency = new LinkedHashMap<>();
        for (CompetencyAssignment a : assignments) {
            if (a == null || a.competency() < 0 || a.competency() >= terminalGoals.size()
                    || a.goal() < 0 || a.goal() >= candidates.size()) {
                continue;
            }
            goalsByCompetency.computeIfAbsent(a.competency(), k -> new ArrayList<>()).add(candidates.get(a.goal()));
        }

        int gaps = 0;
        for (int ci = 0; ci < terminalGoals.size(); ci++) {
            List<LearningGoal> assigned = goalsByCompetency.getOrDefault(ci, List.of());
            if (assigned.isEmpty()) {
                continue;
            }
            LearningGoal terminal = terminalGoals.get(ci);
            List<LearningGoal> subSkills = assigned.stream()
                    .filter(g -> SUB_SKILL_BLOOM.contains(g.getBloomLevel()))
                    .toList();
            List<LearningGoal> knowledge = assigned.stream()
                    .filter(g -> !SUB_SKILL_BLOOM.contains(g.getBloomLevel()))
                    .toList();

            if (subSkills.isEmpty()) {
                // No doing-capability landed here: hang the knowledge straight on the terminal so the
                // goals still appear in the tree (degenerate two-tier branch).
                linkContributors(knowledge, terminal);
                continue;
            }
            linkContributors(subSkills, terminal);

            CompetencyExpansion expansion;
            try {
                expansion = competencyTreeSynthesizer.expand(competencyTexts.get(ci),
                        subSkills.stream().map(LearningGoal::getText).toList(),
                        knowledge.stream().map(LearningGoal::getText).toList(),
                        modelOverride);
            } catch (RuntimeException ex) {
                log.warn("Competency expansion failed for '{}', attaching knowledge to terminal: {}",
                        competencyTexts.get(ci), ex.getMessage());
                linkContributors(knowledge, terminal);
                continue;
            }

            // Attach each grounded knowledge goal under the sub-skill it underpins.
            boolean[] linked = new boolean[knowledge.size()];
            for (CompetencyExpansion.KnowledgeLink link : expansion.knowledge()) {
                if (link.knowledgeIndex() < 0 || link.knowledgeIndex() >= knowledge.size()
                        || link.subSkillIndex() < 0 || link.subSkillIndex() >= subSkills.size()) {
                    continue;
                }
                linkContributors(List.of(knowledge.get(link.knowledgeIndex())), subSkills.get(link.subSkillIndex()));
                linked[link.knowledgeIndex()] = true;
            }

            // Tier-2 gaps: a doing-capability the competency needs but no sub-skill covers. Each becomes
            // an unanchored GAP sub-skill under the terminal; to keep the lowest node a knowledge aspect
            // it must bottom out in knowledge — grounded knowledge re-mapped under it and/or tier-3 gaps.
            for (CompetencyExpansion.MissingSubSkill missing : expansion.missingSubSkills()) {
                if (missing.subSkill() == null || missing.subSkill().isBlank()) {
                    continue;
                }
                List<LearningGoal> mappedKnowledge = new ArrayList<>();
                for (int idx : missing.knowledgeIndices()) {
                    if (idx >= 0 && idx < knowledge.size() && !linked[idx]) {
                        mappedKnowledge.add(knowledge.get(idx));
                        linked[idx] = true;
                    }
                }
                List<String> childGaps = missing.knowledgeGaps().stream()
                        .filter(t -> t != null && !t.isBlank())
                        .toList();
                // Invariant: a gap sub-skill that bottoms out in nothing would be a bare leaf — drop it.
                if (mappedKnowledge.isEmpty() && childGaps.isEmpty()) {
                    continue;
                }
                LearningGoal gapSubSkill = createGapGoal(course, competencyRoot, missing.subSkill(), modelOverride);
                linkContributors(List.of(gapSubSkill), terminal);
                gaps++;
                linkContributors(mappedKnowledge, gapSubSkill);
                for (String childGap : childGaps) {
                    linkContributors(List.of(createGapGoal(course, competencyRoot, childGap, modelOverride)),
                            gapSubSkill);
                    gaps++;
                }
            }

            // Knowledge attached to no sub-skill (real or gap) still belongs to the competency: hang it
            // on the terminal.
            List<LearningGoal> leftover = new ArrayList<>();
            for (int k = 0; k < knowledge.size(); k++) {
                if (!linked[k]) {
                    leftover.add(knowledge.get(k));
                }
            }
            linkContributors(leftover, terminal);

            // Tier-3 gaps: missing foundational knowledge beneath an EXISTING sub-skill, so the lowest
            // node under every real sub-skill is a knowledge aspect too.
            for (CompetencyExpansion.Gap gap : expansion.gaps()) {
                if (gap.knowledge() == null || gap.knowledge().isBlank()
                        || gap.subSkillIndex() < 0 || gap.subSkillIndex() >= subSkills.size()) {
                    continue;
                }
                linkContributors(List.of(createGapGoal(course, competencyRoot, gap.knowledge(), modelOverride)),
                        subSkills.get(gap.subSkillIndex()));
                gaps++;
            }
        }
        return gaps;
    }

    /**
     * Creates, classifies and persists one unanchored {@code GAP}-origin goal under the competency
     * root. Gaps are phrased as learning goals, so we classify them along Bloom/SOLO too — but leave
     * them without embedding or source, as they are not grounded in the material.
     */
    private LearningGoal createGapGoal(Course course, HierarchyNode competencyRoot, String text,
                                       String modelOverride) {
        LearningGoal gapGoal = new LearningGoal(course, text.trim(), GoalKind.IMPLICIT);
        gapGoal.setOrigin(GoalOrigin.GAP);
        gapGoal.setHierarchyNode(competencyRoot);
        TaxonomyClassification classification = safeClassify(gapGoal.getText(), modelOverride);
        if (classification != null) {
            gapGoal.setBloomLevel(classification.bloom());
            gapGoal.setSoloLevel(classification.solo());
        }
        return goalRepository.saveAndFlush(gapGoal);
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
        List<ExtractedGoal> goals = new ArrayList<>();
        for (ChunkExtraction de : extractions) {
            for (ExtractedGoal e : de.goals()) {
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
                List<String> texts = goals.subList(from, to).stream().map(ExtractedGoal::text).toList();
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
                    classified.add(new ClassifiedGoal(owners.get(i).document(), owners.get(i).node(),
                            goals.get(i), c));
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

    private TaxonomyClassification safeClassify(String text, String modelOverride) {
        try {
            return taxonomyService.classify(text, modelOverride);
        } catch (RuntimeException ex) {
            log.warn("Taxonomy classification failed for goal, persisting without levels: {}", ex.getMessage());
            return null;
        }
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
            units.add(new Unit(node, start, end));
        }
        if (units.isEmpty()) {
            HierarchyNode node = hierarchyNodeRepository.save(new HierarchyNode(
                    course, moduleRoot, levelFor(document.getFilename()), document.getFilename(), document));
            units.add(new Unit(node, 0, text.length()));
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
    private record Unit(HierarchyNode node, int start, int end) {
    }

    private record SessionUnit(Document document, HierarchyNode node, String title, String text) {
    }

    private record ChunkExtraction(Document document, HierarchyNode node, List<ExtractedGoal> goals) {
    }

    private record ClassifiedGoal(Document document, HierarchyNode node, ExtractedGoal extracted,
                                  TaxonomyClassification classification) {
    }

    private record EnrichedGoal(ClassifiedGoal classified, float[] embedding) {
    }

    public record ExtractionSummary(int documentsProcessed, int goalsCreated, int terminalCompetencies,
                                    int competencyGaps) {
    }
}
