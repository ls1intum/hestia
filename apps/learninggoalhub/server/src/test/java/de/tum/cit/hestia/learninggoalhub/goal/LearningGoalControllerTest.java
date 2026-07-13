package de.tum.cit.hestia.learninggoalhub.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.hestia.learninggoalhub.TestcontainersConfiguration;
import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import de.tum.cit.hestia.learninggoalhub.document.Document;
import de.tum.cit.hestia.learninggoalhub.document.DocumentRepository;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyLevel;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNode;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNodeRepository;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationship;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationshipRepository;
import de.tum.cit.hestia.learninggoalhub.relationships.RelationshipOrigin;
import de.tum.cit.hestia.learninggoalhub.relationships.RelationshipType;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
    private LearningGoalRepository goalRepository;

    @Autowired
    private GoalSourceRepository goalSourceRepository;

    @Autowired
    private HierarchyNodeRepository hierarchyRepository;

    @Autowired
    private GoalRelationshipRepository goalRelationshipRepository;

    @Test
    void returnsPaginatedGoalsWithKindAndSources() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        Document lecture = documentRepository.save(new Document(course, "lecture.pdf", "application/pdf", "lecture"));
        Document exercise = documentRepository.save(new Document(course, "exercise.pdf", "application/pdf", "exercise"));

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
        goal.setEmbedding(new float[4096]);
        goal = goalRepository.save(goal);

        mockMvc.perform(patch("/api/courses/{courseId}/learning-goals/{goalId}", course.getId(), goal.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"  Apply test-driven development.  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Apply test-driven development."))
                .andExpect(jsonPath("$.status").value("PENDING"));

        LearningGoal reloaded = goalRepository.findById(goal.getId()).orElseThrow();
        assertThat(reloaded.getText()).isEqualTo("Apply test-driven development.");
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
}
