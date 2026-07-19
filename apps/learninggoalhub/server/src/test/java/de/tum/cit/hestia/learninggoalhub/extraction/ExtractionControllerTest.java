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
import de.tum.cit.hestia.learninggoalhub.embedding.EmbeddingService;
import de.tum.cit.hestia.learninggoalhub.goal.GoalKind;
import de.tum.cit.hestia.learninggoalhub.goal.GoalSourceRepository;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoal;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoalRepository;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyLevel;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNode;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNodeRepository;
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
    private LearningGoalRepository goalRepository;

    @Autowired
    private GoalSourceRepository goalSourceRepository;

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
    private EmbeddingService embeddingService;

    @MockitoBean
    private TaxonomyService taxonomyService;

    /**
     * Identity consolidation: pass each session's candidates through unchanged (one outcome per
     * candidate, each supported by itself). This isolates the fallback path from the consolidation LLM.
     */
    @BeforeEach
    void stubIdentityConsolidation() {
        when(sessionGoalConsolidator.consolidate(anyString(), anyList(), any())).thenAnswer(inv -> {
            List<String> candidates = inv.getArgument(1);
            return java.util.stream.IntStream.range(0, candidates.size())
                    .mapToObj(i -> new ConsolidatedGoal(candidates.get(i), List.of(i)))
                    .toList();
        });
    }

    @Test
    void extractionPersistsGoalsAndSourcesPerDocument() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        Document lecture = documentRepository.save(new Document(course, "lecture.pdf", "application/pdf", "lecture text about TDD"));
        Document exercise = documentRepository.save(new Document(course, "exercise.pdf", "application/pdf", "exercise on refactoring"));

        when(sessionExtractionService.extract(eq("lecture.pdf"), eq("lecture text about TDD"), eq(null))).thenReturn(List.of(
                new ExtractedGoal("Apply test-driven development.", GoalKind.EXPLICIT, "...write a failing test first..."),
                new ExtractedGoal("Value short feedback loops.", GoalKind.IMPLICIT, "...keep tests fast...")
        ));
        when(sessionExtractionService.extract(eq("exercise.pdf"), eq("exercise on refactoring"), eq(null))).thenReturn(List.of(
                new ExtractedGoal("Refactor without changing behaviour.", GoalKind.EXPLICIT, "...extract method...")
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
    void oversizedSessionUsesFallbackCandidatesAndProvenance() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        String oversizedText = "This session is deliberately longer than the direct extraction threshold. "
                .repeat(3);
        Document document = documentRepository.save(
                new Document(course, "fallback.pdf", "application/pdf", oversizedText));

        when(extractionService.extract(eq(oversizedText), eq(null))).thenReturn(List.of(
                new ExtractedGoal("Apply the fallback procedure.", GoalKind.EXPLICIT, "...fallback procedure...")));
        stubEmbedAll(Map.of("Apply the fallback procedure.", orthogonalEmbedding(0)));

        mockMvc.perform(post("/api/courses/{id}/extract", course.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goalsCreated").value(1));

        verify(sessionExtractionService, never()).extract(anyString(), anyString(), eq(null));
        List<GoalCandidate> candidates = goalCandidateRepository.findByCourseId(course.getId());
        assertThat(candidates)
                .singleElement()
                .satisfies(candidate -> assertThat(candidate.getConsolidatedGoal()).isNotNull());
        assertThat(extractionRunRepository.findByCourseId(course.getId()))
                .singleElement()
                .satisfies(run -> assertThat(run.getPromptVersion()).isEqualTo("chunked-v2"));
        assertThat(goalSourceRepository.findAll())
                .filteredOn(source -> source.getDocument().getId().equals(document.getId()))
                .hasSize(1);
    }

    @Test
    void failedExtractionLeavesFailedAuditRun() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        String text = "short session text";
        documentRepository.save(new Document(course, "failed.pdf", "application/pdf", text));
        when(sessionExtractionService.extract(eq("failed.pdf"), eq(text), eq(null)))
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

        when(sessionExtractionService.extract(eq("lecture.pdf"), eq("lecture body"), eq(null))).thenReturn(List.of(
                new ExtractedGoal("Apply test-driven development.", GoalKind.EXPLICIT, "...lecture snippet...")
        ));
        when(sessionExtractionService.extract(eq("exercise.pdf"), eq("exercise body"), eq(null))).thenReturn(List.of(
                new ExtractedGoal("Apply TDD when writing code.", GoalKind.EXPLICIT, "...exercise snippet...")
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
        when(sessionExtractionService.extract(eq("lecture.pdf"), eq("lecture body"), eq(null))).thenReturn(List.of(
                new ExtractedGoal("Apply TDD.", GoalKind.EXPLICIT, "...first snippet..."),
                new ExtractedGoal("Apply TDD (rephrased).", GoalKind.EXPLICIT, "...second snippet...")
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

        when(sessionExtractionService.extract(eq("lecture.pdf"), eq("lecture text about TDD"), eq(null))).thenReturn(List.of(
                new ExtractedGoal("Apply test-driven development.", GoalKind.EXPLICIT, "...failing test first...")
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

        when(sessionExtractionService.extract(eq("Lecture 3: Testing"), eq(sessionText), eq(null))).thenReturn(List.of(
                new ExtractedGoal("Apply TDD.", GoalKind.EXPLICIT, "...failing test first..."),
                new ExtractedGoal("Understand SE scope.", GoalKind.IMPLICIT, "...overview...")
        ));
        when(sessionExtractionService.extract(eq("Exercise 3.2: Kata"), eq(exerciseText), eq(null))).thenReturn(List.of(
                new ExtractedGoal("Practise TDD kata.", GoalKind.EXPLICIT, "...kata...")
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
