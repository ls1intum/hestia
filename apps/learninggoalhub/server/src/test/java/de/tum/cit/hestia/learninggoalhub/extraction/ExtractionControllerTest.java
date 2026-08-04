package de.tum.cit.hestia.learninggoalhub.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.hestia.learninggoalhub.TestcontainersConfiguration;
import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import de.tum.cit.hestia.learninggoalhub.document.Document;
import de.tum.cit.hestia.learninggoalhub.document.DocumentRepository;
import de.tum.cit.hestia.learninggoalhub.document.DocumentSection;
import de.tum.cit.hestia.learninggoalhub.document.DocumentSectionRepository;
import de.tum.cit.hestia.learninggoalhub.document.PageDescription;
import de.tum.cit.hestia.learninggoalhub.document.PageDescriptionRepository;
import de.tum.cit.hestia.learninggoalhub.document.PageDescriptionService;
import de.tum.cit.hestia.learninggoalhub.embedding.EmbeddingService;
import de.tum.cit.hestia.learninggoalhub.goal.BloomLevel;
import de.tum.cit.hestia.learninggoalhub.goal.EvidenceKind;
import de.tum.cit.hestia.learninggoalhub.goal.GoalKind;
import de.tum.cit.hestia.learninggoalhub.goal.GoalOrigin;
import de.tum.cit.hestia.learninggoalhub.goal.GoalRole;
import de.tum.cit.hestia.learninggoalhub.goal.GoalSource;
import de.tum.cit.hestia.learninggoalhub.goal.GoalSourceRepository;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoal;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoalRepository;
import de.tum.cit.hestia.learninggoalhub.goal.SoloLevel;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyLevel;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNode;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNodeRepository;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationshipRepository;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationship;
import de.tum.cit.hestia.learninggoalhub.relationships.RelationshipOrigin;
import de.tum.cit.hestia.learninggoalhub.relationships.RelationshipType;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyClassification;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyService;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "hestia.extraction.direct-max-chars=80")
class ExtractionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentSectionRepository documentSectionRepository;

    @Autowired
    private PageDescriptionRepository pageDescriptionRepository;

    @Autowired
    private LearningGoalRepository goalRepository;

    @Autowired
    private GoalSourceRepository goalSourceRepository;

    @Autowired
    private GoalRelationshipRepository goalRelationshipRepository;

    @Autowired
    private HierarchyNodeRepository hierarchyRepository;

    @Autowired
    private GoalCandidateRepository goalCandidateRepository;

    @Autowired
    private ExtractionRunRepository extractionRunRepository;

    @Autowired
    private ExtractionRunAuditService extractionRunAuditService;

    @MockitoBean
    private ExtractionService extractionService;

    @MockitoBean
    private SessionExtractionService sessionExtractionService;

    @MockitoBean
    private SessionGoalConsolidator sessionGoalConsolidator;

    @MockitoBean
    private PageDescriptionService pageDescriptionService;

    @MockitoBean
    private EmbeddingService embeddingService;

    @MockitoBean
    private TaxonomyService taxonomyService;

    @MockitoBean
    private TerminalCompetencySynthesizer terminalCompetencySynthesizer;

    @MockitoBean
    private CompetencyAssignmentSynthesizer competencyAssignmentSynthesizer;

    /**
     * Identity consolidation: pass each session's candidates through unchanged (one outcome per
     * candidate, each supported by itself). This isolates the fallback path from the consolidation LLM.
     */
    @BeforeEach
    void stubIdentityConsolidation() {
        when(sessionGoalConsolidator.consolidate(anyString(), anyList(), anyString(), any())).thenAnswer(inv -> {
            List<String> candidates = inv.getArgument(1);
            return java.util.stream.IntStream.range(0, candidates.size())
                    .mapToObj(i -> new ConsolidatedGoal(candidates.get(i), List.of(i)))
                    .toList();
        });
    }

    private static ExtractedSkill skill(ExtractedGoal goal) {
        return skill(goal, 0, 0);
    }

    private static ExtractedSkill skill(ExtractedGoal goal, int sourceStartLine, int sourceEndLine) {
        return new ExtractedSkill(goal.text(), goal.shortLabel(), goal.kind(),
                sourceStartLine, sourceEndLine, List.of());
    }

    private static ExtractedSkill figureSkill(String text, int sourceFigure) {
        return new ExtractedSkill(text, text, GoalKind.IMPLICIT, null, null, sourceFigure, List.of());
    }

    @Test
    void extractionRefusesToReplaceExistingGoalsWithoutForce() throws Exception {
        Course course = courseRepository.save(new Course("Extraction guard"));
        LearningGoal existing = goalRepository.save(new LearningGoal(course, "Existing goal", GoalKind.EXPLICIT));

        mockMvc.perform(post("/api/courses/{id}/extract", course.getId()))
                .andExpect(status().isConflict());

        verify(sessionExtractionService, never()).extract(anyString(), anyString(), anyString(), any());
        assertThat(goalRepository.findById(existing.getId())).isPresent();
    }

    @Test
    void forcedExtractionClearsThePreviousRunBeforeBuildingItAgain() throws Exception {
        Course course = courseRepository.save(new Course("Forced extraction"));
        Document document = documentRepository.save(
                new Document(course, "forced.pdf", "application/pdf", "new outcome"));
        HierarchyNode oldModule = hierarchyRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.MODULE, "Old module"));
        HierarchyNode oldSession = hierarchyRepository.save(
                new HierarchyNode(course, oldModule, HierarchyLevel.SESSION, "Old session", document));
        LearningGoal oldGoal = new LearningGoal(course, "Old goal", GoalKind.EXPLICIT);
        oldGoal.setHierarchyNode(oldSession);
        oldGoal = goalRepository.saveAndFlush(oldGoal);
        goalSourceRepository.save(new GoalSource(oldGoal, document, "old source"));
        goalCandidateRepository.save(new GoalCandidate(course, oldSession, "old candidate",
                GoalKind.EXPLICIT, "old candidate source"));

        when(sessionExtractionService.extract(eq("forced.pdf"), eq("new outcome"), eq("English"), eq(null)))
                .thenReturn(List.of(skill(new ExtractedGoal("New goal", "New goal", GoalKind.EXPLICIT, "new outcome"))));

        mockMvc.perform(post("/api/courses/{id}/extract?force=true", course.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goalsCreated").value(1));

        assertThat(goalRepository.findById(oldGoal.getId())).isEmpty();
        assertThat(goalSourceRepository.findByGoalId(oldGoal.getId())).isEmpty();
        assertThat(goalCandidateRepository.findByCourseId(course.getId())).isEmpty();
        assertThat(hierarchyRepository.findByCourseId(course.getId()))
                .noneMatch(node -> node.getLabel().equals("Old module") || node.getLabel().equals("Old session"));
        assertThat(goalRepository.findByCourseId(course.getId()))
                .extracting(LearningGoal::getText)
                .containsExactly("New goal");
    }

    @Test
    void extractionPersistsGoalsAndSourcesPerDocument() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        Document lecture = documentRepository.save(new Document(course, "lecture.pdf", "application/pdf", "lecture text about TDD"));
        Document exercise = documentRepository.save(new Document(course, "exercise.pdf", "application/pdf", "exercise on refactoring"));

        when(sessionExtractionService.extract(eq("lecture.pdf"), eq("lecture text about TDD"), eq("English"), eq(null))).thenReturn(List.of(
                skill(new ExtractedGoal("Apply test-driven development.", "Test-Driven Development", GoalKind.EXPLICIT,
                        "...write a failing test first..."), 99, 99),
                skill(new ExtractedGoal("Value short feedback loops.", "Feedback Loops", GoalKind.IMPLICIT,
                        "...keep tests fast..."))
        ));
        when(sessionExtractionService.extract(eq("exercise.pdf"), eq("exercise on refactoring"), eq("English"), eq(null))).thenReturn(List.of(
                skill(new ExtractedGoal("Refactor without changing behaviour.", "Behaviour-Preserving Refactoring",
                        GoalKind.EXPLICIT, "...extract method..."))
        ));
        stubEmbedAll(Map.of(
                "Apply test-driven development.", orthogonalEmbedding(0),
                "Value short feedback loops.", orthogonalEmbedding(1),
                "Refactor without changing behaviour.", orthogonalEmbedding(2)));

        mockMvc.perform(post("/api/courses/{id}/extract", course.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentsProcessed").value(2))
                .andExpect(jsonPath("$.goalsCreated").value(3));

        List<LearningGoal> goals = goalRepository.findByCourseId(course.getId());
        assertThat(goals).hasSize(3);
        assertThat(goals).extracting(LearningGoal::getKind)
                .containsExactlyInAnyOrder(GoalKind.EXPLICIT, GoalKind.IMPLICIT, GoalKind.EXPLICIT);
        assertThat(goals).extracting(LearningGoal::getShortLabel)
                .containsExactlyInAnyOrder("Test-Driven Development", "Feedback Loops",
                        "Behaviour-Preserving Refactoring");
        assertThat(goals).allSatisfy(g -> assertThat(g.getEmbedding()).hasSize(4096));
        // All goals are embedded in one batched call rather than one call per goal.
        verify(embeddingService).embedAll(anyList());

        // Direct extraction does not create legacy raw candidate rows.
        List<GoalCandidate> candidates = goalCandidateRepository.findByCourseId(course.getId());
        assertThat(candidates).isEmpty();

        long sourcesForLecture = goalSourceRepository.findAll().stream()
                .filter(s -> s.getDocument().getId().equals(lecture.getId()))
                .count();
        long sourcesForExercise = goalSourceRepository.findAll().stream()
                .filter(s -> s.getDocument().getId().equals(exercise.getId()))
                .count();
        assertThat(sourcesForLecture).isEqualTo(2);
        assertThat(sourcesForExercise).isEqualTo(1);

        LearningGoal fabricatedGoal = goals.stream()
                .filter(g -> g.getText().equals("Apply test-driven development."))
                .findFirst()
                .orElseThrow();
        assertThat(goalSourceRepository.findByGoalId(fabricatedGoal.getId()))
                .singleElement()
                .satisfies(source -> {
                    assertThat(source.getSnippet()).isEmpty();
                    assertThat(source.getPage()).isNull();
                    assertThat(source.getGroundingQuality()).isEqualTo(SourceMatchQuality.NONE);
                    assertThat(source.isGrounded()).isFalse();
                    assertThat(source.getUnverifiedSnippet()).isNull();
                });

        LearningGoal exactGoal = goals.stream()
                .filter(g -> g.getText().equals("Value short feedback loops."))
                .findFirst()
                .orElseThrow();
        assertThat(goalSourceRepository.findByGoalId(exactGoal.getId()))
                .singleElement()
                .satisfies(source -> {
                    assertThat(source.getSnippet()).isEqualTo("lecture text about TDD");
                    assertThat(source.getGroundingQuality()).isEqualTo(SourceMatchQuality.EXACT_IN_SESSION);
                    assertThat(source.isGrounded()).isTrue();
                });

        ExtractionRun run = extractionRunRepository.findByCourseId(course.getId()).stream()
                .findFirst()
                .orElseThrow();
        assertThat(run.getStatus()).isEqualTo(ExtractionRun.Status.SUCCEEDED);
        assertThat(run.getPromptVersion()).isEqualTo(SessionExtractionService.PROMPT_VERSION);
        assertThat(run.getGoalsCreated()).isEqualTo(3);
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(run.getParams()).contains("direct-max-chars");
    }

    @Test
    void auditServicePersistsRunningThenSucceededInSeparateTransactions() {
        Course course = courseRepository.save(new Course("Audit lifecycle"));

        Long runId = extractionRunAuditService.start(course.getId(), null, "direct-v1",
                "{\"chunk-size\":16000,\"direct-max-chars\":80000,\"parallelism\":16}");
        assertThat(extractionRunRepository.findById(runId).orElseThrow().getStatus())
                .isEqualTo(ExtractionRun.Status.RUNNING);

        extractionRunAuditService.finish(runId, ExtractionRun.Status.SUCCEEDED, null, 4, "direct-v1");

        ExtractionRun run = extractionRunRepository.findById(runId).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(ExtractionRun.Status.SUCCEEDED);
        assertThat(run.getGoalsCreated()).isEqualTo(4);
        assertThat(run.getFinishedAt()).isNotNull();
    }

    @Test
    void directSourcesPreferLinesThenUseFiguresAndRejectInvalidFigureIndices() throws Exception {
        Course course = courseRepository.save(new Course("Figure source precedence"));
        String rawText = "verbatim line";
        Document document = documentRepository.save(
                new Document(course, "figures.pdf", "application/pdf", rawText));
        document.setPageOffsets(new int[]{0, rawText.length(), rawText.length()});
        documentRepository.saveAndFlush(document);
        documentSectionRepository.saveAndFlush(new DocumentSection(
                document, 0, "Section", 0, rawText.length(), 1, 2));
        pageDescriptionRepository.saveAndFlush(
                new PageDescription(document, 2, "A diagram teaches the process.", "vision-test"));

        when(sessionExtractionService.extract(eq("Section"), eq(rawText), eq("English"), eq(null), anyList()))
                .thenReturn(List.of(
                        new ExtractedSkill("Text outcome", "Text", GoalKind.IMPLICIT,
                                0, 0, 0, List.of()),
                        figureSkill("Figure outcome", 0),
                        figureSkill("Unsupported outcome", 4)));
        stubEmbedAll(Map.of(
                "Text outcome", orthogonalEmbedding(0),
                "Figure outcome", orthogonalEmbedding(1),
                "Unsupported outcome", orthogonalEmbedding(2)));

        mockMvc.perform(post("/api/courses/{id}/extract", course.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.textSources").value(1))
                .andExpect(jsonPath("$.figureSources").value(1))
                .andExpect(jsonPath("$.unsupportedSources").value(1));

        Map<Long, String> goalTextById = goalRepository.findByCourseId(course.getId()).stream()
                .collect(Collectors.toMap(LearningGoal::getId, LearningGoal::getText));
        Map<String, GoalSource> sources = goalSourceRepository.findAll().stream()
                .filter(source -> source.getDocument().getId().equals(document.getId()))
                .collect(Collectors.toMap(source -> goalTextById.get(source.getGoal().getId()), source -> source));
        assertThat(sources.get("Text outcome").getEvidenceKind()).isEqualTo(EvidenceKind.TEXT);
        assertThat(sources.get("Figure outcome").getEvidenceKind()).isEqualTo(EvidenceKind.FIGURE);
        assertThat(sources.get("Figure outcome").getPage()).isEqualTo(2);
        assertThat(sources.get("Figure outcome").getSnippet()).isEmpty();
        assertThat(sources.get("Unsupported outcome").getEvidenceKind()).isEqualTo(EvidenceKind.UNSUPPORTED);
    }

    @Test
    void oversizedSessionUsesFallbackCandidatesAndProvenance() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        String oversizedText = "This session is deliberately longer than the direct extraction threshold. "
                .repeat(3);
        Document document = documentRepository.save(
                new Document(course, "fallback.pdf", "application/pdf", oversizedText));

        when(extractionService.extract(eq(oversizedText), eq("English"), eq(null))).thenReturn(List.of(
                new ExtractedGoal("Apply the fallback procedure.", GoalKind.EXPLICIT, "...fallback procedure...")));
        when(sessionGoalConsolidator.consolidate(eq("fallback.pdf"), anyList(), eq("English"), eq(null))).thenReturn(List.of(
                new ConsolidatedGoal("Apply the fallback procedure.", "Fallback Procedure", List.of(0))));
        stubEmbedAll(Map.of("Apply the fallback procedure.", orthogonalEmbedding(0)));

        mockMvc.perform(post("/api/courses/{id}/extract", course.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goalsCreated").value(1));

        verify(sessionExtractionService, never()).extract(anyString(), anyString(), anyString(), eq(null));
        List<GoalCandidate> candidates = goalCandidateRepository.findByCourseId(course.getId());
        assertThat(candidates)
                .singleElement()
                .satisfies(candidate -> assertThat(candidate.getConsolidatedGoal()).isNotNull());
        assertThat(extractionRunRepository.findByCourseId(course.getId()))
                .singleElement()
                .satisfies(run -> assertThat(run.getPromptVersion()).isEqualTo("chunked-v3"));
        assertThat(goalSourceRepository.findAll())
                .filteredOn(source -> source.getDocument().getId().equals(document.getId()))
                .hasSize(1);
        assertThat(goalSourceRepository.findAll())
                .filteredOn(source -> source.getDocument().getId().equals(document.getId()))
                .singleElement()
                .extracting(GoalSource::getEvidenceKind)
                .isEqualTo(EvidenceKind.UNSUPPORTED);
        assertThat(goalRepository.findByCourseId(course.getId()))
                .singleElement()
                .extracting(LearningGoal::getShortLabel)
                .isEqualTo("Fallback Procedure");
    }

    @Test
    void extractionPersistsShortLabelOnTerminalCompetencies() throws Exception {
        Course course = courseRepository.save(new Course("Terminal competency labels"));
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", "Apply the capability."));

        when(sessionExtractionService.extract(eq("session.pdf"), eq("Apply the capability."), eq("English"), eq(null)))
                .thenReturn(List.of(skill(new ExtractedGoal("Apply the capability.", "Source Capability", GoalKind.EXPLICIT,
                        "...capability..."))));
        when(taxonomyService.classifyBatch(anyList(), eq(null)))
                .thenReturn(List.of(new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL)));
        when(terminalCompetencySynthesizer.synthesize(anyList(), eq("English"), eq(null)))
                .thenReturn(List.of(new TerminalCompetency("Perform the capability.", "Terminal Capability")));
        when(competencyAssignmentSynthesizer.assign(anyList(), anyList(), eq(null)))
                .thenReturn(Map.of(0, 0));
        stubEmbedAll(Map.of("Apply the capability.", orthogonalEmbedding(0)));

        mockMvc.perform(post("/api/courses/{id}/extract", course.getId()))
                .andExpect(status().isOk());

        assertThat(goalRepository.findByCourseId(course.getId()))
                .filteredOn(g -> g.getOrigin() == GoalOrigin.TERMINAL)
                .singleElement()
                .extracting(LearningGoal::getShortLabel)
                .isEqualTo("Terminal Capability");
    }

    /**
     * A goal the assignment step could not place must still reach the tree. The client only renders
     * goals reachable from a terminal, so leaving it unlinked would make it invisible — the catch-all
     * keeps it visible without asserting it belongs to a competency it does not serve.
     */
    @Test
    void goalsMatchingNoCompetencyLandUnderACatchAllTerminal() throws Exception {
        Course course = courseRepository.save(new Course("Unmatched goals"));
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", "Apply the capability."));

        when(sessionExtractionService.extract(eq("session.pdf"), eq("Apply the capability."), eq("English"), eq(null)))
                .thenReturn(List.of(skill(new ExtractedGoal("Apply the capability.", "Source Capability", GoalKind.EXPLICIT,
                        "...capability..."))));
        when(taxonomyService.classifyBatch(anyList(), eq(null)))
                .thenReturn(List.of(new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL)));
        when(terminalCompetencySynthesizer.synthesize(anyList(), eq("English"), eq(null)))
                .thenReturn(List.of(new TerminalCompetency("Perform the capability.", "Terminal Capability")));
        // The model placed the only goal nowhere.
        when(competencyAssignmentSynthesizer.assign(anyList(), anyList(), eq(null)))
                .thenReturn(java.util.Collections.singletonMap(0, null));
        stubEmbedAll(Map.of("Apply the capability.", orthogonalEmbedding(0)));

        mockMvc.perform(post("/api/courses/{id}/extract", course.getId()))
                .andExpect(status().isOk());

        List<LearningGoal> terminals = goalRepository.findByCourseId(course.getId()).stream()
                .filter(g -> g.getOrigin() == GoalOrigin.TERMINAL)
                .toList();
        assertThat(terminals).extracting(LearningGoal::getShortLabel)
                .contains("Additional Course Outcomes");
        LearningGoal catchAll = terminals.stream()
                .filter(g -> "Additional Course Outcomes".equals(g.getShortLabel()))
                .findFirst()
                .orElseThrow();
        // The catch-all is a container, so it stays unclassified rather than carrying a Bloom level.
        assertThat(catchAll.getBloomLevel()).isNull();
        LearningGoal extracted = goalRepository.findByCourseId(course.getId()).stream()
                .filter(g -> g.getOrigin() != GoalOrigin.TERMINAL)
                .findFirst()
                .orElseThrow();
        assertThat(goalRelationshipRepository.findBySourceId(extracted.getId()))
                .extracting(r -> r.getTarget().getId())
                .contains(catchAll.getId());
    }

    /**
     * The naming call may propose a competency the assignment then gives nothing. The course does not
     * build toward it, so it must not reach the tree as a childless top-level node.
     */
    @Test
    void competenciesThatReceiveNoGoalsAreNotPersisted() throws Exception {
        Course course = courseRepository.save(new Course("Empty competency"));
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", "Apply the capability."));

        when(sessionExtractionService.extract(eq("session.pdf"), eq("Apply the capability."), eq("English"), eq(null)))
                .thenReturn(List.of(skill(new ExtractedGoal("Apply the capability.", "Source Capability", GoalKind.EXPLICIT,
                        "...capability..."))));
        when(taxonomyService.classifyBatch(anyList(), eq(null)))
                .thenReturn(List.of(new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL)));
        when(terminalCompetencySynthesizer.synthesize(anyList(), eq("English"), eq(null)))
                .thenReturn(List.of(new TerminalCompetency("Perform the capability.", "Claimed"),
                        new TerminalCompetency("Perform something nobody teaches.", "Unclaimed")));
        // Everything lands on the first competency; the second is left empty.
        when(competencyAssignmentSynthesizer.assign(anyList(), anyList(), eq(null)))
                .thenReturn(Map.of(0, 0));
        stubEmbedAll(Map.of("Apply the capability.", orthogonalEmbedding(0)));

        mockMvc.perform(post("/api/courses/{id}/extract", course.getId()))
                .andExpect(status().isOk());

        assertThat(goalRepository.findByCourseId(course.getId()))
                .filteredOn(g -> g.getOrigin() == GoalOrigin.TERMINAL)
                .singleElement()
                .extracting(LearningGoal::getShortLabel)
                .isEqualTo("Claimed");
    }

    /**
     * The rebuild exists so the tree can be iterated on without re-reading documents: it replaces the
     * terminals and their edges while the extracted goals stay exactly where they were.
     */
    @Test
    void rebuildReplacesTheTreeAndKeepsExtractedGoals() throws Exception {
        Course course = courseRepository.save(new Course("Rebuild"));
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", "Apply the capability."));

        when(sessionExtractionService.extract(eq("session.pdf"), eq("Apply the capability."), eq("English"), eq(null)))
                .thenReturn(List.of(skill(new ExtractedGoal("Apply the capability.", "Source Capability", GoalKind.EXPLICIT,
                        "...capability..."))));
        when(taxonomyService.classifyBatch(anyList(), eq(null)))
                .thenReturn(List.of(new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL)));
        when(terminalCompetencySynthesizer.synthesize(anyList(), eq("English"), eq(null)))
                .thenReturn(List.of(new TerminalCompetency("Perform the capability.", "First Label")));
        when(competencyAssignmentSynthesizer.assign(anyList(), anyList(), eq(null)))
                .thenReturn(Map.of(0, 0));
        stubEmbedAll(Map.of("Apply the capability.", orthogonalEmbedding(0)));

        mockMvc.perform(post("/api/courses/{id}/extract", course.getId()))
                .andExpect(status().isOk());
        List<Long> extractedIdsBefore = goalRepository.findByCourseId(course.getId()).stream()
                .filter(g -> g.getOrigin() != GoalOrigin.TERMINAL)
                .map(LearningGoal::getId)
                .toList();
        LearningGoal legacySkill = goalRepository.findById(extractedIdsBefore.getFirst()).orElseThrow();
        legacySkill.setRole(null);
        goalRepository.saveAndFlush(legacySkill);
        LearningGoal legacyKnowledge = new LearningGoal(course, "Explain the legacy basics.", GoalKind.EXPLICIT);
        legacyKnowledge.setRole(null);
        legacyKnowledge.setBloomLevel(BloomLevel.UNDERSTAND);
        legacyKnowledge.setHierarchyNode(legacySkill.getHierarchyNode());
        legacyKnowledge = goalRepository.saveAndFlush(legacyKnowledge);
        goalRelationshipRepository.save(new GoalRelationship(legacyKnowledge, legacySkill,
                RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));
        extractedIdsBefore = java.util.stream.Stream.concat(extractedIdsBefore.stream(),
                java.util.stream.Stream.of(legacyKnowledge.getId())).toList();

        when(terminalCompetencySynthesizer.synthesize(anyList(), eq("English"), eq(null)))
                .thenReturn(List.of(new TerminalCompetency("Perform the capability.", "Second Label")));

        mockMvc.perform(post("/api/courses/{id}/competency-tree", course.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.competencies").value(1))
                .andExpect(jsonPath("$.unmatchedGoals").value(0));

        assertThat(goalRepository.findByCourseId(course.getId()))
                .filteredOn(g -> g.getOrigin() == GoalOrigin.TERMINAL)
                .singleElement()
                .extracting(LearningGoal::getShortLabel)
                .isEqualTo("Second Label");
        assertThat(goalRepository.findByCourseId(course.getId()).stream()
                .filter(g -> g.getOrigin() != GoalOrigin.TERMINAL)
                .map(LearningGoal::getId))
                .containsExactlyInAnyOrderElementsOf(extractedIdsBefore);
        assertThat(hierarchyRepository.findByCourseId(course.getId()))
                .filteredOn(n -> n.getLevel() == HierarchyLevel.COMPETENCY)
                .hasSize(1);
        assertThat(goalRepository.findById(legacySkill.getId()).orElseThrow().getRole()).isNull();
        assertThat(goalRelationshipRepository.findBySourceId(legacyKnowledge.getId())).isEmpty();
    }

    /** Extraction creates knowledge → skill edges, and rebuilding preserves them while replacing tree edges. */
    @Test
    void rebuildLeavesNoEdgesFromThePreviousTree() throws Exception {
        Course course = courseRepository.save(new Course("Rebuild edges"));
        documentRepository.save(new Document(course, "session.pdf", "application/pdf",
                "Apply the capability.\n\nUnderstand the basics."));

        when(sessionExtractionService.extract(eq("session.pdf"), anyString(), eq("English"), eq(null)))
                .thenReturn(List.of(
                        new ExtractedSkill("Apply the capability.", "Capability", GoalKind.EXPLICIT,
                                0, 0, List.of(new ExtractedSkill.Knowledge(
                                        "Understand the basics.", "Basics", GoalKind.EXPLICIT, 1, 1)))));
        when(taxonomyService.classifyBatch(anyList(), eq(null))).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream()
                    .map(t -> t.startsWith("Understand")
                            ? new TaxonomyClassification(BloomLevel.UNDERSTAND, SoloLevel.UNISTRUCTURAL)
                            : new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL))
                    .toList();
        });
        when(terminalCompetencySynthesizer.synthesize(anyList(), eq("English"), eq(null)))
                .thenReturn(List.of(new TerminalCompetency("Perform the capability.", "Capability")));
        when(competencyAssignmentSynthesizer.assign(anyList(), anyList(), eq(null)))
                .thenReturn(Map.of(0, 0));
        stubEmbedAll(Map.of("Apply the capability.", orthogonalEmbedding(0),
                "Understand the basics.", orthogonalEmbedding(1)));

        mockMvc.perform(post("/api/courses/{id}/extract", course.getId()))
                .andExpect(status().isOk());

        LearningGoal knowledgeBefore = goalRepository.findByCourseId(course.getId()).stream()
                .filter(g -> "Understand the basics.".equals(g.getText()))
                .findFirst()
                .orElseThrow();
        LearningGoal skillBefore = goalRepository.findByCourseId(course.getId()).stream()
                .filter(g -> "Apply the capability.".equals(g.getText()))
                .findFirst()
                .orElseThrow();
        assertThat(knowledgeBefore.getRole()).isEqualTo(GoalRole.KNOWLEDGE);
        assertThat(skillBefore.getRole()).isEqualTo(GoalRole.SKILL);
        assertThat(goalSourceRepository.findByGoalId(skillBefore.getId()))
                .singleElement()
                .extracting(GoalSource::getSnippet)
                .isEqualTo("Apply the capability.");
        assertThat(goalSourceRepository.findByGoalId(knowledgeBefore.getId()))
                .singleElement()
                .extracting(GoalSource::getSnippet)
                .isEqualTo("Understand the basics.");
        assertThat(goalRelationshipRepository.findBySourceId(knowledgeBefore.getId()))
                .extracting(r -> r.getTarget().getId())
                .containsExactly(skillBefore.getId());

        when(terminalCompetencySynthesizer.synthesize(anyList(), eq("English"), eq(null)))
                .thenReturn(List.of(new TerminalCompetency("Perform the capability.", "Rebuilt Capability")));
        mockMvc.perform(post("/api/courses/{id}/competency-tree", course.getId()))
                .andExpect(status().isOk());

        LearningGoal knowledge = goalRepository.findByCourseId(course.getId()).stream()
                .filter(g -> "Understand the basics.".equals(g.getText()))
                .findFirst()
                .orElseThrow();
        LearningGoal skill = goalRepository.findByCourseId(course.getId()).stream()
                .filter(g -> "Apply the capability.".equals(g.getText()))
                .findFirst()
                .orElseThrow();
        LearningGoal terminal = goalRepository.findByCourseIdAndOriginIn(course.getId(), List.of(GoalOrigin.TERMINAL))
                .stream()
                .findFirst()
                .orElseThrow();
        // Compare ids, not text: the edge's target is a lazy proxy and this runs outside a session.
        assertThat(goalRelationshipRepository.findBySourceId(knowledge.getId()))
                .as("the extraction edge must survive the rebuild")
                .extracting(r -> r.getTarget().getId())
                .containsExactly(skill.getId());
        assertThat(goalRelationshipRepository.findBySourceId(skill.getId()))
                .extracting(r -> r.getTarget().getId())
                .containsExactly(terminal.getId());
    }

    /**
     * A rebuild synthesises the replacement before it destroys anything, so a model outage mid-rebuild
     * cannot leave the course with its tree deleted and nothing in its place.
     */
    @Test
    void failedRebuildLeavesTheExistingTreeIntact() throws Exception {
        Course course = courseRepository.save(new Course("Rebuild failure"));
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", "Apply the capability."));

        when(sessionExtractionService.extract(eq("session.pdf"), eq("Apply the capability."), eq("English"), eq(null)))
                .thenReturn(List.of(skill(new ExtractedGoal("Apply the capability.", "Source Capability", GoalKind.EXPLICIT,
                        "...capability..."))));
        when(taxonomyService.classifyBatch(anyList(), eq(null)))
                .thenReturn(List.of(new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL)));
        when(terminalCompetencySynthesizer.synthesize(anyList(), eq("English"), eq(null)))
                .thenReturn(List.of(new TerminalCompetency("Perform the capability.", "Survivor")));
        when(competencyAssignmentSynthesizer.assign(anyList(), anyList(), eq(null)))
                .thenReturn(Map.of(0, 0));
        stubEmbedAll(Map.of("Apply the capability.", orthogonalEmbedding(0)));

        mockMvc.perform(post("/api/courses/{id}/extract", course.getId()))
                .andExpect(status().isOk());

        // The model goes down before the rebuild can synthesise a replacement.
        when(terminalCompetencySynthesizer.synthesize(anyList(), eq("English"), eq(null)))
                .thenThrow(new IllegalStateException("model unavailable"));

        mockMvc.perform(post("/api/courses/{id}/competency-tree", course.getId()))
                .andExpect(status().isBadGateway());

        assertThat(goalRepository.findByCourseId(course.getId()))
                .filteredOn(g -> g.getOrigin() == GoalOrigin.TERMINAL)
                .singleElement()
                .extracting(LearningGoal::getShortLabel)
                .isEqualTo("Survivor");
    }

    /**
     * A rebuild replaces exactly the nodes an instructor may have approved or typed, and nothing
     * records what they were — so it refuses rather than destroying them silently.
     */
    @Test
    void rebuildRefusesWhenTheTreeHoldsApprovedWork() throws Exception {
        Course course = courseRepository.save(new Course("Rebuild guard"));
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", "Apply the capability."));

        when(sessionExtractionService.extract(eq("session.pdf"), eq("Apply the capability."), eq("English"), eq(null)))
                .thenReturn(List.of(skill(new ExtractedGoal("Apply the capability.", "Source Capability", GoalKind.EXPLICIT,
                        "...capability..."))));
        when(taxonomyService.classifyBatch(anyList(), eq(null)))
                .thenReturn(List.of(new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL)));
        when(terminalCompetencySynthesizer.synthesize(anyList(), eq("English"), eq(null)))
                .thenReturn(List.of(new TerminalCompetency("Perform the capability.", "Terminal Capability")));
        when(competencyAssignmentSynthesizer.assign(anyList(), anyList(), eq(null)))
                .thenReturn(Map.of(0, 0));
        stubEmbedAll(Map.of("Apply the capability.", orthogonalEmbedding(0)));

        mockMvc.perform(post("/api/courses/{id}/extract", course.getId()))
                .andExpect(status().isOk());

        LearningGoal terminal = goalRepository.findByCourseId(course.getId()).stream()
                .filter(g -> g.getOrigin() == GoalOrigin.TERMINAL)
                .findFirst()
                .orElseThrow();
        terminal.setStatus(de.tum.cit.hestia.learninggoalhub.goal.GoalStatus.APPROVED);
        goalRepository.save(terminal);

        mockMvc.perform(post("/api/courses/{id}/competency-tree", course.getId()))
                .andExpect(status().isConflict());
        assertThat(goalRepository.findByCourseId(course.getId()))
                .anyMatch(g -> g.getOrigin() == GoalOrigin.TERMINAL);

        // The override is what makes the destruction deliberate.
        mockMvc.perform(post("/api/courses/{id}/competency-tree?force=true", course.getId()))
                .andExpect(status().isOk());
        assertThat(goalRepository.findByCourseId(course.getId()))
                .filteredOn(g -> g.getOrigin() == GoalOrigin.TERMINAL)
                .singleElement()
                .satisfies(g -> assertThat(g.getStatus())
                        .isEqualTo(de.tum.cit.hestia.learninggoalhub.goal.GoalStatus.PENDING));
    }

    /**
     * Without a single placement the tree would be one bucket holding the whole course, which is
     * worse than no tree. Nothing may be persisted in that case — not even the competency root.
     */
    @Test
    void failedAssignmentLeavesNoCompetencyTree() throws Exception {
        Course course = courseRepository.save(new Course("Assignment failure"));
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", "Apply the capability."));

        when(sessionExtractionService.extract(eq("session.pdf"), eq("Apply the capability."), eq("English"), eq(null)))
                .thenReturn(List.of(skill(new ExtractedGoal("Apply the capability.", "Source Capability", GoalKind.EXPLICIT,
                        "...capability..."))));
        when(taxonomyService.classifyBatch(anyList(), eq(null)))
                .thenReturn(List.of(new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL)));
        when(terminalCompetencySynthesizer.synthesize(anyList(), eq("English"), eq(null)))
                .thenReturn(List.of(new TerminalCompetency("Perform the capability.", "Terminal Capability")));
        when(competencyAssignmentSynthesizer.assign(anyList(), anyList(), eq(null)))
                .thenThrow(new IllegalStateException("assignment call failed"));
        stubEmbedAll(Map.of("Apply the capability.", orthogonalEmbedding(0)));

        mockMvc.perform(post("/api/courses/{id}/extract", course.getId()))
                .andExpect(status().isOk());

        assertThat(goalRepository.findByCourseId(course.getId()))
                .noneMatch(g -> g.getOrigin() == GoalOrigin.TERMINAL);
        assertThat(hierarchyRepository.findByCourseId(course.getId()))
                .noneMatch(n -> n.getLevel() == HierarchyLevel.COMPETENCY);
    }

    @Test
    void failedExtractionLeavesFailedAuditRun() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        String text = "short session text";
        documentRepository.save(new Document(course, "failed.pdf", "application/pdf", text));
        when(sessionExtractionService.extract(eq("failed.pdf"), eq(text), eq("English"), eq(null)))
                .thenThrow(new RuntimeException("direct extraction failed"));

        mockMvc.perform(post("/api/courses/{id}/extract", course.getId()))
                .andExpect(status().isInternalServerError());

        assertThat(extractionRunRepository.findByCourseId(course.getId()))
                .singleElement()
                .satisfies(run -> {
                    assertThat(run.getStatus()).isEqualTo(ExtractionRun.Status.FAILED);
                    assertThat(run.getError()).isEqualTo("direct extraction failed");
                    assertThat(run.getFinishedAt()).isNotNull();
                    assertThat(run.getGoalsCreated()).isNull();
                });
    }

    @Test
    void identicalGoalsAcrossDocumentsRemainSeparateGoals() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        Document lecture = documentRepository.save(new Document(course, "lecture.pdf", "application/pdf", "lecture body"));
        Document exercise = documentRepository.save(new Document(course, "exercise.pdf", "application/pdf", "exercise body"));

        when(sessionExtractionService.extract(eq("lecture.pdf"), eq("lecture body"), eq("English"), eq(null))).thenReturn(List.of(
                skill(new ExtractedGoal("Apply test-driven development.", GoalKind.EXPLICIT, "...lecture snippet..."))
        ));
        when(sessionExtractionService.extract(eq("exercise.pdf"), eq("exercise body"), eq("English"), eq(null))).thenReturn(List.of(
                skill(new ExtractedGoal("Apply TDD when writing code.", GoalKind.EXPLICIT, "...exercise snippet..."))
        ));
        // Both goals get the exact same embedding; extraction no longer performs embedding deduplication.
        stubEmbedAll(Map.of());

        mockMvc.perform(post("/api/courses/{id}/extract", course.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goalsCreated").value(2));

        List<LearningGoal> goals = goalRepository.findByCourseId(course.getId());
        assertThat(goals).hasSize(2);
        assertThat(goals)
                .flatExtracting(goal -> goalSourceRepository.findByGoalId(goal.getId()))
                .extracting(s -> s.getDocument().getId())
                .containsExactlyInAnyOrder(lecture.getId(), exercise.getId());
    }

    @Test
    void duplicateExtractionsFromSameDocumentCreateSeparateGoals() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        Document lecture = documentRepository.save(new Document(course, "lecture.pdf", "application/pdf", "lecture body"));

        // A direct response can contain closely related goals; each enriched goal is persisted.
        when(sessionExtractionService.extract(eq("lecture.pdf"), eq("lecture body"), eq("English"), eq(null))).thenReturn(List.of(
                skill(new ExtractedGoal("Apply TDD.", GoalKind.EXPLICIT, "...first snippet...")),
                skill(new ExtractedGoal("Apply TDD (rephrased).", GoalKind.EXPLICIT, "...second snippet..."))
        ));
        stubEmbedAll(Map.of());

        mockMvc.perform(post("/api/courses/{id}/extract", course.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goalsCreated").value(2));

        List<LearningGoal> goals = goalRepository.findByCourseId(course.getId());
        assertThat(goals).hasSize(2);
        assertThat(goals)
                .flatExtracting(goal -> goalSourceRepository.findByGoalId(goal.getId()))
                .hasSize(2)
                .allSatisfy(s -> assertThat(s.getDocument().getId()).isEqualTo(lecture.getId()));
    }

    @Test
    void unknownCourseReturns404() throws Exception {
        mockMvc.perform(post("/api/courses/{id}/extract", 999999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void statusReturnsNoContentBeforeAnyRun() throws Exception {
        Course course = courseRepository.save(new Course("Untouched"));

        mockMvc.perform(get("/api/courses/{id}/extract/status", course.getId()))
                .andExpect(status().isNoContent());
    }

    @Test
    void statusReportsSucceededWithSummaryAfterExtraction() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        documentRepository.save(new Document(course, "lecture.pdf", "application/pdf", "lecture text about TDD"));

        when(sessionExtractionService.extract(eq("lecture.pdf"), eq("lecture text about TDD"), eq("English"), eq(null))).thenReturn(List.of(
                skill(new ExtractedGoal("Apply test-driven development.", GoalKind.EXPLICIT, "...failing test first..."))
        ));
        stubEmbedAll(Map.of());

        mockMvc.perform(post("/api/courses/{id}/extract", course.getId()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/courses/{id}/extract/status", course.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.phase").value("PERSISTING"))
                .andExpect(jsonPath("$.summary.goalsCreated").value(1))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void extractionSplitsOneDocumentIntoItsStructuralSectionsAndRoutesChunksByOffset() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        // One uploaded PDF whose bookmarks split it into a lecture chapter and an exercise chapter.
        // The runner chunks each section's character range and attaches its goals to that section.
        String sessionText = "Lecture 3 Testing. Students apply TDD by writing a failing test first.\n";
        String exerciseText = "Exercise 3.2 Kata. Practise the refactoring kata until it is fluent.\n";
        Document combined = documentRepository.save(new Document(course, "chapters.pdf", "application/pdf",
                sessionText + exerciseText));
        documentSectionRepository.save(
                new DocumentSection(combined, 0, "Lecture 3: Testing", 0, sessionText.length()));
        documentSectionRepository.save(new DocumentSection(combined, 1, "Exercise 3.2: Kata",
                sessionText.length(), sessionText.length() + exerciseText.length()));

        when(sessionExtractionService.extract(eq("Lecture 3: Testing"), eq(sessionText), eq("English"), eq(null))).thenReturn(List.of(
                skill(new ExtractedGoal("Apply TDD.", GoalKind.EXPLICIT, "...failing test first...")),
                skill(new ExtractedGoal("Understand SE scope.", GoalKind.IMPLICIT, "...overview..."))
        ));
        when(sessionExtractionService.extract(eq("Exercise 3.2: Kata"), eq(exerciseText), eq("English"), eq(null))).thenReturn(List.of(
                skill(new ExtractedGoal("Practise TDD kata.", GoalKind.EXPLICIT, "...kata..."))
        ));
        stubEmbedAll(Map.of(
                "Apply TDD.", orthogonalEmbedding(0),
                "Understand SE scope.", orthogonalEmbedding(1),
                "Practise TDD kata.", orthogonalEmbedding(2)));

        mockMvc.perform(post("/api/courses/{id}/extract", course.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goalsCreated").value(3));

        // Module root + one session node + one exercise node (the title keyword picks EXERCISE).
        List<HierarchyNode> nodes = hierarchyRepository.findByCourseId(course.getId());
        assertThat(nodes).extracting(HierarchyNode::getLevel)
                .containsExactlyInAnyOrder(HierarchyLevel.MODULE, HierarchyLevel.SESSION, HierarchyLevel.EXERCISE);

        // Each goal is attached to the node of the section its chunk came from (deterministic, by
        // offset). Labels are resolved through the already-loaded nodes: the goal's hierarchyNode is a
        // lazy proxy and the session is closed, but reading its id never triggers initialization.
        Map<Long, String> labelsByNodeId = nodes.stream()
                .collect(Collectors.toMap(HierarchyNode::getId, HierarchyNode::getLabel));
        List<LearningGoal> goals = goalRepository.findByCourseId(course.getId());
        assertThat(goals).allSatisfy(g -> assertThat(g.getHierarchyNode()).isNotNull());
        assertThat(goals)
                .filteredOn(g -> g.getText().equals("Practise TDD kata."))
                .singleElement()
                .satisfies(g -> assertThat(labelsByNodeId.get(g.getHierarchyNode().getId()))
                        .isEqualTo("Exercise 3.2: Kata"));
        assertThat(goals)
                .filteredOn(g -> g.getText().equals("Apply TDD."))
                .singleElement()
                .satisfies(g -> assertThat(labelsByNodeId.get(g.getHierarchyNode().getId()))
                        .isEqualTo("Lecture 3: Testing"));
    }

    private static float[] orthogonalEmbedding(int slot) {
        float[] v = new float[4096];
        v[slot] = 1.0f;
        return v;
    }

    /**
     * Stubs the batched goal embedding: each text maps to its vector in {@code byText}, defaulting to
     * {@code orthogonalEmbedding(0)} for any text not listed. The returned list is aligned to the
     * batch's input order, mirroring the real {@code embedAll}.
     */
    private void stubEmbedAll(Map<String, float[]> byText) {
        when(embeddingService.embedAll(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> byText.getOrDefault(t, orthogonalEmbedding(0))).toList();
        });
    }
}
