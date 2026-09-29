package de.tum.cit.hestia.learninggoalhub.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import de.tum.cit.hestia.learninggoalhub.document.PageDescription;
import de.tum.cit.hestia.learninggoalhub.document.PageDescriptionRepository;
import de.tum.cit.hestia.learninggoalhub.extraction.SkillSuggestionSynthesizer;
import de.tum.cit.hestia.learninggoalhub.extraction.SubtreeSynthesizer;
import de.tum.cit.hestia.learninggoalhub.extraction.SubtreeSynthesizer.GeneratedKnowledge;
import de.tum.cit.hestia.learninggoalhub.extraction.SubtreeSynthesizer.GeneratedSkill;
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
import java.util.Map;
import java.util.stream.Collectors;
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
    private PageDescriptionRepository pageDescriptionRepository;

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
                .andExpect(jsonPath("$.content[1].sources[0].evidenceKind").value("UNSUPPORTED"))
                // goals without hierarchy/taxonomy/relationships expose null/empty, not missing keys
                .andExpect(jsonPath("$.content[0].hierarchy").doesNotExist())
                .andExpect(jsonPath("$.content[0].bloomLevel").doesNotExist())
                .andExpect(jsonPath("$.content[0].soloLevel").doesNotExist())
                .andExpect(jsonPath("$.content[0].relationships", Matchers.hasSize(0)));
    }

    @Test
    void joinsFigureDescriptionIntoFigureSourceResponse() throws Exception {
        Course course = courseRepository.save(new Course("Figure source API"));
        Document document = documentRepository.save(new Document(
                course, "figures.pdf", "application/pdf", "text"));
        LearningGoal goal = goalRepository.save(new LearningGoal(course, "Explain the diagram.", GoalKind.IMPLICIT));
        goalSourceRepository.save(GoalSource.figure(goal, document, 3));
        pageDescriptionRepository.save(new PageDescription(document, 3, "A diagram shows the data flow.", "vision"));

        mockMvc.perform(get("/api/courses/{id}/learning-goals", course.getId()).param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].sources[0].evidenceKind").value("FIGURE"))
                .andExpect(jsonPath("$.content[0].sources[0].page").value(3))
                .andExpect(jsonPath("$.content[0].sources[0].snippet").value(""))
                .andExpect(jsonPath("$.content[0].sources[0].figureDescription")
                        .value("A diagram shows the data flow."));
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
    void filtersByRoleWithoutChangingTheDefaultList() throws Exception {
        Course course = courseRepository.save(new Course("Role filter"));
        LearningGoal skill = new LearningGoal(course, "Apply TDD.", GoalKind.EXPLICIT);
        skill.setRole(GoalRole.SKILL);
        goalRepository.save(skill);
        LearningGoal knowledge = new LearningGoal(course, "Explain tests.", GoalKind.EXPLICIT);
        knowledge.setRole(GoalRole.KNOWLEDGE);
        goalRepository.save(knowledge);
        goalRepository.save(new LearningGoal(course, "Legacy goal.", GoalKind.IMPLICIT));

        mockMvc.perform(get("/api/courses/{id}/learning-goals", course.getId())
                        .param("role", "KNOWLEDGE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].role").value("KNOWLEDGE"));

        mockMvc.perform(get("/api/courses/{id}/learning-goals/by-session", course.getId())
                        .param("role", "SKILL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].goals[0].role").value("SKILL"));
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
    void patchKeepsGeneratedWordingOnFirstRenameOnly() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        LearningGoal goal = goalRepository.save(new LearningGoal(course, "Apply TDD.", GoalKind.EXPLICIT));

        mockMvc.perform(patch("/api/courses/{courseId}/learning-goals/{goalId}", course.getId(), goal.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalText").doesNotExist());

        mockMvc.perform(patch("/api/courses/{courseId}/learning-goals/{goalId}", course.getId(), goal.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"Apply test-driven development.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalText").value("Apply TDD."));

        mockMvc.perform(patch("/api/courses/{courseId}/learning-goals/{goalId}", course.getId(), goal.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"Practise test-driven development.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Practise test-driven development."))
                .andExpect(jsonPath("$.originalText").value("Apply TDD."));
    }

    @Test
    void patchKeepsNoOriginalForInstructorCreatedGoal() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        LearningGoal goal = new LearningGoal(course, "Apply TDD.", GoalKind.EXPLICIT);
        goal.setCreationProvenance(GoalCreationProvenance.USER_CREATED);
        goal = goalRepository.save(goal);

        mockMvc.perform(patch("/api/courses/{courseId}/learning-goals/{goalId}", course.getId(), goal.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"Apply test-driven development.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalText").doesNotExist());
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
        // SUPPORTS is not a tree edge, so the supporting goal is not beneath the deleted one and survives.
        goalRelationshipRepository.save(new GoalRelationship(
                unitTests, tdd, RelationshipType.SUPPORTS, 1.0, RelationshipOrigin.HIERARCHY));

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

        mockMvc.perform(post("/api/courses/{id}/learning-goals/terminal", course.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"  Design a REST API.  \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value("Design a REST API."))
                .andExpect(jsonPath("$.kind").value("IMPLICIT"))
                .andExpect(jsonPath("$.origin").value("TERMINAL"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.creationProvenance").value("USER_CREATED"))
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

    /** Adding a topic by hand stays manual: nothing is generated beneath it. */
    @Test
    void manualTopicCreationGeneratesNothingBeneathIt() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));

        mockMvc.perform(post("/api/courses/{id}/learning-goals/terminal", course.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"Automate secure deployments.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.origin").value("TERMINAL"))
                .andExpect(jsonPath("$.creationProvenance").value("USER_CREATED"));

        assertThat(goalRepository.findByCourseId(course.getId())).singleElement()
                .satisfies(goal -> {
                    assertThat(goal.getText()).isEqualTo("Automate secure deployments.");
                    assertThat(goal.getCreationProvenance()).isEqualTo(GoalCreationProvenance.USER_CREATED);
                });
        assertThat(goalRelationshipRepository.findBySourceIdIn(
                goalRepository.findByCourseId(course.getId()).stream().map(LearningGoal::getId).toList()))
                .isEmpty();
        verifyNoInteractions(subtreeSynthesizer);
    }

    @Test
    void createTerminalSkillLeavesTaxonomyLevelsUnset() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));

        mockMvc.perform(post("/api/courses/{id}/learning-goals/terminal", course.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"Design a REST API.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.origin").value("TERMINAL"))
                .andExpect(jsonPath("$.bloomLevel").doesNotExist())
                .andExpect(jsonPath("$.soloLevel").doesNotExist());
        // A typed skill is the instructor's wording; the levels are theirs to set in the review.
        verify(taxonomyService, never()).classify(any());
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
                new GeneratedSkill("Automate the release process", "Automate releases", List.of(
                        new GeneratedSubSkill("Configure deployment pipelines.", "Deployment Pipelines",
                                List.of(new GeneratedKnowledge("Explain pipeline stages.", "Pipeline Stages"))),
                        new GeneratedSubSkill("Automate release checks.", "Release Checks",
                                List.of(new GeneratedKnowledge("Identify release criteria.",
                                        "Release Criteria")))))));
        when(subtreeSynthesizer.generateSubtree(anyString(), anyString(), any())).thenReturn(subtree);
        // One classification per generated node; the topic itself is not classified.
        when(taxonomyService.classifyBatch(anyList(), any())).thenReturn(List.of(
                new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL),
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
        assertThat(goals).hasSize(6);
        assertThat(goals).filteredOn(goal -> goal.getOrigin() == GoalOrigin.TERMINAL)
                .singleElement()
                .satisfies(goal -> assertThat(goal.getCreationProvenance())
                        .isEqualTo(GoalCreationProvenance.WIZARD_AI_SUBTREE));
        assertThat(goals).filteredOn(goal -> goal.getOrigin() == GoalOrigin.SYNTHESIZED)
                .allSatisfy(goal -> assertThat(goal.getCreationProvenance())
                        .isEqualTo(GoalCreationProvenance.WIZARD_AI_SUBTREE));
        // Every generated node states its tier: skills and sub-skills are SKILL, knowledge is KNOWLEDGE.
        assertThat(goals).filteredOn(goal -> goal.getOrigin() == GoalOrigin.SYNTHESIZED)
                .extracting(LearningGoal::getText, LearningGoal::getRole)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("Automate the release process", GoalRole.SKILL),
                        org.assertj.core.groups.Tuple.tuple("Configure deployment pipelines.", GoalRole.SKILL),
                        org.assertj.core.groups.Tuple.tuple("Explain pipeline stages.", GoalRole.KNOWLEDGE),
                        org.assertj.core.groups.Tuple.tuple("Automate release checks.", GoalRole.SKILL),
                        org.assertj.core.groups.Tuple.tuple("Identify release criteria.", GoalRole.KNOWLEDGE));
        Map<String, Long> idByText = goals.stream()
                .collect(Collectors.toMap(LearningGoal::getText, LearningGoal::getId));
        List<GoalRelationship> relationships = goalRelationshipRepository.findBySourceIdIn(
                goals.stream().map(LearningGoal::getId).toList());
        assertThat(relationships).hasSize(5);
        assertThat(relationships)
                .extracting(relationship -> relationship.getSource().getId(),
                        relationship -> relationship.getTarget().getId())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(idByText.get("Automate the release process"),
                                idByText.get("Automate secure deployments.")),
                        org.assertj.core.groups.Tuple.tuple(idByText.get("Configure deployment pipelines."),
                                idByText.get("Automate the release process")),
                        org.assertj.core.groups.Tuple.tuple(idByText.get("Automate release checks."),
                                idByText.get("Automate the release process")),
                        org.assertj.core.groups.Tuple.tuple(idByText.get("Explain pipeline stages."),
                                idByText.get("Configure deployment pipelines.")),
                        org.assertj.core.groups.Tuple.tuple(idByText.get("Identify release criteria."),
                                idByText.get("Automate release checks.")));
        assertThat(relationships).allSatisfy(relationship -> {
            assertThat(relationship.getType()).isEqualTo(RelationshipType.CONTRIBUTES_TO);
            assertThat(relationship.getOrigin()).isEqualTo(RelationshipOrigin.HIERARCHY);
            assertThat(relationship.getConfidence()).isEqualTo(1.0);
        });
        assertThat(goals).allSatisfy(goal -> assertThat(goal.getStatus()).isEqualTo(GoalStatus.PENDING));
        assertThat(goals).filteredOn(goal -> goal.getOrigin() == GoalOrigin.SYNTHESIZED)
                .allSatisfy(goal -> assertThat(goal.getBloomLevel()).isNotNull());
        assertThat(goals).filteredOn(goal -> goal.getOrigin() == GoalOrigin.TERMINAL)
                .singleElement()
                .satisfies(goal -> {
                    assertThat(goal.getBloomLevel()).isNull();
                    assertThat(goal.getSoloLevel()).isNull();
                });
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
                .thenReturn(subtree("Run deployments", "Configure deployments.", "Explain deployment stages."));
        when(taxonomyService.classifyBatch(anyList(), any()))
                .thenThrow(new IllegalStateException("taxonomy unavailable"));

        mockMvc.perform(post("/api/courses/{id}/learning-goals/terminal/generated", course.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"Automate deployments.\"}"))
                .andExpect(status().isCreated());

        assertThat(goalRepository.findByCourseId(course.getId())).hasSize(4)
                .allSatisfy(goal -> {
                    assertThat(goal.getBloomLevel()).isNull();
                    assertThat(goal.getSoloLevel()).isNull();
                });
    }

    @Test
    void generatesSubtreeForChildlessUserCreatedTerminal() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        HierarchyNode rootNode = hierarchyRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.COMPETENCY, "Terminal Competencies"));
        LearningGoal terminal = terminalGoal(course, "Automate secure deployments.",
                GoalCreationProvenance.USER_CREATED);
        terminal.setShortLabel("Secure Deployment Automation");
        terminal.setBloomLevel(BloomLevel.APPLY);
        terminal.setSoloLevel(SoloLevel.RELATIONAL);
        terminal.setHierarchyNode(rootNode);
        terminal = goalRepository.saveAndFlush(terminal);
        Long terminalId = terminal.getId();

        when(subtreeSynthesizer.generateSubtree(anyString(), anyString(), any()))
                .thenReturn(subtree("Automate the release process", "Configure deployment pipelines.",
                        "Explain pipeline stages."));

        mockMvc.perform(post("/api/courses/{courseId}/learning-goals/{goalId}/subtree",
                        course.getId(), terminalId)
                        .param("model", "test-model"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(terminalId.intValue()))
                .andExpect(jsonPath("$.text").value("Automate secure deployments."))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.creationProvenance").value("USER_CREATED"));

        assertThat(goalRepository.findById(terminalId)).isPresent()
                .get()
                .satisfies(persisted -> {
                    assertThat(persisted.getCreationProvenance())
                            .isEqualTo(GoalCreationProvenance.USER_CREATED);
                    assertThat(persisted.getShortLabel()).isEqualTo("Secure Deployment Automation");
                    assertThat(persisted.getBloomLevel()).isEqualTo(BloomLevel.APPLY);
                    assertThat(persisted.getSoloLevel()).isEqualTo(SoloLevel.RELATIONAL);
                    assertThat(persisted.getHierarchyNode().getId()).isEqualTo(rootNode.getId());
                });
        assertThat(goalRepository.findByCourseId(course.getId())).hasSize(4)
                .filteredOn(goal -> goal.getOrigin() == GoalOrigin.SYNTHESIZED)
                .extracting(LearningGoal::getText)
                .containsExactlyInAnyOrder("Automate the release process", "Configure deployment pipelines.",
                        "Explain pipeline stages.");
        assertThat(goalRelationshipRepository.findByTargetId(terminalId)).hasSize(1);
    }

    @Test
    void replacesWizardGeneratedSubtreeWithoutChangingTerminal() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        HierarchyNode rootNode = hierarchyRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.COMPETENCY, "Terminal Competencies"));
        LearningGoal terminal = terminalGoal(course, "Automate secure deployments.",
                GoalCreationProvenance.WIZARD_AI_SUBTREE);
        terminal.setShortLabel("Secure Deployment Automation");
        terminal.setStatus(GoalStatus.APPROVED);
        terminal.setBloomLevel(BloomLevel.CREATE);
        terminal.setSoloLevel(SoloLevel.RELATIONAL);
        terminal.setHierarchyNode(rootNode);
        terminal = goalRepository.saveAndFlush(terminal);
        Long terminalId = terminal.getId();
        var createdAt = terminal.getCreatedAt();
        LearningGoal oldSubSkill = goalRepository.save(
                generatedGoal(course, "Configure old pipelines.", GoalOrigin.SYNTHESIZED));
        LearningGoal oldKnowledge = goalRepository.save(
                generatedGoal(course, "Explain old pipeline stages.", GoalOrigin.SYNTHESIZED));
        goalRelationshipRepository.save(new GoalRelationship(
                oldKnowledge, oldSubSkill, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));
        goalRelationshipRepository.saveAndFlush(new GoalRelationship(
                oldSubSkill, terminal, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));
        LearningGoal manualChild = goalRepository.saveAndFlush(userCreatedGoal(
                course, "Review deployment risks.", GoalOrigin.SYNTHESIZED));
        goalRelationshipRepository.saveAndFlush(new GoalRelationship(
                manualChild, terminal, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));

        when(subtreeSynthesizer.generateSubtree(anyString(), anyString(), any()))
                .thenReturn(subtree("Release new builds", "Configure new pipelines.",
                        "Explain new pipeline stages."));

        mockMvc.perform(post("/api/courses/{courseId}/learning-goals/{goalId}/subtree",
                        course.getId(), terminalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(terminalId.intValue()))
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(goalRepository.findById(oldSubSkill.getId())).isEmpty();
        assertThat(goalRepository.findById(oldKnowledge.getId())).isEmpty();
        assertThat(goalRepository.findById(manualChild.getId())).isPresent();
        assertThat(goalRepository.findByCourseId(course.getId())).hasSize(5)
                .extracting(LearningGoal::getText)
                .containsExactlyInAnyOrder("Automate secure deployments.", "Release new builds",
                        "Configure new pipelines.", "Explain new pipeline stages.", "Review deployment risks.");
        assertThat(goalRepository.findById(terminalId)).get().satisfies(persisted -> {
            assertThat(persisted.getId()).isEqualTo(terminalId);
            assertThat(persisted.getText()).isEqualTo("Automate secure deployments.");
            assertThat(persisted.getShortLabel()).isEqualTo("Secure Deployment Automation");
            assertThat(persisted.getStatus()).isEqualTo(GoalStatus.APPROVED);
            assertThat(persisted.getCreatedAt()).isEqualTo(createdAt);
            assertThat(persisted.getBloomLevel()).isEqualTo(BloomLevel.CREATE);
            assertThat(persisted.getSoloLevel()).isEqualTo(SoloLevel.RELATIONAL);
            assertThat(persisted.getHierarchyNode().getId()).isEqualTo(rootNode.getId());
            assertThat(persisted.getCreationProvenance()).isEqualTo(GoalCreationProvenance.WIZARD_AI_SUBTREE);
        });
        // The relationship's source is a lazy proxy outside the request's session, so compare ids
        // rather than dereferencing the goal.
        Long newSkillId = goalRepository.findByCourseId(course.getId()).stream()
                .filter(goal -> "Release new builds".equals(goal.getText()))
                .map(LearningGoal::getId)
                .findFirst()
                .orElseThrow();
        assertThat(goalRelationshipRepository.findByTargetId(terminalId))
                .extracting(relationship -> relationship.getSource().getId())
                .containsExactlyInAnyOrder(newSkillId, manualChild.getId());
    }

    @Test
    void subtreeGenerationRejectsNonTerminalGoal() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        LearningGoal goal = goalRepository.saveAndFlush(
                new LearningGoal(course, "Configure deployment pipelines.", GoalKind.EXPLICIT));

        mockMvc.perform(post("/api/courses/{courseId}/learning-goals/{goalId}/subtree",
                        course.getId(), goal.getId()))
                .andExpect(status().isConflict());
    }

    @Test
    void subtreeGenerationRejectsPipelineClusteredTerminal() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        // No creation provenance: the pipeline clustered this terminal together with its structure.
        LearningGoal terminal = goalRepository.saveAndFlush(terminalGoal(
                course, "Automate secure deployments.", null));

        mockMvc.perform(post("/api/courses/{courseId}/learning-goals/{goalId}/subtree",
                        course.getId(), terminal.getId()))
                .andExpect(status().isConflict());

        verifyNoInteractions(subtreeSynthesizer);
    }

    @Test
    void subtreeGenerationKeepsExtractedContributor() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        LearningGoal terminal = goalRepository.saveAndFlush(terminalGoal(
                course, "Automate secure deployments.", GoalCreationProvenance.USER_CREATED));
        LearningGoal extracted = goalRepository.save(
                new LearningGoal(course, "Configure deployment pipelines.", GoalKind.EXPLICIT));
        goalRelationshipRepository.saveAndFlush(new GoalRelationship(
                extracted, terminal, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));

        when(subtreeSynthesizer.generateSubtree(anyString(), anyString(), any()))
                .thenReturn(subtree("Release new builds", "Configure new pipelines.",
                        "Explain new pipeline stages."));

        mockMvc.perform(post("/api/courses/{courseId}/learning-goals/{goalId}/subtree",
                        course.getId(), terminal.getId()))
                .andExpect(status().isOk());

        // An extracted goal carries no creation provenance and is therefore never owned by the
        // wizard: regeneration must leave it, and its edge to the terminal, in place.
        assertThat(goalRepository.findById(extracted.getId())).isPresent();
        assertThat(goalRelationshipRepository.findByTargetId(terminal.getId()))
                .extracting(relationship -> relationship.getSource().getId())
                .contains(extracted.getId());
        assertThat(goalRepository.findByCourseId(course.getId()))
                .extracting(LearningGoal::getText)
                .containsExactlyInAnyOrder("Automate secure deployments.",
                        "Configure deployment pipelines.", "Release new builds", "Configure new pipelines.",
                        "Explain new pipeline stages.");
    }

    @Test
    void failedSubtreeGenerationLeavesExistingSubtreeIntact() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        LearningGoal terminal = goalRepository.saveAndFlush(terminalGoal(
                course, "Automate secure deployments.", GoalCreationProvenance.WIZARD_AI_SUBTREE));
        LearningGoal oldSubSkill = goalRepository.save(
                generatedGoal(course, "Configure old pipelines.", GoalOrigin.SYNTHESIZED));
        LearningGoal oldKnowledge = goalRepository.save(
                generatedGoal(course, "Explain old pipeline stages.", GoalOrigin.SYNTHESIZED));
        goalRelationshipRepository.save(new GoalRelationship(
                oldKnowledge, oldSubSkill, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));
        goalRelationshipRepository.saveAndFlush(new GoalRelationship(
                oldSubSkill, terminal, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));
        when(subtreeSynthesizer.generateSubtree(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("LLM unavailable"));

        mockMvc.perform(post("/api/courses/{courseId}/learning-goals/{goalId}/subtree",
                        course.getId(), terminal.getId()))
                .andExpect(status().isInternalServerError());

        assertThat(goalRepository.findById(terminal.getId())).isPresent();
        assertThat(goalRepository.findById(oldSubSkill.getId())).isPresent();
        assertThat(goalRepository.findById(oldKnowledge.getId())).isPresent();
        assertThat(goalRelationshipRepository.findByTargetId(terminal.getId())).hasSize(1);
        assertThat(goalRelationshipRepository.findByTargetId(oldSubSkill.getId())).hasSize(1);
    }

    @Test
    void addsManualChildToTerminal() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        LearningGoal terminal = goalRepository.saveAndFlush(terminalGoal(
                course, "Automate secure deployments.", GoalCreationProvenance.USER_CREATED));

        mockMvc.perform(post("/api/courses/{courseId}/learning-goals/{goalId}/children",
                        course.getId(), terminal.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"Review deployment risks.\","
                                + "\"shortLabel\": \"Deployment Risks\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value("Review deployment risks."))
                .andExpect(jsonPath("$.shortLabel").value("Deployment Risks"))
                .andExpect(jsonPath("$.origin").value("SYNTHESIZED"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.creationProvenance").value("USER_CREATED"))
                .andExpect(jsonPath("$.hierarchy").doesNotExist())
                // Manually added nodes stay unclassified, like a typed skill.
                .andExpect(jsonPath("$.bloomLevel").doesNotExist())
                .andExpect(jsonPath("$.soloLevel").doesNotExist());
        verify(taxonomyService, never()).classify(any());

        LearningGoal child = goalRepository.findByCourseId(course.getId()).stream()
                .filter(goal -> "Review deployment risks.".equals(goal.getText()))
                .findFirst()
                .orElseThrow();
        assertThat(child.getHierarchyNode()).isNull();
        assertThat(goalRelationshipRepository.findByTargetId(terminal.getId()))
                .extracting(relationship -> relationship.getSource().getId())
                .containsExactly(child.getId());
    }

    @Test
    void addsManualChildToSubSkill() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        LearningGoal terminal = goalRepository.saveAndFlush(terminalGoal(
                course, "Automate secure deployments.", GoalCreationProvenance.WIZARD_AI_SUBTREE));
        LearningGoal subSkill = goalRepository.saveAndFlush(
                generatedGoal(course, "Configure deployment pipelines.", GoalOrigin.SYNTHESIZED));
        // A sub-skill is tier 2 by virtue of contributing to a terminal, which is what makes it
        // eligible to take knowledge children.
        goalRelationshipRepository.saveAndFlush(new GoalRelationship(
                subSkill, terminal, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));

        mockMvc.perform(post("/api/courses/{courseId}/learning-goals/{goalId}/children",
                        course.getId(), subSkill.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"Explain pipeline stages.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.origin").value("SYNTHESIZED"))
                .andExpect(jsonPath("$.creationProvenance").value("USER_CREATED"));

        LearningGoal child = goalRepository.findByCourseId(course.getId()).stream()
                .filter(goal -> "Explain pipeline stages.".equals(goal.getText()))
                .findFirst()
                .orElseThrow();
        assertThat(goalRelationshipRepository.findByTargetId(subSkill.getId()))
                .extracting(relationship -> relationship.getSource().getId())
                .containsExactly(child.getId());
    }

    @Test
    void acceptsSixthManualSkillUnderTopic() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        LearningGoal terminal = goalRepository.saveAndFlush(terminalGoal(
                course, "Automate secure deployments.", GoalCreationProvenance.USER_CREATED));
        for (int index = 1; index <= 5; index++) {
            LearningGoal child = goalRepository.saveAndFlush(
                    generatedGoal(course, "Sub-skill " + index + ".", GoalOrigin.SYNTHESIZED));
            goalRelationshipRepository.save(new GoalRelationship(
                    child, terminal, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));
        }
        goalRelationshipRepository.flush();

        mockMvc.perform(post("/api/courses/{courseId}/learning-goals/{goalId}/children",
                        course.getId(), terminal.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"A sixth skill.\", \"role\": \"SKILL\"}"))
                .andExpect(status().isCreated());

        assertThat(goalRepository.findByCourseId(course.getId())).hasSize(7);
    }

    @Test
    void addsManualChildToExtractedGoal() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        LearningGoal terminal = goalRepository.saveAndFlush(terminalGoal(
                course, "Automate secure deployments.", GoalCreationProvenance.WIZARD_AI_SUBTREE));
        LearningGoal extracted = goalRepository.saveAndFlush(
                new LearningGoal(course, "Configure deployment pipelines.", GoalKind.EXPLICIT));
        goalRelationshipRepository.saveAndFlush(new GoalRelationship(
                extracted, terminal, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));

        mockMvc.perform(post("/api/courses/{courseId}/learning-goals/{goalId}/children",
                        course.getId(), extracted.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"Identify release criteria.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.origin").value("SYNTHESIZED"))
                .andExpect(jsonPath("$.creationProvenance").value("USER_CREATED"));

        LearningGoal child = goalRepository.findByCourseId(course.getId()).stream()
                .filter(goal -> "Identify release criteria.".equals(goal.getText()))
                .findFirst()
                .orElseThrow();
        assertThat(goalRelationshipRepository.findByTargetId(extracted.getId()))
                .extracting(relationship -> relationship.getSource().getId())
                .containsExactly(child.getId());
    }

    @Test
    void rejectsManualChildUnderKnowledge() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        LearningGoal terminal = goalRepository.saveAndFlush(terminalGoal(
                course, "Automate secure deployments.", GoalCreationProvenance.WIZARD_AI_SUBTREE));
        LearningGoal subSkill = goalRepository.saveAndFlush(
                generatedGoal(course, "Configure deployment pipelines.", GoalOrigin.SYNTHESIZED));
        LearningGoal knowledge = goalRepository.saveAndFlush(
                generatedGoal(course, "Explain pipeline stages.", GoalOrigin.SYNTHESIZED));
        goalRelationshipRepository.save(new GoalRelationship(
                subSkill, terminal, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));
        goalRelationshipRepository.saveAndFlush(new GoalRelationship(
                knowledge, subSkill, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));

        // Knowledge takes no children, so a child here would never render in any view.
        mockMvc.perform(post("/api/courses/{courseId}/learning-goals/{goalId}/children",
                        course.getId(), knowledge.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"Name the stage order.\"}"))
                .andExpect(status().isConflict());

        assertThat(goalRepository.findByCourseId(course.getId())).hasSize(3);
    }

    @Test
    void addsManualChildWithTheRoleItWasAddedAs() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        LearningGoal topic = goalRepository.saveAndFlush(terminalGoal(
                course, "Deployment automation", GoalCreationProvenance.USER_CREATED));

        mockMvc.perform(post("/api/courses/{courseId}/learning-goals/{goalId}/children",
                        course.getId(), topic.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"Automate secure deployments.\", \"role\": \"SKILL\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("SKILL"));
    }

    @Test
    void addsManualKnowledgeToSubSkillBeneathASkill() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        LearningGoal topic = goalRepository.saveAndFlush(terminalGoal(
                course, "Deployment automation", GoalCreationProvenance.WIZARD_AI_SUBTREE));
        LearningGoal skill = generatedGoal(course, "Automate secure deployments.", GoalOrigin.SYNTHESIZED);
        skill.setRole(GoalRole.SKILL);
        goalRepository.saveAndFlush(skill);
        LearningGoal subSkill = generatedGoal(course, "Configure deployment pipelines.", GoalOrigin.SYNTHESIZED);
        subSkill.setRole(GoalRole.SKILL);
        goalRepository.saveAndFlush(subSkill);
        goalRelationshipRepository.save(new GoalRelationship(
                skill, topic, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));
        goalRelationshipRepository.saveAndFlush(new GoalRelationship(
                subSkill, skill, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));

        // The sub-skill sits two tiers below the topic, which the three-tier rule used to refuse.
        mockMvc.perform(post("/api/courses/{courseId}/learning-goals/{goalId}/children",
                        course.getId(), subSkill.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"Explain pipeline stages.\", \"role\": \"KNOWLEDGE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("KNOWLEDGE"));

        assertThat(goalRelationshipRepository.findByTargetId(subSkill.getId())).hasSize(1);
    }

    @Test
    void rejectsManualChildUnderKnowledgeDirectlyBeneathATopic() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        LearningGoal topic = goalRepository.saveAndFlush(terminalGoal(
                course, "Deployment automation", GoalCreationProvenance.WIZARD_AI_SUBTREE));
        LearningGoal knowledge = generatedGoal(course, "Explain pipeline stages.", GoalOrigin.SYNTHESIZED);
        knowledge.setRole(GoalRole.KNOWLEDGE);
        goalRepository.saveAndFlush(knowledge);
        goalRelationshipRepository.saveAndFlush(new GoalRelationship(
                knowledge, topic, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));

        mockMvc.perform(post("/api/courses/{courseId}/learning-goals/{goalId}/children",
                        course.getId(), knowledge.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"Name the stage order.\"}"))
                .andExpect(status().isConflict());

        assertThat(goalRepository.findByCourseId(course.getId())).hasSize(2);
    }

    @Test
    void deletingGeneratedTopicRemovesEverythingBeneathIt() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        Document lecture = documentRepository.save(new Document(course, "lecture.pdf", "application/pdf", "evidence"));
        HierarchyNode rootNode = hierarchyRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.COMPETENCY, "Terminal Competencies"));
        LearningGoal terminal = generatedGoal(course, "Automate deployments.", GoalOrigin.TERMINAL);
        terminal.setHierarchyNode(rootNode);
        terminal = goalRepository.save(terminal);
        LearningGoal subSkill = goalRepository.save(generatedGoal(
                course, "Configure deployments.", GoalOrigin.SYNTHESIZED));
        LearningGoal knowledge = goalRepository.save(generatedGoal(
                course, "Explain deployment stages.", GoalOrigin.SYNTHESIZED));
        LearningGoal manualChild = goalRepository.save(userCreatedGoal(
                course, "Review deployment risks.", GoalOrigin.SYNTHESIZED));
        LearningGoal extracted = goalRepository.save(
                new LearningGoal(course, "Configure deployment pipelines.", GoalKind.EXPLICIT));
        goalSourceRepository.save(new GoalSource(extracted, lecture, "deployment pipeline evidence"));
        goalRelationshipRepository.save(new GoalRelationship(
                knowledge, subSkill, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));
        goalRelationshipRepository.save(new GoalRelationship(
                subSkill, terminal, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));
        goalRelationshipRepository.save(new GoalRelationship(
                manualChild, terminal, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));
        goalRelationshipRepository.saveAndFlush(new GoalRelationship(
                extracted, terminal, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));

        mockMvc.perform(delete("/api/courses/{courseId}/learning-goals/{goalId}", course.getId(), terminal.getId()))
                .andExpect(status().isNoContent());

        assertThat(goalRepository.findById(terminal.getId())).isEmpty();
        assertThat(goalRepository.findById(subSkill.getId())).isEmpty();
        assertThat(goalRepository.findById(knowledge.getId())).isEmpty();
        assertThat(goalRepository.findById(manualChild.getId())).isEmpty();
        assertThat(goalRepository.findById(extracted.getId())).isEmpty();
        assertThat(goalSourceRepository.findByGoalId(extracted.getId())).isEmpty();
        assertThat(goalRelationshipRepository.findBySourceId(extracted.getId())).isEmpty();
    }

    @Test
    void deletingSubSkillRemovesOwnedKnowledge() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        LearningGoal subSkill = goalRepository.save(
                generatedGoal(course, "Configure deployment pipelines.", GoalOrigin.SYNTHESIZED));
        LearningGoal knowledge = goalRepository.saveAndFlush(
                generatedGoal(course, "Explain pipeline stages.", GoalOrigin.SYNTHESIZED));
        goalRelationshipRepository.saveAndFlush(new GoalRelationship(
                knowledge, subSkill, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));

        mockMvc.perform(delete("/api/courses/{courseId}/learning-goals/{goalId}", course.getId(), subSkill.getId()))
                .andExpect(status().isNoContent());

        assertThat(goalRepository.findById(subSkill.getId())).isEmpty();
        assertThat(goalRepository.findById(knowledge.getId())).isEmpty();
    }

    @Test
    void deletingPipelineTopicRemovesItsSkillsAndExtractedGoals() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        Document lecture = documentRepository.save(new Document(course, "lecture.pdf", "application/pdf", "evidence"));
        LearningGoal topic = goalRepository.saveAndFlush(terminalGoal(course, "Deployment automation", null));
        LearningGoal skill = new LearningGoal(course, "Automate secure deployments.", GoalKind.IMPLICIT);
        skill.setOrigin(GoalOrigin.SYNTHESIZED);
        skill.setRole(GoalRole.SKILL);
        skill = goalRepository.saveAndFlush(skill);
        LearningGoal subSkill = goalRepository.saveAndFlush(
                new LearningGoal(course, "Configure deployment pipelines.", GoalKind.EXPLICIT));
        LearningGoal knowledge = goalRepository.saveAndFlush(
                new LearningGoal(course, "Explain pipeline stages.", GoalKind.EXPLICIT));
        LearningGoal directSubSkill = goalRepository.saveAndFlush(
                new LearningGoal(course, "Roll back a failed release.", GoalKind.EXPLICIT));
        goalSourceRepository.save(new GoalSource(subSkill, lecture, "deployment pipeline evidence"));
        goalRelationshipRepository.save(new GoalRelationship(
                skill, topic, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.SYNTHESIS));
        goalRelationshipRepository.save(new GoalRelationship(
                subSkill, skill, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.SYNTHESIS));
        goalRelationshipRepository.save(new GoalRelationship(
                knowledge, subSkill, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));
        goalRelationshipRepository.saveAndFlush(new GoalRelationship(
                directSubSkill, topic, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.SYNTHESIS));

        mockMvc.perform(delete("/api/courses/{courseId}/learning-goals/{goalId}", course.getId(), topic.getId()))
                .andExpect(status().isNoContent());

        assertThat(goalRepository.findByCourseId(course.getId())).isEmpty();
        assertThat(goalSourceRepository.findByGoalId(subSkill.getId())).isEmpty();
    }

    @Test
    void deletingPipelineSkillRemovesItsExtractedSubSkills() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        LearningGoal topic = goalRepository.saveAndFlush(terminalGoal(course, "Deployment automation", null));
        LearningGoal skill = new LearningGoal(course, "Automate secure deployments.", GoalKind.IMPLICIT);
        skill.setOrigin(GoalOrigin.SYNTHESIZED);
        skill.setRole(GoalRole.SKILL);
        skill = goalRepository.saveAndFlush(skill);
        LearningGoal subSkill = goalRepository.saveAndFlush(
                new LearningGoal(course, "Configure deployment pipelines.", GoalKind.EXPLICIT));
        LearningGoal sibling = goalRepository.saveAndFlush(
                new LearningGoal(course, "Roll back a failed release.", GoalKind.EXPLICIT));
        goalRelationshipRepository.save(new GoalRelationship(
                skill, topic, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.SYNTHESIS));
        goalRelationshipRepository.save(new GoalRelationship(
                sibling, topic, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.SYNTHESIS));
        goalRelationshipRepository.saveAndFlush(new GoalRelationship(
                subSkill, skill, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.SYNTHESIS));

        mockMvc.perform(delete("/api/courses/{courseId}/learning-goals/{goalId}", course.getId(), skill.getId()))
                .andExpect(status().isNoContent());

        assertThat(goalRepository.findById(topic.getId())).isPresent();
        assertThat(goalRepository.findById(sibling.getId())).isPresent();
        assertThat(goalRepository.findById(skill.getId())).isEmpty();
        assertThat(goalRepository.findById(subSkill.getId())).isEmpty();
    }

    @Test
    void deletingKeepsADescendantThatStillContributesElsewhere() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        LearningGoal topic = goalRepository.saveAndFlush(terminalGoal(course, "Deployment automation", null));
        LearningGoal otherTopic = goalRepository.saveAndFlush(terminalGoal(course, "Release management", null));
        LearningGoal shared = goalRepository.saveAndFlush(
                new LearningGoal(course, "Configure deployment pipelines.", GoalKind.EXPLICIT));
        LearningGoal knowledge = goalRepository.saveAndFlush(
                new LearningGoal(course, "Explain pipeline stages.", GoalKind.EXPLICIT));
        goalRelationshipRepository.save(new GoalRelationship(
                shared, topic, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.SYNTHESIS));
        goalRelationshipRepository.save(new GoalRelationship(
                shared, otherTopic, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.SYNTHESIS));
        goalRelationshipRepository.saveAndFlush(new GoalRelationship(
                knowledge, shared, RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));

        mockMvc.perform(delete("/api/courses/{courseId}/learning-goals/{goalId}", course.getId(), topic.getId()))
                .andExpect(status().isNoContent());

        assertThat(goalRepository.findById(topic.getId())).isEmpty();
        assertThat(goalRepository.findById(shared.getId())).isPresent();
        assertThat(goalRepository.findById(knowledge.getId())).isPresent();
        assertThat(goalRelationshipRepository.findBySourceId(shared.getId()))
                .extracting(relationship -> relationship.getTarget().getId())
                .containsExactly(otherTopic.getId());
    }

    /** A generated subtree with one skill, one sub-skill beneath it and one knowledge item beneath that. */
    private static GeneratedSubtree subtree(String skill, String subSkill, String knowledge) {
        return new GeneratedSubtree(List.of(new GeneratedSkill(skill, null, List.of(
                new GeneratedSubSkill(subSkill, null, List.of(new GeneratedKnowledge(knowledge, null)))))));
    }

    private LearningGoal generatedGoal(Course course, String text, GoalOrigin origin) {
        LearningGoal goal = new LearningGoal(course, text, GoalKind.IMPLICIT);
        goal.setOrigin(origin);
        goal.setStatus(GoalStatus.PENDING);
        goal.setCreationProvenance(GoalCreationProvenance.WIZARD_AI_SUBTREE);
        return goal;
    }

    private LearningGoal terminalGoal(Course course, String text, GoalCreationProvenance provenance) {
        LearningGoal goal = new LearningGoal(course, text, GoalKind.IMPLICIT);
        goal.setOrigin(GoalOrigin.TERMINAL);
        goal.setStatus(GoalStatus.PENDING);
        goal.setCreationProvenance(provenance);
        return goal;
    }

    private LearningGoal userCreatedGoal(Course course, String text, GoalOrigin origin) {
        LearningGoal goal = new LearningGoal(course, text, GoalKind.IMPLICIT);
        goal.setOrigin(origin);
        goal.setStatus(GoalStatus.PENDING);
        goal.setCreationProvenance(GoalCreationProvenance.USER_CREATED);
        return goal;
    }
}
