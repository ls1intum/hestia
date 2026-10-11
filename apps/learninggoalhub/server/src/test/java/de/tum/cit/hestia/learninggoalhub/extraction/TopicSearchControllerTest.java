package de.tum.cit.hestia.learninggoalhub.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import de.tum.cit.hestia.learninggoalhub.TestcontainersConfiguration;
import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import de.tum.cit.hestia.learninggoalhub.document.Document;
import de.tum.cit.hestia.learninggoalhub.document.DocumentRepository;
import de.tum.cit.hestia.learninggoalhub.document.DocumentSection;
import de.tum.cit.hestia.learninggoalhub.document.DocumentSectionRepository;
import de.tum.cit.hestia.learninggoalhub.goal.BloomLevel;
import de.tum.cit.hestia.learninggoalhub.goal.GoalCreationProvenance;
import de.tum.cit.hestia.learninggoalhub.goal.GoalKind;
import de.tum.cit.hestia.learninggoalhub.goal.GoalOrigin;
import de.tum.cit.hestia.learninggoalhub.goal.GoalRole;
import de.tum.cit.hestia.learninggoalhub.goal.GoalSourceRepository;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoal;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoalRepository;
import de.tum.cit.hestia.learninggoalhub.goal.SoloLevel;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyLevel;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNode;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNodeRepository;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationship;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationshipRepository;
import de.tum.cit.hestia.learninggoalhub.relationships.RelationshipOrigin;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyService;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
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
class TopicSearchControllerTest {

    private static final String TOPIC = "Random forests";

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
    private GoalRelationshipRepository goalRelationshipRepository;

    @MockitoBean
    private SessionExtractionService sessionExtractionService;

    @MockitoBean
    private TopicTreeSynthesizer topicTreeSynthesizer;

    @MockitoBean
    private TopicSearchSynthesizer topicSearchSynthesizer;

    @MockitoBean
    private TaxonomyService taxonomyService;

    @Test
    void acceptPersistsTheTopicSkillsSubSkillsSourcesKnowledgeAndEdges() throws Exception {
        Course course = courseWithLecture();
        Document lecture = documentRepository.findByCourseId(course.getId()).getFirst();

        String proposal = search(course, lecture, 1, 4);
        String proposalId = JsonPath.read(proposal, "$.proposalId");
        mockMvc.perform(post("/api/courses/{id}/learning-goals/topic-search/{proposalId}", course.getId(), proposalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subSkillKeys\": [\"s0\", \"s1\", \"s2\", \"s3\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value(TOPIC))
                .andExpect(jsonPath("$.origin").value("TERMINAL"))
                .andExpect(jsonPath("$.creationProvenance").value("USER_CREATED"));

        Map<String, LearningGoal> goals = goalRepository.findByCourseId(course.getId()).stream()
                .collect(Collectors.toMap(LearningGoal::getText, Function.identity()));
        assertThat(goals).containsOnlyKeys(TOPIC, "Build a random forest.",
                "Train trees on bootstrap samples.", "Sample features at each split.",
                "Estimate the out-of-bag error.", "Tune the number of trees.",
                "Recall what a bootstrap sample is.");
        LearningGoal topic = goals.get(TOPIC);
        LearningGoal skill = goals.get("Build a random forest.");
        LearningGoal bootstrap = goals.get("Train trees on bootstrap samples.");
        LearningGoal outOfBag = goals.get("Estimate the out-of-bag error.");
        assertThat(topic.getCreationProvenance()).isEqualTo(GoalCreationProvenance.USER_CREATED);
        assertThat(skill.getOrigin()).isEqualTo(GoalOrigin.SYNTHESIZED);
        assertThat(skill.getRole()).isEqualTo(GoalRole.SKILL);
        // Classification is mocked away, so the skill takes its members' highest levels.
        assertThat(skill.getBloomLevel()).isEqualTo(BloomLevel.ANALYZE);
        assertThat(bootstrap.getOrigin()).isEqualTo(GoalOrigin.EXTRACTED);
        assertThat(bootstrap.getRole()).isEqualTo(GoalRole.SKILL);
        assertThat(bootstrap.getCreationProvenance()).isNull();

        // Sub-skills join the session node of the bookmark section holding their page.
        HierarchyNode session = hierarchyRepository.findByCourseId(course.getId()).stream()
                .filter(node -> node.getLevel() == HierarchyLevel.SESSION)
                .findFirst().orElseThrow();
        assertThat(session.getLabel()).isEqualTo("Ensembles");
        assertThat(bootstrap.getHierarchyNode().getId()).isEqualTo(session.getId());
        assertThat(goalSourceRepository.findByGoalId(outOfBag.getId())).singleElement().satisfies(source -> {
            assertThat(source.getPage()).isEqualTo(3);
            assertThat(source.getSnippet()).isEqualTo("The out-of-bag error estimates generalisation");
            assertThat(source.isGrounded()).isTrue();
        });

        assertThat(parents(skill)).containsExactly(Map.entry(topic.getId(), RelationshipOrigin.SYNTHESIS));
        assertThat(parents(bootstrap)).containsExactly(Map.entry(skill.getId(), RelationshipOrigin.SYNTHESIS));
        assertThat(parents(outOfBag)).containsExactly(Map.entry(topic.getId(), RelationshipOrigin.SYNTHESIS));
        List<LearningGoal> knowledge = goalRepository.findByCourseIdAndRole(course.getId(), GoalRole.KNOWLEDGE);
        assertThat(knowledge).singleElement().satisfies(item -> {
            assertThat(item.getOrigin()).isEqualTo(GoalOrigin.EXTRACTED);
            assertThat(parents(item)).containsExactly(Map.entry(bootstrap.getId(), RelationshipOrigin.HIERARCHY));
            assertThat(goalSourceRepository.findByGoalId(item.getId())).hasSize(1);
        });
    }

    @Test
    void untickedSubSkillsAreDroppedAndASkillLeftWithOneMemberDissolves() throws Exception {
        Course course = courseWithLecture();
        Document lecture = documentRepository.findByCourseId(course.getId()).getFirst();

        String proposalId = JsonPath.read(search(course, lecture, 1, 4), "$.proposalId");
        mockMvc.perform(post("/api/courses/{id}/learning-goals/topic-search/{proposalId}", course.getId(), proposalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subSkillKeys\": [\"s0\", \"s2\"]}"))
                .andExpect(status().isCreated());

        Map<String, LearningGoal> goals = goalRepository.findByCourseId(course.getId()).stream()
                .collect(Collectors.toMap(LearningGoal::getText, Function.identity()));
        assertThat(goals).containsOnlyKeys(TOPIC, "Train trees on bootstrap samples.",
                "Estimate the out-of-bag error.", "Recall what a bootstrap sample is.");
        assertThat(parents(goals.get("Train trees on bootstrap samples.")))
                .containsExactly(Map.entry(goals.get(TOPIC).getId(), RelationshipOrigin.SYNTHESIS));
    }

    @Test
    void anExistingTopicWithTheSameTextConflicts() throws Exception {
        Course course = courseWithLecture();
        Document lecture = documentRepository.findByCourseId(course.getId()).getFirst();
        String proposalId = JsonPath.read(search(course, lecture, 1, 4), "$.proposalId");
        LearningGoal existing = new LearningGoal(course, "random forests", GoalKind.IMPLICIT);
        existing.setOrigin(GoalOrigin.TERMINAL);
        goalRepository.save(existing);

        mockMvc.perform(post("/api/courses/{id}/learning-goals/topic-search/{proposalId}", course.getId(), proposalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subSkillKeys\": [\"s0\"]}"))
                .andExpect(status().isConflict());
    }

    @Test
    void anUnknownProposalIsNotFound() throws Exception {
        Course course = courseRepository.save(new Course("Machine Learning"));

        mockMvc.perform(post("/api/courses/{id}/learning-goals/topic-search/{proposalId}", course.getId(),
                        "00000000-0000-0000-0000-000000000000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subSkillKeys\": [\"s0\"]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rangesAboveTheCapAreRejected() throws Exception {
        Course course = courseRepository.save(new Course("Machine Learning"));
        String[] pages = new String[81];
        java.util.Arrays.fill(pages, "Random forests");
        Document lecture = document(course, pages);

        mockMvc.perform(post("/api/courses/{id}/learning-goals/topic-search", course.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"" + TOPIC + "\", \"ranges\": [{\"documentId\": " + lecture.getId()
                                + ", \"startPage\": 1, \"endPage\": 81}]}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * A four-page lecture under one bookmark. The mocked extraction returns four sub-skills, one per
     * page, and the mocked structuring groups the first two into a skill.
     */
    private Course courseWithLecture() {
        Course course = courseRepository.save(new Course("Machine Learning"));
        Document lecture = document(course, "Bootstrap samples train each tree", "Features are sampled per split",
                "The out-of-bag error estimates generalisation", "More trees reduce variance");
        documentSectionRepository.save(new DocumentSection(lecture, 0, "Ensembles", 0,
                lecture.getRawText().length(), 1, 4));
        when(sessionExtractionService.extract(anyString(), anyString(), anyString(), anyString(),
                nullable(String.class), anyList(), anyInt(), any()))
                .thenReturn(List.of(
                        skill("Train trees on bootstrap samples.", 0, BloomLevel.APPLY,
                                new ExtractedSkill.Knowledge("Recall what a bootstrap sample is.", null,
                                        GoalKind.EXPLICIT, BloomLevel.REMEMBER, SoloLevel.UNISTRUCTURAL, 0, 0)),
                        skill("Sample features at each split.", 1, BloomLevel.ANALYZE),
                        skill("Estimate the out-of-bag error.", 2, BloomLevel.APPLY),
                        skill("Tune the number of trees.", 3, BloomLevel.APPLY)));
        when(topicTreeSynthesizer.structure(eq(TOPIC), anyList(), anyString(), nullable(String.class)))
                .thenReturn(new TopicTreeSynthesizer.PlannedTopic(TOPIC, List.of(
                        new TopicTreeSynthesizer.PlannedCapability("Build a random forest.", List.of(0, 1))),
                        List.of(2, 3)));
        when(topicTreeSynthesizer.shortLabels(anyList(), anyString(), nullable(String.class)))
                .thenReturn(List.of("Build forests"));
        when(taxonomyService.classifyBatch(anyList(), any())).thenReturn(List.of());
        return course;
    }

    private String search(Course course, Document lecture, int startPage, int endPage) throws Exception {
        return mockMvc.perform(post("/api/courses/{id}/learning-goals/topic-search", course.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"" + TOPIC + "\", \"ranges\": [{\"documentId\": " + lecture.getId()
                                + ", \"startPage\": " + startPage + ", \"endPage\": " + endPage + "}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills[0].subSkills[0].key").value("s0"))
                .andExpect(jsonPath("$.direct[0].source.page").value(3))
                .andReturn().getResponse().getContentAsString();
    }

    private Document document(Course course, String... pages) {
        StringBuilder text = new StringBuilder();
        int[] offsets = new int[pages.length + 1];
        for (int page = 0; page < pages.length; page++) {
            text.append(pages[page]).append('\n');
            offsets[page + 1] = text.length();
        }
        Document document = new Document(course, "lecture.pdf", "application/pdf", text.toString());
        document.setPageOffsets(offsets);
        return documentRepository.save(document);
    }

    private static ExtractedSkill skill(String text, int line, BloomLevel bloom, ExtractedSkill.Knowledge... knowledge) {
        return new ExtractedSkill(text, null, GoalKind.EXPLICIT, bloom, SoloLevel.RELATIONAL, line, line,
                List.of(knowledge));
    }

    /** Each tree parent of a goal with the origin of the edge to it. */
    private Map<Long, RelationshipOrigin> parents(LearningGoal goal) {
        return goalRelationshipRepository.findBySourceId(goal.getId()).stream()
                .collect(Collectors.toMap(relationship -> relationship.getTarget().getId(), GoalRelationship::getOrigin));
    }
}
