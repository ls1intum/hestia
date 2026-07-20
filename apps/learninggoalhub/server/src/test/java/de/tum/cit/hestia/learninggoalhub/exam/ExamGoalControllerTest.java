package de.tum.cit.hestia.learninggoalhub.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.hestia.learninggoalhub.TestcontainersConfiguration;
import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import de.tum.cit.hestia.learninggoalhub.embedding.EmbeddingService;
import de.tum.cit.hestia.learninggoalhub.goal.BloomLevel;
import de.tum.cit.hestia.learninggoalhub.goal.GoalKind;
import de.tum.cit.hestia.learninggoalhub.goal.GoalOrigin;
import de.tum.cit.hestia.learninggoalhub.goal.GoalStatus;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoal;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoalRepository;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyLevel;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNode;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNodeRepository;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyClassification;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyService;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ExamGoalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private LearningGoalRepository goalRepository;

    @Autowired
    private HierarchyNodeRepository hierarchyNodeRepository;

    @MockitoBean
    private ExamGoalGenerator generator;

    @MockitoBean
    private TaxonomyService taxonomyService;

    @MockitoBean
    private EmbeddingService embeddingService;

    private static final String HARRY_PAYLOAD = """
            {
              "blocks": [
                { "blockId": "1", "blockType": "context", "taskType": null,
                  "description": "The exam covers the machine learning lecture." },
                { "blockId": "2", "blockType": "task", "taskType": "singleChoice",
                  "description": "What is 1 + 1?" },
                { "blockId": "3", "blockType": "task", "taskType": "freeText",
                  "description": "Explain how LLMs have shaped the way humans work." }
              ]
            }
            """;

    @Test
    void generatesPersistsAndReturnsGoalsPerTaskBlock() throws Exception {
        Course course = courseRepository.save(new Course("Introduction to ML"));
        when(generator.generate(anyString(), eq("singleChoice"), anyString(), anyString(), isNull()))
                .thenReturn(List.of(new GeneratedExamGoal("Recall basic integer addition.")));
        when(generator.generate(anyString(), eq("freeText"), anyString(), anyString(), isNull()))
                .thenReturn(List.of(
                        new GeneratedExamGoal("Explain the impact of LLMs on knowledge work."),
                        new GeneratedExamGoal("Evaluate the use of LLMs with a personal example.")));
        when(taxonomyService.classifyBatch(anyList(), isNull())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream()
                    .map(t -> new TaxonomyClassification(BloomLevel.UNDERSTAND, null))
                    .toList();
        });
        when(embeddingService.embedAll(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> new float[4096]).toList();
        });

        mockMvc.perform(post("/api/courses/{id}/exam-tasks/learning-goals", course.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(HARRY_PAYLOAD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(2)))
                .andExpect(jsonPath("$[0].blockId").value("2"))
                .andExpect(jsonPath("$[0].goals", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].goals[0].id").isNumber())
                .andExpect(jsonPath("$[0].goals[0].text").value("Recall basic integer addition."))
                .andExpect(jsonPath("$[0].goals[0].kind").value("IMPLICIT"))
                .andExpect(jsonPath("$[0].goals[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].goals[0].bloomLevel").value("UNDERSTAND"))
                .andExpect(jsonPath("$[1].blockId").value("3"))
                .andExpect(jsonPath("$[1].goals", Matchers.hasSize(2)));

        List<LearningGoal> persisted = goalRepository.findByCourseId(course.getId());
        assertThat(persisted).hasSize(3);
        assertThat(persisted).allSatisfy(g -> {
            assertThat(g.getOrigin()).isEqualTo(GoalOrigin.EXAM);
            assertThat(g.getKind()).isEqualTo(GoalKind.IMPLICIT);
            assertThat(g.getStatus()).isEqualTo(GoalStatus.PENDING);
        });
        List<HierarchyNode> examRoots = hierarchyNodeRepository.findByCourseId(course.getId()).stream()
                .filter(n -> n.getLevel() == HierarchyLevel.EXAM)
                .toList();
        assertThat(examRoots).hasSize(1);
        assertThat(examRoots.getFirst().getLabel()).isEqualTo("Exam");
    }

    @Test
    void accumulatesPrecedingContextBlocksPerTask() throws Exception {
        Course course = courseRepository.save(new Course("Course"));
        when(generator.generate(anyString(), any(), anyString(), anyString(), isNull())).thenReturn(List.of());
        when(taxonomyService.classifyBatch(anyList(), isNull())).thenReturn(List.of());
        when(embeddingService.embedAll(anyList())).thenReturn(List.of());

        mockMvc.perform(post("/api/courses/{id}/exam-tasks/learning-goals", course.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "blocks": [
                                    { "blockId": "1", "blockType": "context", "description": "Context A" },
                                    { "blockId": "2", "blockType": "task", "taskType": "freeText", "description": "Task 1" },
                                    { "blockId": "3", "blockType": "task", "taskType": "freeText", "description": "Task 2" },
                                    { "blockId": "4", "blockType": "context", "description": "Context B" },
                                    { "blockId": "5", "blockType": "task", "taskType": "freeText", "description": "Task 3" }
                                  ]
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(generator, Mockito.times(3))
                .generate(contextCaptor.capture(), anyString(), anyString(), anyString(), isNull());
        assertThat(contextCaptor.getAllValues()).containsExactly(
                "Context A",
                "Context A",
                "Context A\n\nContext B");
    }

    @Test
    void reusesExamRootAcrossRequests() throws Exception {
        Course course = courseRepository.save(new Course("Course"));
        when(generator.generate(anyString(), any(), anyString(), anyString(), isNull()))
                .thenReturn(List.of(new GeneratedExamGoal("Recall a fact.")));
        when(taxonomyService.classifyBatch(anyList(), isNull()))
                .thenReturn(java.util.Collections.singletonList(null));
        when(embeddingService.embedAll(anyList()))
                .thenReturn(java.util.Collections.singletonList(null));

        String payload = """
                { "blocks": [ { "blockId": "1", "blockType": "task", "taskType": null, "description": "Task" } ] }
                """;
        mockMvc.perform(post("/api/courses/{id}/exam-tasks/learning-goals", course.getId())
                .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isOk());
        mockMvc.perform(post("/api/courses/{id}/exam-tasks/learning-goals", course.getId())
                .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isOk());

        long examRoots = hierarchyNodeRepository.findByCourseId(course.getId()).stream()
                .filter(n -> n.getLevel() == HierarchyLevel.EXAM)
                .count();
        assertThat(examRoots).isEqualTo(1);
    }

    @Test
    void persistsGoalsWithoutLevelsWhenTaxonomyFails() throws Exception {
        Course course = courseRepository.save(new Course("Course"));
        when(generator.generate(anyString(), any(), anyString(), anyString(), isNull()))
                .thenReturn(List.of(new GeneratedExamGoal("Recall a fact.")));
        when(taxonomyService.classifyBatch(anyList(), isNull())).thenThrow(new RuntimeException("SAIA 429"));
        when(embeddingService.embedAll(anyList())).thenThrow(new RuntimeException("SAIA 429"));

        mockMvc.perform(post("/api/courses/{id}/exam-tasks/learning-goals", course.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "blocks": [ { "blockId": "1", "blockType": "task", "taskType": null, "description": "Task" } ] }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].goals[0].bloomLevel").value(Matchers.nullValue()));

        List<LearningGoal> persisted = goalRepository.findByCourseId(course.getId());
        assertThat(persisted).hasSize(1);
        assertThat(persisted.getFirst().getBloomLevel()).isNull();
        assertThat(persisted.getFirst().getEmbedding()).isNull();
    }

    @Test
    void returns404ForUnknownCourse() throws Exception {
        mockMvc.perform(post("/api/courses/999999/exam-tasks/learning-goals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(HARRY_PAYLOAD))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void returns400WhenNoTaskBlockPresent() throws Exception {
        Course course = courseRepository.save(new Course("Course"));

        mockMvc.perform(post("/api/courses/{id}/exam-tasks/learning-goals", course.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "blocks": [ { "blockId": "1", "blockType": "context", "description": "Only context" } ] }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void returns400ForEmptyBlocks() throws Exception {
        Course course = courseRepository.save(new Course("Course"));

        mockMvc.perform(post("/api/courses/{id}/exam-tasks/learning-goals", course.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"blocks\": [] }"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returns400ForBlankTaskDescription() throws Exception {
        Course course = courseRepository.save(new Course("Course"));

        mockMvc.perform(post("/api/courses/{id}/exam-tasks/learning-goals", course.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "blocks": [ { "blockId": "1", "blockType": "task", "taskType": "freeText", "description": " " } ] }
                                """))
                .andExpect(status().isBadRequest());
    }
}
