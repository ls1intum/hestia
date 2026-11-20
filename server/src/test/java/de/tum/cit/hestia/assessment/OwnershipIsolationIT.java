package de.tum.cit.hestia.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import de.tum.cit.hestia.assessment.examination.Examination;
import de.tum.cit.hestia.assessment.examination.ExaminationRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Who sees which exam. An exam follows the roles in its course (viewer reads, editor writes); an exam
 * in a user's personal course is that user's alone; another organisation sees nothing; and nothing
 * answers without a login. Every endpoint family that reaches an exam or its children is covered.
 */
@AutoConfigureMockMvc
class OwnershipIsolationIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ExaminationRepository exams;

    private UUID privateExam;
    private UUID courseExam;
    private UUID coreCourse;

    @BeforeEach
    void seed() throws Exception {
        coreCourse = runAs("alice", () -> courseApi.create("Algorithms").id());
        userId("bob");
        userId("viewer");
        userId("editor");
        member("viewer", "VIEWER");
        member("editor", "EDITOR");

        privateExam = createExam("private", null);
        courseExam = createExam("shared", coreCourse);
    }

    private void member(String user, String role) throws Exception {
        mvc.perform(put("/core/api/courses/" + coreCourse + "/members").with(as("alice"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"externalId\":\"" + user + "\",\"role\":\"" + role + "\"}"))
            .andExpect(status().isOk());
    }

    private UUID createExam(String title, UUID course) throws Exception {
        String body = "{\"title\":\"" + title + "\",\"source\":\"manual\""
            + (course == null ? "" : ",\"course_id\":\"" + course + "\"") + "}";
        String json = mvc.perform(post("/examlense/api/exams").with(as("alice"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    private static MediaType json() {
        return MediaType.APPLICATION_JSON;
    }

    /** One request per endpoint family that reaches an exam; {@code write} marks changing calls. */
    private record Call(String name, boolean write, MockHttpServletRequestBuilder request) {}

    private List<Call> callsOn(UUID exam) {
        String base = "/examlense/api";
        MockMultipartFile pdf = new MockMultipartFile("file", "exam.pdf", "application/pdf", "%PDF-1.4 x".getBytes());
        return List.of(
            new Call("get exam", false, get(base + "/exams/" + exam)),
            new Call("tasks", false, get(base + "/exams/" + exam + "/tasks")),
            new Call("sections", false, get(base + "/exams/" + exam + "/sections")),
            new Call("blocks", false, get(base + "/exams/" + exam + "/blocks")),
            new Call("answers", false, get(base + "/exams/" + exam + "/answers")),
            new Call("grades", false, get(base + "/exams/" + exam + "/grades")),
            new Call("learning goals", false, get(base + "/exams/" + exam + "/learning-goals")),
            new Call("events", false, get(base + "/exams/" + exam + "/events")),
            new Call("patch exam", true, patch(base + "/exams/" + exam).contentType(json()).content("{\"title\":\"x\"}")),
            new Call("delete exam", true, delete(base + "/exams/" + exam)),
            new Call("cancel", true, post(base + "/exams/" + exam + "/cancel")),
            new Call("move to course", true, put(base + "/exams/" + exam + "/course").contentType(json())
                .content("{\"courseId\":\"" + UUID.randomUUID() + "\"}")),
            new Call("duplicate", true, post(base + "/exams/" + exam + "/duplicate")),
            new Call("upload pdf", true, multipart(base + "/exams/" + exam + "/pdf").file(pdf)),
            new Call("create task", true, post(base + "/tasks").contentType(json())
                .content("{\"exam_id\":\"" + exam + "\",\"position\":1,\"type\":\"text\",\"prompt\":\"q\"}")),
            new Call("create section", true, post(base + "/sections").contentType(json())
                .content("{\"exam_id\":\"" + exam + "\",\"position\":1,\"name\":\"s\"}")),
            new Call("create block", true, post(base + "/blocks").contentType(json())
                .content("{\"exam_id\":\"" + exam + "\",\"position\":1,\"content\":\"c\"}")),
            new Call("delete tasks", true, delete(base + "/exams/" + exam + "/tasks?section_id=" + UUID.randomUUID())),
            new Call("parse", true, post(base + "/parse-exam-pdf").contentType(json())
                .content("{\"exam_id\":\"" + exam + "\",\"storage_path\":\"x\"}")),
            new Call("solve exam", true, post(base + "/solve-exam").contentType(json())
                .content("{\"exam_id\":\"" + exam + "\"}")),
            new Call("solve section", true, post(base + "/solve-section").contentType(json())
                .content("{\"exam_id\":\"" + exam + "\"}")));
    }

    private int statusOf(Call call, org.springframework.test.web.servlet.request.RequestPostProcessor login)
            throws Exception {
        return mvc.perform(call.request().with(login)).andReturn().getResponse().getStatus();
    }

    // (a) no login -> 401

    @Test
    void withoutLoginEveryEndpointAnswers401() throws Exception {
        for (Call c : callsOn(courseExam)) {
            mvc.perform(c.request().with(csrf())).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/examlense/api/exams")).andExpect(status().isUnauthorized());
        mvc.perform(get("/examlense/api/exams/events")).andExpect(status().isUnauthorized());
        mvc.perform(get("/examlense/api/lgh/courses")).andExpect(status().isUnauthorized());
        mvc.perform(post("/examlense/api/lgh/courses").with(csrf()).contentType(json()).content("{\"name\":\"x\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/examlense/api/me")).andExpect(status().isUnauthorized());
    }

    // creator

    @Test
    void theCreatorReadsAndChangesTheirExamInThePersonalCourse() throws Exception {
        mvc.perform(get("/examlense/api/exams/" + privateExam).with(as("alice")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.created_by").value(userId("alice").toString()))
            .andExpect(jsonPath("$.course_id").value(courseOf("alice").toString()));
        mvc.perform(patch("/examlense/api/exams/" + privateExam).with(as("alice"))
                .contentType(json()).content("{\"title\":\"renamed\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void anExamCreatedWithACourseIdSitsInThatCourse() throws Exception {
        mvc.perform(get("/examlense/api/exams/" + courseExam).with(as("alice")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.course_id").value(coreCourse.toString()));
    }

    @Test
    void anExamCannotBePutIntoACourseTheCallerDoesNotBelongTo() throws Exception {
        String body = "{\"title\":\"x\",\"source\":\"manual\",\"course_id\":\"" + coreCourse + "\"}";
        mvc.perform(post("/examlense/api/exams").with(as("bob"))
                .contentType(json()).content(body))
            .andExpect(status().isNotFound());
        // A viewer is a member, but may not put content into the course.
        mvc.perform(post("/examlense/api/exams").with(as("viewer"))
                .contentType(json()).content(body))
            .andExpect(status().isForbidden());
    }

    // (b) another user / another organisation sees nothing

    @Test
    void anotherUserOfTheSameOrganisationSeesNothingOfAnExamInAPersonalCourse() throws Exception {
        for (Call c : callsOn(privateExam)) {
            assertThat(statusOf(c, as("bob"))).as(c.name()).isEqualTo(404);
        }
        mvc.perform(get("/examlense/api/exams").with(as("bob")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == '" + privateExam + "')]").doesNotExist());
    }

    @Test
    void aNonMemberSeesNothingOfACourseExam() throws Exception {
        for (Call c : callsOn(courseExam)) {
            assertThat(statusOf(c, as("bob"))).as(c.name()).isEqualTo(404);
        }
        mvc.perform(get("/examlense/api/exams").with(as("bob")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void anotherOrganisationSeesNothingEvenWithTheSameUserName() throws Exception {
        String other = "other-" + uni;
        for (UUID exam : List.of(privateExam, courseExam)) {
            for (Call c : callsOn(exam)) {
                assertThat(statusOf(c, asIn(other, "alice"))).as(c.name()).isEqualTo(404);
            }
        }
        mvc.perform(get("/examlense/api/exams").with(asIn(other, "alice")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void rowLevelSecurityHidesTheRowsFromAnotherOrganisationEvenForTheRepository() {
        UUID otherOrg = identities.login("other-" + uni, "alice", "alice").orgId();
        de.tum.cit.hestia.core.OrgContext.set(otherOrg);
        try {
            assertThat(exams.findById(privateExam)).isEmpty();
            assertThat(exams.findAll()).isEmpty();
        } finally {
            de.tum.cit.hestia.core.OrgContext.set(orgId());
        }
        assertThat(exams.findById(privateExam)).isPresent();
    }

    @Test
    void listShowsPersonalCourseExamsToTheirOwnerAndCourseExamsToMembers() throws Exception {
        mvc.perform(get("/examlense/api/exams").with(as("alice")))
            .andExpect(jsonPath("$[?(@.id == '" + privateExam + "')]").exists())
            .andExpect(jsonPath("$[?(@.id == '" + courseExam + "')]").exists());
        mvc.perform(get("/examlense/api/exams").with(as("viewer")))
            .andExpect(jsonPath("$[?(@.id == '" + privateExam + "')]").doesNotExist())
            .andExpect(jsonPath("$[?(@.id == '" + courseExam + "')]").exists());
    }

    // (c) a viewer cannot write

    @Test
    void aViewerCanReadButNoWriteGetsThrough() throws Exception {
        for (Call c : callsOn(courseExam)) {
            if (c.name().equals("events")) continue; // a long-lived stream, covered below
            int status = statusOf(c, as("viewer"));
            if (c.write()) assertThat(status).as(c.name()).isEqualTo(403);
            else assertThat(status).as(c.name()).isBetween(200, 299);
        }
        mvc.perform(get("/examlense/api/exams/" + courseExam + "/events").with(as("viewer")))
            .andExpect(status().isOk());
        Examination unchanged = exams.findById(courseExam).orElseThrow();
        assertThat(unchanged.getTitle()).isEqualTo("shared");
    }

    @Test
    void anEditorOfTheCourseWrites() throws Exception {
        mvc.perform(patch("/examlense/api/exams/" + courseExam).with(as("editor"))
                .contentType(json()).content("{\"title\":\"edited\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("edited"));
        mvc.perform(post("/examlense/api/tasks").with(as("editor")).contentType(json())
                .content("{\"exam_id\":\"" + courseExam + "\",\"position\":1,\"type\":\"text\",\"prompt\":\"q\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void childRowsFollowTheirExamsCourse() throws Exception {
        String task = mvc.perform(post("/examlense/api/tasks").with(as("alice")).contentType(json())
                .content("{\"exam_id\":\"" + courseExam + "\",\"position\":1,\"type\":\"text\",\"prompt\":\"q\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String taskId = JsonPath.read(task, "$.id");

        mvc.perform(patch("/examlense/api/tasks/" + taskId).with(as("viewer")).contentType(json())
                .content("{\"prompt\":\"changed\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/examlense/api/tasks/" + taskId).with(as("viewer")))
            .andExpect(status().isForbidden());
        mvc.perform(put("/examlense/api/task-grades").with(as("viewer")).contentType(json())
                .content("{\"task_id\":\"" + taskId + "\",\"exam_id\":\"" + courseExam + "\",\"score\":1}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/examlense/api/solve-task").with(as("viewer")).contentType(json())
                .content("{\"task_id\":\"" + taskId + "\"}"))
            .andExpect(status().isForbidden());

        mvc.perform(patch("/examlense/api/tasks/" + taskId).with(as("bob")).contentType(json())
                .content("{\"prompt\":\"changed\"}"))
            .andExpect(status().isNotFound());
        mvc.perform(delete("/examlense/api/tasks/" + taskId).with(asIn("other-" + uni, "alice")))
            .andExpect(status().isNotFound());

        mvc.perform(delete("/examlense/api/tasks/" + taskId).with(as("editor")))
            .andExpect(status().isNoContent());
    }

    @Test
    void figuresOfACourseExamNeedTheCourseRole() throws Exception {
        String section = mvc.perform(post("/examlense/api/sections").with(as("alice")).contentType(json())
                .content("{\"exam_id\":\"" + courseExam + "\",\"position\":1,\"name\":\"s\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String block = mvc.perform(post("/examlense/api/blocks").with(as("alice")).contentType(json())
                .content("{\"exam_id\":\"" + courseExam + "\",\"section_id\":\"" + JsonPath.read(section, "$.id")
                    + "\",\"position\":1,\"kind\":\"figure\",\"content\":\"c\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String blockId = JsonPath.read(block, "$.id");
        MockMultipartFile png = new MockMultipartFile("file", "f.png", "image/png", new byte[] {1, 2, 3});

        mvc.perform(multipart("/examlense/api/blocks/" + blockId + "/figures").file(png).with(as("viewer")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/examlense/api/blocks/" + blockId + "/figures").with(as("viewer")))
            .andExpect(status().isOk());
        mvc.perform(get("/examlense/api/blocks/" + blockId + "/figures").with(as("bob")))
            .andExpect(status().isNotFound());

        String figure = mvc.perform(multipart("/examlense/api/blocks/" + blockId + "/figures").file(png).with(as("editor")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String figureId = JsonPath.read(figure, "$.id");
        mvc.perform(get("/examlense/api/figures/" + figureId + "/signed-url").with(as("viewer")))
            .andExpect(status().isOk());
        mvc.perform(get("/examlense/api/figures/" + figureId + "/signed-url").with(as("bob")))
            .andExpect(status().isNotFound());
        mvc.perform(delete("/examlense/api/figures/" + figureId).with(as("viewer")))
            .andExpect(status().isForbidden());
    }

    @Test
    void unknownExaminationIsNotFound() throws Exception {
        mvc.perform(get("/examlense/api/exams/" + UUID.randomUUID()).with(as("bob")))
            .andExpect(status().isNotFound());
    }
}
