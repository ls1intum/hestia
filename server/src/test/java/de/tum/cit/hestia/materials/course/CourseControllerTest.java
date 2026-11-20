package de.tum.cit.hestia.materials.course;

import de.tum.cit.hestia.materials.TestTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.tum.cit.hestia.materials.MaterialsTestBase;
import de.tum.cit.hestia.materials.MaterialsTestConfiguration;
import de.tum.cit.hestia.materials.document.Document;
import de.tum.cit.hestia.materials.document.DocumentRepository;
import de.tum.cit.hestia.materials.goal.GoalCreationProvenance;
import de.tum.cit.hestia.core.CourseApi;
import de.tum.cit.hestia.core.CourseNotFoundException;
import de.tum.cit.hestia.core.GoalNotFoundException;
import de.tum.cit.hestia.core.GoalKind;
import de.tum.cit.hestia.core.LearningGoalApi;
import de.tum.cit.hestia.materials.goal.GoalPlacementRepository;
import de.tum.cit.hestia.core.GoalOrigin;
import de.tum.cit.hestia.core.GoalRole;
import de.tum.cit.hestia.materials.GoalFixtures;
import de.tum.cit.hestia.materials.goal.PlacedGoal;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// GET /api/courses lists every course, and these @SpringBootTest classes share one committed
// Testcontainers DB, so assertions key on the ids created here rather than on absolute totals.
@Import(MaterialsTestConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CourseControllerTest extends MaterialsTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MaterialSettingsRepository settingsRepository;

    @Autowired
    private MaterialSettingsService settingsService;

    @Autowired
    private CourseApi courseApi;

    @Autowired
    private LearningGoalApi goalApi;

    @Autowired
    private GoalPlacementRepository placementRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Test
    void exposesDocumentAndGoalCountsPerCourse() throws Exception {
        UUID empty = TestTenant.course("Empty Course");

        UUID withDocs = TestTenant.course("Course With Documents");
        documentRepository.save(new Document(withDocs, "lecture.pdf", "application/pdf", "lecture"));
        documentRepository.save(new Document(withDocs, "exercise.pdf", "application/pdf", "exercise"));

        UUID analyzed = TestTenant.course("Analyzed Course");
        documentRepository.save(new Document(analyzed, "slides.pdf", "application/pdf", "slides"));
        GoalFixtures.goal(analyzed, "Apply TDD.").kind(GoalKind.EXPLICIT).create();
        GoalFixtures.goal(analyzed, "Value refactoring.").kind(GoalKind.IMPLICIT).create();
        GoalFixtures.goal(analyzed, "Understand testing.").kind(GoalKind.EXPLICIT).create();

        mockMvc.perform(get("/learninggoalhub/api/courses").param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '%s')].name", analyzed)
                        .value(Matchers.contains("Analyzed Course")))
                .andExpect(jsonPath("$.content[?(@.id == '%s')].documentCount", analyzed)
                        .value(Matchers.contains(1)))
                .andExpect(jsonPath("$.content[?(@.id == '%s')].goalCount", analyzed)
                        .value(Matchers.contains(3)))
                .andExpect(jsonPath("$.content[?(@.id == '%s')].createdAt", analyzed)
                        .value(Matchers.hasSize(1)))
                .andExpect(jsonPath("$.content[?(@.id == '%s')].figuresEnabled", analyzed)
                        .value(Matchers.contains(false)))
                .andExpect(jsonPath("$.content[?(@.id == '%s')].documentCount", withDocs)
                        .value(Matchers.contains(2)))
                .andExpect(jsonPath("$.content[?(@.id == '%s')].goalCount", withDocs)
                        .value(Matchers.contains(0)))
                .andExpect(jsonPath("$.content[?(@.id == '%s')].documentCount", empty)
                        .value(Matchers.contains(0)))
                .andExpect(jsonPath("$.content[?(@.id == '%s')].goalCount", empty)
                        .value(Matchers.contains(0)));
    }

    @Test
    void getCourseReturnsNameAndCounts() throws Exception {
        UUID course = TestTenant.course("Single Course");
        documentRepository.save(new Document(course, "slides.pdf", "application/pdf", "slides"));
        GoalFixtures.goal(course, "Apply TDD.").kind(GoalKind.EXPLICIT).create();
        GoalFixtures.goal(course, "Understand testing.").kind(GoalKind.EXPLICIT).create();

        mockMvc.perform(get("/learninggoalhub/api/courses/{id}", course))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(course.toString()))
                .andExpect(jsonPath("$.name").value("Single Course"))
                .andExpect(jsonPath("$.figuresEnabled").value(false))
                .andExpect(jsonPath("$.documentCount").value(1))
                .andExpect(jsonPath("$.goalCount").value(2))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void countsTopicsAndSkillsFromTheTopicTree() throws Exception {
        UUID course = TestTenant.course("Tree Course");
        PlacedGoal topicA = goal(course, "Testing", GoalOrigin.TERMINAL, null);
        PlacedGoal topicB = goal(course, "Design", GoalOrigin.TERMINAL, null);
        // A skill: a topic child whose own children include a SKILL-role goal.
        PlacedGoal skill = goal(course, "Write unit tests", GoalOrigin.SYNTHESIZED, null);
        contributes(skill, topicA);
        contributes(goal(course, "Apply mocking", GoalOrigin.EXTRACTED, GoalRole.SKILL), skill);
        // Also a skill: hand-added under a topic with the SKILL role, still childless.
        PlacedGoal manual = GoalFixtures.goal(course, "Review a design").role(GoalRole.SKILL)
                .provenance(GoalCreationProvenance.USER_CREATED).create();
        contributes(manual, topicB);
        // Not a skill: a topic child with only knowledge beneath it.
        PlacedGoal loose = goal(course, "Explain coupling", GoalOrigin.EXTRACTED, GoalRole.SKILL);
        contributes(loose, topicB);
        contributes(goal(course, "Define coupling", GoalOrigin.EXTRACTED, GoalRole.KNOWLEDGE), loose);

        mockMvc.perform(get("/learninggoalhub/api/courses/{id}", course))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topicCount").value(2))
                .andExpect(jsonPath("$.skillCount").value(2));
        mockMvc.perform(get("/learninggoalhub/api/courses").param("size", "200"))
                .andExpect(jsonPath("$.content[?(@.id == '%s')].topicCount", course)
                        .value(Matchers.contains(2)))
                .andExpect(jsonPath("$.content[?(@.id == '%s')].skillCount", course)
                        .value(Matchers.contains(2)));
    }

    private PlacedGoal goal(UUID course, String text, GoalOrigin origin, GoalRole role) {
        return GoalFixtures.goal(course, text).origin(origin).role(role).create();
    }

    private void contributes(PlacedGoal child, PlacedGoal parent) {
        GoalFixtures.contributes(child, parent);
    }

    @Test
    void getCourseReturns404WhenMissing() throws Exception {
        mockMvc.perform(get("/learninggoalhub/api/courses/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void updatesAndExposesOutputLanguage() throws Exception {
        UUID course = TestTenant.course("Language Course");

        mockMvc.perform(patch("/learninggoalhub/api/courses/{id}", course)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outputLanguage\":\"de\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outputLanguage").value("de"));

        mockMvc.perform(get("/learninggoalhub/api/courses/{id}", course))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outputLanguage").value("de"));

        mockMvc.perform(patch("/learninggoalhub/api/courses/{id}", course)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outputLanguage\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outputLanguage").value(Matchers.nullValue()));
    }

    @Test
    void leavesTheOutputLanguageAloneWhenOmitted() throws Exception {
        UUID course = TestTenant.course("Language Course");
        MaterialSettings settings = settingsService.settings(course);
        settings.setOutputLanguage("de");
        settingsRepository.saveAndFlush(settings);

        // Like the document PATCH: an omitted field is left alone, an explicit null clears it.
        mockMvc.perform(patch("/learninggoalhub/api/courses/{id}", course)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.outputLanguage").value("de"));
    }

    @Test
    void createsPatchesAndReadsFiguresSetting() throws Exception {
        mockMvc.perform(post("/learninggoalhub/api/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Figures Course\",\"figuresEnabled\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.figuresEnabled").value(true));

        UUID course = TestTenant.course("Patch Figures Course");

        mockMvc.perform(patch("/learninggoalhub/api/courses/{id}", course)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"figuresEnabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.figuresEnabled").value(true));

        mockMvc.perform(get("/learninggoalhub/api/courses/{id}", course))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.figuresEnabled").value(true));

        mockMvc.perform(patch("/learninggoalhub/api/courses/{id}", course)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"figuresEnabled\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.figuresEnabled").value(true));

        mockMvc.perform(patch("/learninggoalhub/api/courses/{id}", course)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"figuresEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.figuresEnabled").value(false));
    }

    @Test
    void renamesCourseAndLeavesTheNameAloneWhenOmitted() throws Exception {
        UUID course = TestTenant.course("Old Name");

        mockMvc.perform(patch("/learninggoalhub/api/courses/{id}", course)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  New Name  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Name"));

        mockMvc.perform(patch("/learninggoalhub/api/courses/{id}", course)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"figuresEnabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Name"));
    }

    @Test
    void rejectsBlankCourseName() throws Exception {
        UUID course = TestTenant.course("Keeps Its Name");

        mockMvc.perform(patch("/learninggoalhub/api/courses/{id}", course)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("name")));

        mockMvc.perform(get("/learninggoalhub/api/courses/{id}", course))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Keeps Its Name"));
    }

    @Test
    void rejectsACourseNameLongerThanTheColumn() throws Exception {
        String tooLong = "x".repeat(256);
        mockMvc.perform(post("/learninggoalhub/api/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("name")));

        UUID course = TestTenant.course("Keeps Its Name");
        mockMvc.perform(patch("/learninggoalhub/api/courses/{id}", course)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("name")));
    }

    @Test
    void rejectsUnknownOutputLanguage() throws Exception {
        UUID course = TestTenant.course("Language Course");

        mockMvc.perform(patch("/learninggoalhub/api/courses/{id}", course)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outputLanguage\":\"fr\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("outputLanguage")));
    }

    @Test
    void deleteRemovesCourseAndCascadesToDocumentsAndGoals() throws Exception {
        UUID course = TestTenant.course("Disposable Course");
        Document document =
                documentRepository.save(new Document(course, "slides.pdf", "application/pdf", "slides"));
        PlacedGoal goal = GoalFixtures.goal(course, "Apply TDD.").kind(GoalKind.EXPLICIT).create();
        settingsService.settings(course);

        mockMvc.perform(delete("/learninggoalhub/api/courses/{id}", course))
                .andExpect(status().isNoContent());

        assertThatThrownBy(() -> courseApi.get(course)).isInstanceOf(CourseNotFoundException.class);
        assertThat(documentRepository.existsById(document.getId())).isFalse();
        assertThat(settingsRepository.existsById(course)).isFalse();
        assertThat(placementRepository.existsById(goal.id())).isFalse();
        assertThatThrownBy(() -> goalApi.goal(goal.id())).isInstanceOf(GoalNotFoundException.class);
        mockMvc.perform(get("/learninggoalhub/api/courses/{id}", course))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturns404WhenMissing() throws Exception {
        mockMvc.perform(delete("/learninggoalhub/api/courses/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidCreateReturnsStructuredBadRequest() throws Exception {
        mockMvc.perform(post("/learninggoalhub/api/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("name")));
    }

    @Test
    void createPersistsOutputLanguage() throws Exception {
        // The override has to take at create time: the extraction runs immediately after the
        // materials upload that follows this call, so there is no later moment to set it.
        mockMvc.perform(post("/learninggoalhub/api/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"German Course\",\"outputLanguage\":\"de\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outputLanguage").value("de"))
                .andExpect(jsonPath("$.figuresEnabled").value(false));
    }

    @Test
    void createRejectsUnknownOutputLanguage() throws Exception {
        mockMvc.perform(post("/learninggoalhub/api/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Course\",\"outputLanguage\":\"fr\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("outputLanguage")));
    }

    @Test
    void sortsNewestFirst() throws Exception {
        // The three most recently created courses must occupy the first three positions
        // (default sort createdAt DESC), regardless of older courses left by other tests.
        UUID older = TestTenant.course("Older");
        UUID newer = TestTenant.course("Newer");
        UUID newest = TestTenant.course("Newest");

        String body = mockMvc.perform(get("/learninggoalhub/api/courses").param("size", "200"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<UUID> ids = new ArrayList<>();
        objectMapper.readTree(body).get("content").forEach(node -> ids.add(UUID.fromString(node.get("id").asText())));

        assertThat(ids.subList(0, 3))
                .containsExactlyInAnyOrder(newest, newer, older);
    }

    @Test
    void createRenameAndDeleteOverHttpUseUuidIds() throws Exception {
        String created = mockMvc.perform(post("/learninggoalhub/api/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Lifecycle Course\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Lifecycle Course"))
                .andExpect(jsonPath("$.personal").value(false))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(created).get("id").asText();
        assertThat(UUID.fromString(id)).isNotNull();

        mockMvc.perform(get("/learninggoalhub/api/courses").param("size", "200"))
                .andExpect(jsonPath("$.content[?(@.id == '%s')].name", id).value(Matchers.contains("Lifecycle Course")))
                .andExpect(jsonPath("$.content[?(@.id == '%s')].personal", id).value(Matchers.contains(false)));

        mockMvc.perform(patch("/learninggoalhub/api/courses/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Lifecycle Renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Lifecycle Renamed"));
        assertThat(courseApi.get(UUID.fromString(id)).title()).isEqualTo("Lifecycle Renamed");

        mockMvc.perform(delete("/learninggoalhub/api/courses/{id}", id)).andExpect(status().isNoContent());
        mockMvc.perform(get("/learninggoalhub/api/courses/{id}", id)).andExpect(status().isNotFound());
        mockMvc.perform(get("/learninggoalhub/api/courses").param("size", "200"))
                .andExpect(jsonPath("$.content[?(@.id == '%s')]", id).isEmpty());
    }

    @Test
    void aCourseIdThatIsNotAUuidIsABadRequest() throws Exception {
        mockMvc.perform(get("/learninggoalhub/api/courses/{id}", "42")).andExpect(status().isBadRequest());
    }

    @Test
    void thePersonalCourseIsListedMarkedPersonalAndCannotBeDeleted() throws Exception {
        UUID personal = courseApi.personalCourse().id();

        mockMvc.perform(get("/learninggoalhub/api/courses").param("size", "200"))
                .andExpect(jsonPath("$.content[?(@.id == '%s')].personal", personal.toString())
                        .value(Matchers.contains(true)));
        mockMvc.perform(get("/learninggoalhub/api/courses/{id}", personal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personal").value(true));

        mockMvc.perform(delete("/learninggoalhub/api/courses/{id}", personal)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/learninggoalhub/api/courses/{id}", personal)).andExpect(status().isOk());
    }

    @Test
    void materialSettingsAreCreatedLazilyWithTheDefaults() throws Exception {
        // A course made in core (or by another module) has no settings row yet.
        UUID course = TestTenant.course("Core Only Course");
        assertThat(settingsRepository.existsById(course)).isFalse();

        // Reading the course through the materials endpoint does not need the row ...
        mockMvc.perform(get("/learninggoalhub/api/courses/{id}", course))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outputLanguage").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.figuresEnabled").value(false));

        // ... the first use creates it with the defaults, and asking again keeps it.
        MaterialSettings settings = settingsService.settings(course);
        assertThat(settings.getCourseId()).isEqualTo(course);
        assertThat(settings.getOutputLanguage()).isNull();
        assertThat(settings.isFiguresEnabled()).isFalse();
        assertThat(settings.getSkillsReviewedAt()).isNull();
        assertThat(settingsRepository.existsById(course)).isTrue();
        assertThat(settingsService.settings(course).getCourseId()).isEqualTo(course);
        assertThat(settingsService.context(course).name()).isEqualTo("Core Only Course");
        assertThat(settingsService.lock(course).id()).isEqualTo(course);
        assertThat(settingsRepository.findByCourseIdIn(List.of(course))).hasSize(1);
    }

    @Test
    void settingsOfACourseTheUserCannotReadAreNotCreated() {
        UUID foreign = TestTenant.courseAs("someone-else.test", "carol", "Not Yours");

        assertThatThrownBy(() -> settingsService.settings(foreign)).isInstanceOf(CourseNotFoundException.class);
        assertThatThrownBy(() -> settingsService.lock(foreign)).isInstanceOf(CourseNotFoundException.class);
    }
}
