package de.tum.cit.hestia.learninggoalhub.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.tum.cit.hestia.learninggoalhub.TestcontainersConfiguration;
import de.tum.cit.hestia.learninggoalhub.document.Document;
import de.tum.cit.hestia.learninggoalhub.document.DocumentRepository;
import de.tum.cit.hestia.learninggoalhub.goal.GoalKind;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoal;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoalRepository;
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
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CourseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private LearningGoalRepository goalRepository;

    @Test
    void exposesDocumentAndGoalCountsPerCourse() throws Exception {
        Course empty = courseRepository.save(new Course("Empty Course"));

        Course withDocs = courseRepository.save(new Course("Course With Documents"));
        documentRepository.save(new Document(withDocs, "lecture.pdf", "application/pdf", "lecture"));
        documentRepository.save(new Document(withDocs, "exercise.pdf", "application/pdf", "exercise"));

        Course analyzed = courseRepository.save(new Course("Analyzed Course"));
        documentRepository.save(new Document(analyzed, "slides.pdf", "application/pdf", "slides"));
        goalRepository.save(new LearningGoal(analyzed, "Apply TDD.", GoalKind.EXPLICIT));
        goalRepository.save(new LearningGoal(analyzed, "Value refactoring.", GoalKind.IMPLICIT));
        goalRepository.save(new LearningGoal(analyzed, "Understand testing.", GoalKind.EXPLICIT));

        mockMvc.perform(get("/api/courses").param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == %d)].name", analyzed.getId())
                        .value(Matchers.contains("Analyzed Course")))
                .andExpect(jsonPath("$.content[?(@.id == %d)].documentCount", analyzed.getId())
                        .value(Matchers.contains(1)))
                .andExpect(jsonPath("$.content[?(@.id == %d)].goalCount", analyzed.getId())
                        .value(Matchers.contains(3)))
                .andExpect(jsonPath("$.content[?(@.id == %d)].createdAt", analyzed.getId())
                        .value(Matchers.hasSize(1)))
                .andExpect(jsonPath("$.content[?(@.id == %d)].documentCount", withDocs.getId())
                        .value(Matchers.contains(2)))
                .andExpect(jsonPath("$.content[?(@.id == %d)].goalCount", withDocs.getId())
                        .value(Matchers.contains(0)))
                .andExpect(jsonPath("$.content[?(@.id == %d)].documentCount", empty.getId())
                        .value(Matchers.contains(0)))
                .andExpect(jsonPath("$.content[?(@.id == %d)].goalCount", empty.getId())
                        .value(Matchers.contains(0)));
    }

    @Test
    void getCourseReturnsNameAndCounts() throws Exception {
        Course course = courseRepository.save(new Course("Single Course"));
        documentRepository.save(new Document(course, "slides.pdf", "application/pdf", "slides"));
        goalRepository.save(new LearningGoal(course, "Apply TDD.", GoalKind.EXPLICIT));
        goalRepository.save(new LearningGoal(course, "Understand testing.", GoalKind.EXPLICIT));

        mockMvc.perform(get("/api/courses/{id}", course.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(course.getId()))
                .andExpect(jsonPath("$.name").value("Single Course"))
                .andExpect(jsonPath("$.documentCount").value(1))
                .andExpect(jsonPath("$.goalCount").value(2))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void getCourseReturns404WhenMissing() throws Exception {
        mockMvc.perform(get("/api/courses/{id}", 999_999_999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteRemovesCourseAndCascadesToDocumentsAndGoals() throws Exception {
        Course course = courseRepository.save(new Course("Disposable Course"));
        Document document =
                documentRepository.save(new Document(course, "slides.pdf", "application/pdf", "slides"));
        LearningGoal goal = goalRepository.save(new LearningGoal(course, "Apply TDD.", GoalKind.EXPLICIT));

        mockMvc.perform(delete("/api/courses/{id}", course.getId()))
                .andExpect(status().isNoContent());

        assertThat(courseRepository.existsById(course.getId())).isFalse();
        assertThat(documentRepository.existsById(document.getId())).isFalse();
        assertThat(goalRepository.existsById(goal.getId())).isFalse();
        mockMvc.perform(get("/api/courses/{id}", course.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturns404WhenMissing() throws Exception {
        mockMvc.perform(delete("/api/courses/{id}", 999_999_999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidCreateReturnsStructuredBadRequest() throws Exception {
        mockMvc.perform(post("/api/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("name")));
    }

    @Test
    void sortsNewestFirst() throws Exception {
        // The three most recently created courses must occupy the first three positions
        // (default sort createdAt DESC), regardless of older courses left by other tests.
        Course older = courseRepository.save(new Course("Older"));
        Course newer = courseRepository.save(new Course("Newer"));
        Course newest = courseRepository.save(new Course("Newest"));

        String body = mockMvc.perform(get("/api/courses").param("size", "200"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Long> ids = new ArrayList<>();
        objectMapper.readTree(body).get("content").forEach(node -> ids.add(node.get("id").asLong()));

        assertThat(ids.subList(0, 3))
                .containsExactlyInAnyOrder(newest.getId(), newer.getId(), older.getId());
    }
}
