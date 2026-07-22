package de.tum.cit.hestia.learninggoalhub.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.hestia.learninggoalhub.TestcontainersConfiguration;
import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import de.tum.cit.hestia.learninggoalhub.document.Document;
import de.tum.cit.hestia.learninggoalhub.document.DocumentContent;
import de.tum.cit.hestia.learninggoalhub.document.DocumentContentRepository;
import de.tum.cit.hestia.learninggoalhub.document.DocumentRepository;
import de.tum.cit.hestia.learninggoalhub.extraction.SkillSuggestionSynthesizer;
import de.tum.cit.hestia.learninggoalhub.extraction.SubtreeSynthesizer;
import de.tum.cit.hestia.learninggoalhub.extraction.SubtreeSynthesizer.GeneratedKnowledge;
import de.tum.cit.hestia.learninggoalhub.extraction.SubtreeSynthesizer.GeneratedSubSkill;
import de.tum.cit.hestia.learninggoalhub.extraction.SubtreeSynthesizer.GeneratedSubtree;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyLevel;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNode;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNodeRepository;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationship;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationshipRepository;
import de.tum.cit.hestia.learninggoalhub.relationships.RelationshipOrigin;
import de.tum.cit.hestia.learninggoalhub.relationships.RelationshipType;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyClassification;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyService;
import java.util.List;
import org.hamcrest.Matchers;
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
class LearningGoalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentContentRepository documentContentRepository;

    @Autowired
    private LearningGoalRepository goalRepository;

    @Autowired
    private GoalSourceRepository goalSourceRepository;

    @Autowired
    private HierarchyNodeRepository hierarchyRepository;

    @Autowired
    private GoalRelationshipRepository goalRelationshipRepository;

    @MockitoBean
    private TaxonomyService taxonomyService;

    @MockitoBean
    private SkillSuggestionSynthesizer skillSuggestionSynthesizer;

    @MockitoBean
    private SubtreeSynthesizer subtreeSynthesizer;

    @Test
    void returnsPaginatedGoalsWithKindAndSources() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        Document lecture = documentRepository.save(new Document(course, "lecture.pdf", "application/pdf", "lecture"));
        Document exercise = documentRepository.save(new Document(course, "exercise.pdf", "application/pdf", "exercise"));
        documentContentRepository.save(new DocumentContent(lecture, new byte[]{1, 2, 3}));

        LearningGoal tdd = goalRepository.save(new LearningGoal(course, "Apply TDD.", GoalKind.EXPLICIT));
        LearningGoal refactor = goalRepository.save(new LearningGoal(course, "Value refactoring.", GoalKind.IMPLICIT));
        goalSourceRepository.save(new GoalSource(tdd, lecture, "...failing test first..."));
        goalSourceRepository.save(new GoalSource(tdd, exercise, "...red-green-refactor..."));
        goalSourceRepository.save(new GoalSource(refactor, lecture, "...small steps..."));

        mockMvc.perform(get("/api/courses/{id}/learning-goals", course.getId())
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.content", Matchers.hasSize(2)))
                .andExpect(jsonPath("$.content[0].text").value("Apply TDD."))
                .andExpect(jsonPath("$.content[0].kind").value("EXPLICIT"))
                .andExpect(jsonPath("$.content[0].sources", Matchers.hasSize(2)))
                .andExpect(jsonPath("$.content[0].sources[*].filename",
                        Matchers.containsInAnyOrder("lecture.pdf", "exercise.pdf")))
                .andExpect(jsonPath("$.content[1].text").value("Value refactoring."))
                .andExpect(jsonPath("$.content[1].kind").value("IMPLICIT"))
                .andExpect(jsonPath("$.content[1].sources", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.content[1].sources[0].filename").value("lecture.pdf"))
                .andExpect(jsonPath("$.content[1].sources[0].contentAvailable").value(true))
                .andExpect(jsonPath("$.content[1].sources[0].grounded").value(false))
                // goals without hierarchy/taxonomy/relationships expose null/empty, not missing keys
                .andExpect(jsonPath("$.content[0].hierarchy").doesNotExist())
                .andExpect(jsonPath("$.content[0].bloomLevel").doesNotExist())
                .andExpect(jsonPath("$.content[0].soloLevel").doesNotExist())
                .andExpect(jsonPath("$.content[0].relationships", Matchers.hasSize(0)));
    }

    @Test
    void exposesHierarchyTaxonomyAndRelationshipsInJson() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));

        HierarchyNode module = hierarchyRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.MODULE, "Software Engineering"));
        HierarchyNode session = hierarchyRepository.save(
                new HierarchyNode(course, module, HierarchyLevel.SESSION, "Session 3: Testing"));

        LearningGoal tdd = new LearningGoal(course, "Apply TDD.", GoalKind.EXPLICIT);
        tdd.setHierarchyNode(session);
        tdd.setBloomLevel(BloomLevel.APPLY);
        tdd.setSoloLevel(SoloLevel.RELATIONAL);
        tdd = goalRepository.save(tdd);

        LearningGoal unitTests = goalRepository.save(
                new LearningGoal(course, "Understand unit testing.", GoalKind.EXPLICIT));

        // tdd PREREQUISITE_OF nothing here, but unitTests CONTRIBUTES_TO tdd and OVERLAPS_WITH it;
        // assert grouping order CONTRIBUTES_TO before OVERLAPS_WITH within the same source goal.
        goalRelationshipRepository.save(new GoalRelationship(
                unitTests, tdd, RelationshipType.OVERLAPS_WITH, 0.85, RelationshipOrigin.EMBEDDING));
        goalRelationshipRepository.save(new GoalRelationship(
                unitTests, tdd, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));

        mockMvc.perform(get("/api/courses/{id}/learning-goals", course.getId())
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].text").value("Apply TDD."))
                .andExpect(jsonPath("$.content[0].hierarchy.module").value("Software Engineering"))
                .andExpect(jsonPath("$.content[0].hierarchy.session").value("Session 3: Testing"))
                .andExpect(jsonPath("$.content[0].hierarchy.sessionId").value(session.getId().intValue()))
                .andExpect(jsonPath("$.content[0].hierarchy.exercise").doesNotExist())
                .andExpect(jsonPath("$.content[0].bloomLevel").value("APPLY"))
                .andExpect(jsonPath("$.content[0].soloLevel").value("RELATIONAL"))
                .andExpect(jsonPath("$.content[1].text").value("Understand unit testing."))
                .andExpect(jsonPath("$.content[1].relationships", Matchers.hasSize(2)))
                .andExpect(jsonPath("$.content[1].relationships[0].type").value("CONTRIBUTES_TO"))
                .andExpect(jsonPath("$.content[1].relationships[0].targetGoalId").value(tdd.getId().intValue()))
                .andExpect(jsonPath("$.content[1].relationships[0].targetText").value("Apply TDD."))
                .andExpect(jsonPath("$.content[1].relationships[0].origin").value("HIERARCHY"))
                .andExpect(jsonPath("$.content[1].relationships[1].type").value("OVERLAPS_WITH"));
    }

    @Test
    void honoursPageSizeAndPageNumber() throws Exception {
        Course course = courseRepository.save(new Course("Databases"));
        for (int i = 0; i < 5; i++) {
            goalRepository.save(new LearningGoal(course, "Goal " + i, GoalKind.EXPLICIT));
        }

        mockMvc.perform(get("/api/courses/{id}/learning-goals", course.getId())
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(5))
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.content", Matchers.hasSize(2)))
                .andExpect(jsonPath("$.content[0].text").value("Goal 2"))
                .andExpect(jsonPath("$.content[1].text").value("Goal 3"));
    }

    @Test
    void filtersByStatus() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        LearningGoal approved = new LearningGoal(course, "Apply TDD.", GoalKind.EXPLICIT);
        approved.setStatus(GoalStatus.APPROVED);
        goalRepository.save(approved);
        goalRepository.save(new LearningGoal(course, "Value refactoring.", GoalKind.IMPLICIT));

        mockMvc.perform(get("/api/courses/{id}/learning-goals", course.getId())
                        .param("status", "APPROVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].text").value("Apply TDD."));

        mockMvc.perform(get("/api/courses/{id}/learning-goals", course.getId())
                        .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].text").value("Value refactoring."));
    }

    @Test
    void listsGoalsGroupedBySession() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));

        HierarchyNode module = hierarchyRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.MODULE, "Software Engineering"));
        HierarchyNode session = hierarchyRepository.save(
                new HierarchyNode(course, module, HierarchyLevel.SESSION, "Session 3: Testing"));

        LearningGoal moduleGoal = new LearningGoal(course, "Engineer software.", GoalKind.IMPLICIT);
        moduleGoal.setHierarchyNode(module);
        goalRepository.save(moduleGoal);
        LearningGoal tdd = new LearningGoal(course, "Apply TDD.", GoalKind.EXPLICIT);
        tdd.setHierarchyNode(session);
        tdd.setStatus(GoalStatus.APPROVED);
        goalRepository.save(tdd);
        LearningGoal refactor = new LearningGoal(course, "Value refactoring.", GoalKind.EXPLICIT);
        refactor.setHierarchyNode(session);
        goalRepository.save(refactor);
        goalRepository.save(new LearningGoal(course, "Unlinked.", GoalKind.IMPLICIT));

        // groups follow node creation order (module root first), the node-less bucket comes last
        mockMvc.perform(get("/api/courses/{id}/learning-goals/by-session", course.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(3)))
                .andExpect(jsonPath("$[0].nodeId").value(module.getId().intValue()))
                .andExpect(jsonPath("$[0].level").value("MODULE"))
                .andExpect(jsonPath("$[0].label").value("Software Engineering"))
                .andExpect(jsonPath("$[0].goals[0].text").value("Engineer software."))
                .andExpect(jsonPath("$[1].nodeId").value(session.getId().intValue()))
                .andExpect(jsonPath("$[1].level").value("SESSION"))
                .andExpect(jsonPath("$[1].label").value("Session 3: Testing"))
                .andExpect(jsonPath("$[1].goals", Matchers.hasSize(2)))
                .andExpect(jsonPath("$[1].goals[0].hierarchy.session").value("Session 3: Testing"))
                .andExpect(jsonPath("$[2].nodeId").doesNotExist())
                .andExpect(jsonPath("$[2].level").doesNotExist())
                .andExpect(jsonPath("$[2].label").doesNotExist())
                .andExpect(jsonPath("$[2].goals[0].text").value("Unlinked."));

        // the status filter drops empty groups entirely
        mockMvc.perform(get("/api/courses/{id}/learning-goals/by-session", course.getId())
                        .param("status", "APPROVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].level").value("SESSION"))
                .andExpect(jsonPath("$[0].goals", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].goals[0].text").value("Apply TDD."));
    }

    @Test
    void listBySessionFiltersToSingleNode() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        HierarchyNode module = hierarchyRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.MODULE, "Software Engineering"));
        HierarchyNode session = hierarchyRepository.save(
                new HierarchyNode(course, module, HierarchyLevel.SESSION, "Session 3: Testing"));

        LearningGoal moduleGoal = new LearningGoal(course, "Engineer software.", GoalKind.IMPLICIT);
        moduleGoal.setHierarchyNode(module);
        goalRepository.save(moduleGoal);
        LearningGoal tdd = new LearningGoal(course, "Apply TDD.", GoalKind.EXPLICIT);
        tdd.setHierarchyNode(session);
        goalRepository.save(tdd);
        goalRepository.save(new LearningGoal(course, "Unlinked.", GoalKind.IMPLICIT));

        // nodeId narrows to exactly that node's group, dropping the other node and the node-less bucket
        mockMvc.perform(get("/api/courses/{id}/learning-goals/by-session", course.getId())
                        .param("nodeId", session.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].nodeId").value(session.getId().intValue()))
                .andExpect(jsonPath("$[0].goals", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].goals[0].text").value("Apply TDD."));

        // an unknown nodeId yields an empty list rather than an error
        mockMvc.perform(get("/api/courses/{id}/learning-goals/by-session", course.getId())
                        .param("nodeId", "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(0)));
    }

    @Test
    void listBySessionUnknownCourseReturns404() throws Exception {
        mockMvc.perform(get("/api/courses/{id}/learning-goals/by-session", 999999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownCourseErrorBodyHasCodeAndMessage() throws Exception {
        mockMvc.perform(get("/api/courses/{id}/learning-goals/by-session", 999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("999999")));
    }

    @Test
    void unknownCourseReturns404() throws Exception {
        mockMvc.perform(get("/api/courses/{id}/learning-goals", 999999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void exportCsvReturnsAllGoalsWithHierarchyAndSources() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        Document lecture = documentRepository.save(new Document(course, "lecture.pdf", "application/pdf", "lecture"));
        Document exercise = documentRepository.save(new Document(course, "exercise.pdf", "application/pdf", "exercise"));

        HierarchyNode module = hierarchyRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.MODULE, "Software Engineering"));
        HierarchyNode session = hierarchyRepository.save(
                new HierarchyNode(course, module, HierarchyLevel.SESSION, "Session 3: Testing"));
        HierarchyNode exerciseNode = hierarchyRepository.save(
                new HierarchyNode(course, session, HierarchyLevel.EXERCISE, "Exercise 3.2: TDD Kata"));

        LearningGoal sessionGoal = new LearningGoal(course, "Apply TDD.", GoalKind.EXPLICIT);
        sessionGoal.setHierarchyNode(session);
        sessionGoal = goalRepository.save(sessionGoal);
        LearningGoal exerciseGoal = new LearningGoal(course, "Practise the kata.", GoalKind.EXPLICIT);
        exerciseGoal.setHierarchyNode(exerciseNode);
        exerciseGoal = goalRepository.save(exerciseGoal);
        LearningGoal orphan = goalRepository.save(new LearningGoal(course, "Unlinked.", GoalKind.IMPLICIT));

        goalSourceRepository.save(new GoalSource(sessionGoal, lecture, "snippet a"));
        goalSourceRepository.save(new GoalSource(sessionGoal, exercise, "snippet b"));
        goalSourceRepository.save(new GoalSource(exerciseGoal, exercise, "snippet c"));

        String csv = mockMvc.perform(get("/api/courses/{id}/learning-goals/export.csv", course.getId()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition",
                        Matchers.containsString("course-" + course.getId() + "-learning-goals.csv")))
                .andReturn().getResponse().getContentAsString();

        String[] lines = csv.split("\\r?\\n");
        assertThat(lines[0]).isEqualTo(
                "\"hierarchy_module\",\"hierarchy_session\",\"hierarchy_exercise\",\"learning_goal\",\"kind\",\"sources\",\"taxonomy\",\"relationships\",\"status\"");
        assertThat(csv).contains(
                "\"Software Engineering\",\"Session 3: Testing\",\"\",\"Apply TDD.\",\"EXPLICIT\",\"lecture.pdf; exercise.pdf\",\"\",\"\",\"PENDING\"");
        assertThat(csv).contains(
                "\"Software Engineering\",\"Session 3: Testing\",\"Exercise 3.2: TDD Kata\",\"Practise the kata.\",\"EXPLICIT\",\"exercise.pdf\",\"\",\"\",\"PENDING\"");
        assertThat(csv).contains("\"\",\"\",\"\",\"Unlinked.\",\"IMPLICIT\",\"\",\"\",\"\",\"PENDING\"");
    }

    @Test
    void exportCsvUnknownCourseReturns404() throws Exception {
        mockMvc.perform(get("/api/courses/{id}/learning-goals/export.csv", 999999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void patchUpdatesTextAndDropsStaleEmbedding() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        LearningGoal goal = new LearningGoal(course, "Apply TDD.", GoalKind.EXPLICIT);
        goal.setShortLabel("Test-Driven Development");
        goal.setEmbedding(new float[4096]);
        goal = goalRepository.save(goal);

        mockMvc.perform(patch("/api/courses/{courseId}/learning-goals/{goalId}", course.getId(), goal.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"  Apply test-driven development.  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Apply test-driven development."))
                .andExpect(jsonPath("$.shortLabel").doesNotExist())
                .andExpect(jsonPath("$.status").value("PENDING"));

        LearningGoal reloaded = goalRepository.findById(goal.getId()).orElseThrow();
        assertThat(reloaded.getText()).isEqualTo("Apply test-driven development.");
        assertThat(reloaded.getShortLabel()).isNull();
        assertThat(reloaded.getEmbedding()).isNull();
    }

    @Test
    void patchApprovesAndUnapprovesGoal() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        LearningGoal goal = goalRepository.save(new LearningGoal(course, "Apply TDD.", GoalKind.EXPLICIT));

        mockMvc.perform(patch("/api/courses/{courseId}/learning-goals/{goalId}", course.getId(), goal.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                // status-only updates keep the text untouched
                .andExpect(jsonPath("$.text").value("Apply TDD."));

        mockMvc.perform(patch("/api/courses/{courseId}/learning-goals/{goalId}", course.getId(), goal.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"PENDING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void patchUpdatesTaxonomyLevels() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        LearningGoal goal = new LearningGoal(course, "Apply TDD.", GoalKind.EXPLICIT);
        goal.setBloomLevel(BloomLevel.UNDERSTAND);
        goal = goalRepository.save(goal);

        mockMvc.perform(patch("/api/courses/{courseId}/learning-goals/{goalId}", course.getId(), goal.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bloomLevel\": \"APPLY\", \"soloLevel\": \"RELATIONAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bloomLevel").value("APPLY"))
                .andExpect(jsonPath("$.soloLevel").value("RELATIONAL"))
                // taxonomy-only updates keep the text untouched
                .andExpect(jsonPath("$.text").value("Apply TDD."));

        // a text-only update keeps the levels untouched
        mockMvc.perform(patch("/api/courses/{courseId}/learning-goals/{goalId}", course.getId(), goal.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"Apply test-driven development.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bloomLevel").value("APPLY"))
                .andExpect(jsonPath("$.soloLevel").value("RELATIONAL"));
    }

    @Test
    void patchBlankTextReturns400() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        LearningGoal goal = goalRepository.save(new LearningGoal(course, "Apply TDD.", GoalKind.EXPLICIT));

        mockMvc.perform(patch("/api/courses/{courseId}/learning-goals/{goalId}", course.getId(), goal.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchGoalFromOtherCourseReturns404() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        Course other = courseRepository.save(new Course("Databases"));
        LearningGoal goal = goalRepository.save(new LearningGoal(course, "Apply TDD.", GoalKind.EXPLICIT));

        mockMvc.perform(patch("/api/courses/{courseId}/learning-goals/{goalId}", other.getId(), goal.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"APPROVED\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteRemovesGoalWithSourcesAndRelationships() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        Document lecture = documentRepository.save(new Document(course, "lecture.pdf", "application/pdf", "lecture"));
        LearningGoal tdd = goalRepository.save(new LearningGoal(course, "Apply TDD.", GoalKind.EXPLICIT));
        LearningGoal unitTests = goalRepository.save(
                new LearningGoal(course, "Understand unit testing.", GoalKind.EXPLICIT));
        goalSourceRepository.save(new GoalSource(tdd, lecture, "...failing test first..."));
        goalRelationshipRepository.save(new GoalRelationship(
                unitTests, tdd, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));

        mockMvc.perform(delete("/api/courses/{courseId}/learning-goals/{goalId}", course.getId(), tdd.getId()))
                .andExpect(status().isNoContent());

        assertThat(goalRepository.findById(tdd.getId())).isEmpty();
        // the surviving goal no longer reports a relationship to the deleted one
        mockMvc.perform(get("/api/courses/{id}/learning-goals", course.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.content[0].relationships", Matchers.hasSize(0)));
    }

    @Test
    void deleteGoalFromOtherCourseReturns404() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        Course other = courseRepository.save(new Course("Databases"));
        LearningGoal goal = goalRepository.save(new LearningGoal(course, "Apply TDD.", GoalKind.EXPLICIT));

        mockMvc.perform(delete("/api/courses/{courseId}/learning-goals/{goalId}", other.getId(), goal.getId()))
                .andExpect(status().isNotFound());

        assertThat(goalRepository.findById(goal.getId())).isPresent();
    }

    @Test
    void createTerminalSkillPersistsUserCreatedTerminalUnderCompetencyRoot() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        when(taxonomyService.classify(any()))
                .thenReturn(new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL));

        mockMvc.perform(post("/api/courses/{id}/learning-goals/terminal", course.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"  Design a REST API.  \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value("Design a REST API."))
                .andExpect(jsonPath("$.kind").value("IMPLICIT"))
                .andExpect(jsonPath("$.origin").value("TERMINAL"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.creationProvenance").value("USER_CREATED"))
                .andExpect(jsonPath("$.bloomLevel").value("APPLY"))
                .andExpect(jsonPath("$.soloLevel").value("RELATIONAL"))
                .andExpect(jsonPath("$.hierarchy").exists());

        // a second skill reuses the same lazily created COMPETENCY root instead of creating another
        mockMvc.perform(post("/api/courses/{id}/learning-goals/terminal", course.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"Secure a web application.\"}"))
                .andExpect(status().isCreated());

        assertThat(hierarchyRepository.findByCourseId(course.getId()).stream()
                .filter(n -> n.getLevel() == HierarchyLevel.COMPETENCY)
                .count()).isEqualTo(1);
    }

    @Test
    void createTerminalSkillWithoutClassificationStillCreatesSkill() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        // taxonomyService is a mock and returns null by default — the skill is created without levels
        mockMvc.perform(post("/api/courses/{id}/learning-goals/terminal", course.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"Design a REST API.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.origin").value("TERMINAL"))
                .andExpect(jsonPath("$.bloomLevel").doesNotExist())
                .andExpect(jsonPath("$.soloLevel").doesNotExist());
    }

    @Test
    void createTerminalSkillBlankTextReturns400() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));

        mockMvc.perform(post("/api/courses/{id}/learning-goals/terminal", course.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createTerminalSkillRejectsDuplicateIgnoringCaseAndWhitespace() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));

        mockMvc.perform(post("/api/courses/{id}/learning-goals/terminal", course.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"Design a REST API.\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/courses/{id}/learning-goals/terminal", course.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"  design a rest api.  \"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void createTerminalSkillUnknownCourseReturns404() throws Exception {
        mockMvc.perform(post("/api/courses/{id}/learning-goals/terminal", 999999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"Design a REST API.\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void skillSuggestionsUseExtractedGoalsAndReturnTransientCandidates() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        Document lecture = documentRepository.save(new Document(course, "lecture.pdf", "application/pdf", "evidence"));
        HierarchyNode session = hierarchyRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.SESSION, "Session 1"));
        LearningGoal extracted = new LearningGoal(course, "Configure a secure deployment.", GoalKind.EXPLICIT);
        extracted.setHierarchyNode(session);
        extracted.setBloomLevel(BloomLevel.APPLY);
        extracted = goalRepository.save(extracted);
        goalSourceRepository.save(new GoalSource(extracted, lecture, "secure deployment evidence"));
        goalRepository.save(new LearningGoal(course, "An unrelated goal.", GoalKind.EXPLICIT));

        when(skillSuggestionSynthesizer.suggest(anyList(), anyList(), anyString(), any()))
                .thenReturn(List.of(new SkillSuggestionSynthesizer.Suggestion(
                        "Automate secure deployments.", "Secure Deployment Automation")));

        mockMvc.perform(post("/api/courses/{id}/learning-goals/skill-suggestions", course.getId())
                        .param("model", "test-model"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].text").value("Automate secure deployments."))
                .andExpect(jsonPath("$[0].shortLabel").value("Secure Deployment Automation"));
    }

    @Test
    void skillSuggestionsReturnEmptyWithoutExtractedSessionGoals() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));

        mockMvc.perform(post("/api/courses/{id}/learning-goals/skill-suggestions", course.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(0)));
    }

    @Test
    void generatedTerminalSkillPersistsFullSubtreeAndHierarchyEdges() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        GeneratedSubtree subtree = new GeneratedSubtree(List.of(
                new GeneratedSubSkill("Configure deployment pipelines.", "Deployment Pipelines",
                        List.of(new GeneratedKnowledge("Explain pipeline stages.", "Pipeline Stages"))),
                new GeneratedSubSkill("Automate release checks.", "Release Checks",
                        List.of(new GeneratedKnowledge("Identify release criteria.", "Release Criteria")))));
        when(subtreeSynthesizer.generateSubtree(anyString(), anyString(), any())).thenReturn(subtree);
        when(taxonomyService.classifyBatch(anyList(), any())).thenReturn(List.of(
                new TaxonomyClassification(BloomLevel.CREATE, SoloLevel.RELATIONAL),
                new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL),
                new TaxonomyClassification(BloomLevel.UNDERSTAND, SoloLevel.MULTISTRUCTURAL),
                new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL),
                new TaxonomyClassification(BloomLevel.REMEMBER, SoloLevel.UNISTRUCTURAL)));

        mockMvc.perform(post("/api/courses/{id}/learning-goals/terminal/generated", course.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"Automate secure deployments.\","
                                + "\"shortLabel\": \"Secure Deployment Automation\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value("Automate secure deployments."))
                .andExpect(jsonPath("$.origin").value("TERMINAL"))
                .andExpect(jsonPath("$.creationProvenance").value("WIZARD_AI_SUBTREE"));

        List<LearningGoal> goals = goalRepository.findByCourseId(course.getId());
        assertThat(goals).hasSize(5);
        assertThat(goals).filteredOn(goal -> goal.getOrigin() == GoalOrigin.TERMINAL)
                .singleElement()
                .satisfies(goal -> assertThat(goal.getCreationProvenance())
                        .isEqualTo(GoalCreationProvenance.WIZARD_AI_SUBTREE));
        assertThat(goals).filteredOn(goal -> goal.getOrigin() == GoalOrigin.SYNTHESIZED)
                .allSatisfy(goal -> assertThat(goal.getCreationProvenance())
                        .isEqualTo(GoalCreationProvenance.WIZARD_AI_SUBTREE));
        List<GoalRelationship> relationships = goalRelationshipRepository.findBySourceIdIn(
                goals.stream().map(LearningGoal::getId).toList());
        assertThat(relationships).hasSize(4);
        assertThat(relationships).allSatisfy(relationship -> {
            assertThat(relationship.getType()).isEqualTo(RelationshipType.CONTRIBUTES_TO);
            assertThat(relationship.getOrigin()).isEqualTo(RelationshipOrigin.HIERARCHY);
            assertThat(relationship.getConfidence()).isEqualTo(1.0);
        });
        assertThat(goals).allSatisfy(goal -> assertThat(goal.getStatus()).isEqualTo(GoalStatus.PENDING));
        assertThat(goals).allSatisfy(goal -> assertThat(goal.getBloomLevel()).isNotNull());
        assertThat(goals).filteredOn(goal -> "Configure deployment pipelines.".equals(goal.getText()))
                .singleElement()
                .satisfies(goal -> assertThat(goal.getShortLabel()).isEqualTo("Deployment Pipelines"));
    }

    @Test
    void generatedTerminalSkillBlankTextReturns400() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));

        mockMvc.perform(post("/api/courses/{id}/learning-goals/terminal/generated", course.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void generatedTerminalSkillRejectsDuplicateIgnoringCaseAndWhitespace() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        LearningGoal existing = new LearningGoal(course, "Automate secure deployments.", GoalKind.IMPLICIT);
        existing.setOrigin(GoalOrigin.TERMINAL);
        goalRepository.save(existing);

        mockMvc.perform(post("/api/courses/{id}/learning-goals/terminal/generated", course.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"  automate SECURE deployments.  \"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void generatedTerminalSkillPersistsWhenClassificationFails() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        when(subtreeSynthesizer.generateSubtree(anyString(), anyString(), any()))
                .thenReturn(new GeneratedSubtree(List.of(
                        new GeneratedSubSkill("Configure deployments.", "Deployment Config",
                                List.of(new GeneratedKnowledge("Explain deployment stages.", "Deployment Stages"))))));
        when(taxonomyService.classifyBatch(anyList(), any()))
                .thenThrow(new IllegalStateException("taxonomy unavailable"));

        mockMvc.perform(post("/api/courses/{id}/learning-goals/terminal/generated", course.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"Automate deployments.\"}"))
                .andExpect(status().isCreated());

        assertThat(goalRepository.findByCourseId(course.getId())).hasSize(3)
                .allSatisfy(goal -> {
                    assertThat(goal.getBloomLevel()).isNull();
                    assertThat(goal.getSoloLevel()).isNull();
                });
    }

    @Test
    void deletingGeneratedTerminalSkillRemovesOwnedDescendants() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        HierarchyNode rootNode = hierarchyRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.COMPETENCY, "Terminal Competencies"));
        LearningGoal terminal = generatedGoal(course, "Automate deployments.", GoalOrigin.TERMINAL);
        terminal.setHierarchyNode(rootNode);
        terminal = goalRepository.save(terminal);
        LearningGoal subSkill = goalRepository.save(generatedGoal(
                course, "Configure deployments.", GoalOrigin.SYNTHESIZED));
        LearningGoal knowledge = goalRepository.save(generatedGoal(
                course, "Explain deployment stages.", GoalOrigin.SYNTHESIZED));
        goalRelationshipRepository.save(new GoalRelationship(
                knowledge, subSkill, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));
        goalRelationshipRepository.save(new GoalRelationship(
                subSkill, terminal, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));

        mockMvc.perform(delete("/api/courses/{courseId}/learning-goals/{goalId}", course.getId(), terminal.getId()))
                .andExpect(status().isNoContent());

        assertThat(goalRepository.findById(terminal.getId())).isEmpty();
        assertThat(goalRepository.findById(subSkill.getId())).isEmpty();
        assertThat(goalRepository.findById(knowledge.getId())).isEmpty();
    }

    private LearningGoal generatedGoal(Course course, String text, GoalOrigin origin) {
        LearningGoal goal = new LearningGoal(course, text, GoalKind.IMPLICIT);
        goal.setOrigin(origin);
        goal.setStatus(GoalStatus.PENDING);
        goal.setCreationProvenance(GoalCreationProvenance.WIZARD_AI_SUBTREE);
        return goal;
    }
}
