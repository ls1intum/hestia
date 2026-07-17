package de.tum.cit.hestia.learninggoalhub.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
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

    @MockitoBean
    private ExtractionService extractionService;

    @MockitoBean
    private SessionGoalConsolidator sessionGoalConsolidator;

    @MockitoBean
    private EmbeddingService embeddingService;

    /**
     * Identity consolidation: pass each session's candidates through unchanged (one outcome per
     * candidate, each supported by itself). This isolates the rest of the pipeline from the
     * consolidation LLM so the existing dedup/source/hierarchy assertions still describe behaviour.
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

        when(extractionService.extract(eq("lecture text about TDD"), eq(null))).thenReturn(List.of(
                new ExtractedGoal("Apply test-driven development.", GoalKind.EXPLICIT, "...write a failing test first..."),
                new ExtractedGoal("Value short feedback loops.", GoalKind.IMPLICIT, "...keep tests fast...")
        ));
        when(extractionService.extract(eq("exercise on refactoring"), eq(null))).thenReturn(List.of(
                new ExtractedGoal("Refactor without changing behaviour.", GoalKind.EXPLICIT, "...extract method...")
        ));
        stubEmbedAll(Map.of(
                "Apply test-driven development.", orthogonalEmbedding(0),
                "Value short feedback loops.", orthogonalEmbedding(1),
                "Refactor without changing behaviour.", orthogonalEmbedding(2)));

        mockMvc.perform(post("/api/courses/{id}/extract", course.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentsProcessed").value(2))
                .andExpect(jsonPath("$.candidatesExtracted").value(3))
                .andExpect(jsonPath("$.goalsCreated").value(3));

        List<LearningGoal> goals = goalRepository.findByCourseId(course.getId());
        assertThat(goals).hasSize(3);
        assertThat(goals).extracting(LearningGoal::getKind)
                .containsExactlyInAnyOrder(GoalKind.EXPLICIT, GoalKind.IMPLICIT, GoalKind.EXPLICIT);
        assertThat(goals).allSatisfy(g -> assertThat(g.getEmbedding()).hasSize(4096));
        // All goals are embedded in one batched call rather than one call per goal.
        verify(embeddingService).embedAll(anyList());

        // The raw candidates are persisted and each points at the goal it was consolidated into.
        List<GoalCandidate> candidates = goalCandidateRepository.findByCourseId(course.getId());
        assertThat(candidates).hasSize(3);
        assertThat(candidates).allSatisfy(c -> assertThat(c.getConsolidatedGoal()).isNotNull());

        long sourcesForLecture = goalSourceRepository.findAll().stream()
                .filter(s -> s.getDocument().getId().equals(lecture.getId()))
                .count();
        long sourcesForExercise = goalSourceRepository.findAll().stream()
                .filter(s -> s.getDocument().getId().equals(exercise.getId()))
                .count();
        assertThat(sourcesForLecture).isEqualTo(2);
        assertThat(sourcesForExercise).isEqualTo(1);
    }

    @Test
    void identicalGoalsAcrossDocumentsCollapseIntoSingleGoalWithTwoSources() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        Document lecture = documentRepository.save(new Document(course, "lecture.pdf", "application/pdf", "lecture body"));
        Document exercise = documentRepository.save(new Document(course, "exercise.pdf", "application/pdf", "exercise body"));

        when(extractionService.extract(eq("lecture body"), eq(null))).thenReturn(List.of(
                new ExtractedGoal("Apply test-driven development.", GoalKind.EXPLICIT, "...lecture snippet...")
        ));
        when(extractionService.extract(eq("exercise body"), eq(null))).thenReturn(List.of(
                new ExtractedGoal("Apply TDD when writing code.", GoalKind.EXPLICIT, "...exercise snippet...")
        ));
        // Both goals get the exact same embedding → cosine similarity = 1.0, above the 0.92 threshold.
        stubEmbedAll(Map.of());

        mockMvc.perform(post("/api/courses/{id}/extract", course.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goalsCreated").value(1))
                .andExpect(jsonPath("$.goalsDeduplicated").value(1));

        List<LearningGoal> goals = goalRepository.findByCourseId(course.getId());
        assertThat(goals).hasSize(1);
        LearningGoal goal = goals.get(0);
        assertThat(goalSourceRepository.findByGoalId(goal.getId()))
                .extracting(s -> s.getDocument().getId())
                .containsExactlyInAnyOrder(lecture.getId(), exercise.getId());
    }

    @Test
    void duplicateExtractionsFromSameDocumentReuseSingleSource() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        Document lecture = documentRepository.save(new Document(course, "lecture.pdf", "application/pdf", "lecture body"));

        // Chunk overlap can yield the same goal twice from a single document; the second
        // attempt must not blow up on the (goal_id, document_id) composite PK.
        when(extractionService.extract(eq("lecture body"), eq(null))).thenReturn(List.of(
                new ExtractedGoal("Apply TDD.", GoalKind.EXPLICIT, "...first snippet..."),
                new ExtractedGoal("Apply TDD (rephrased).", GoalKind.EXPLICIT, "...second snippet...")
        ));
        stubEmbedAll(Map.of());

        mockMvc.perform(post("/api/courses/{id}/extract", course.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goalsCreated").value(1))
                .andExpect(jsonPath("$.goalsDeduplicated").value(1));

        List<LearningGoal> goals = goalRepository.findByCourseId(course.getId());
        assertThat(goals).hasSize(1);
        assertThat(goalSourceRepository.findByGoalId(goals.get(0).getId()))
                .singleElement()
                .satisfies(s -> assertThat(s.getDocument().getId()).isEqualTo(lecture.getId()));
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

        when(extractionService.extract(eq("lecture text about TDD"), eq(null))).thenReturn(List.of(
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
    @Transactional
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

        when(extractionService.extract(contains("apply TDD"), eq(null))).thenReturn(List.of(
                new ExtractedGoal("Apply TDD.", GoalKind.EXPLICIT, "...failing test first..."),
                new ExtractedGoal("Understand SE scope.", GoalKind.IMPLICIT, "...overview...")
        ));
        when(extractionService.extract(contains("refactoring kata"), eq(null))).thenReturn(List.of(
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

        // Each goal is attached to the node of the section its chunk came from (deterministic, by offset).
        List<LearningGoal> goals = goalRepository.findByCourseId(course.getId());
        assertThat(goals).allSatisfy(g -> assertThat(g.getHierarchyNode()).isNotNull());
        assertThat(goals)
                .filteredOn(g -> g.getText().equals("Practise TDD kata."))
                .singleElement()
                .satisfies(g -> assertThat(g.getHierarchyNode().getLabel()).isEqualTo("Exercise 3.2: Kata"));
        assertThat(goals)
                .filteredOn(g -> g.getText().equals("Apply TDD."))
                .singleElement()
                .satisfies(g -> assertThat(g.getHierarchyNode().getLabel()).isEqualTo("Lecture 3: Testing"));
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
