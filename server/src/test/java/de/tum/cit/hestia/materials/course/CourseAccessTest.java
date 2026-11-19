package de.tum.cit.hestia.materials.course;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.hestia.core.OrgContext;
import de.tum.cit.hestia.core.TestLogin;
import de.tum.cit.hestia.core.internal.identity.IdentityService;
import de.tum.cit.hestia.materials.MaterialsTestBase;
import de.tum.cit.hestia.materials.MaterialsTestConfiguration;
import de.tum.cit.hestia.materials.TestTenant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Login, organisation and course roles of the materials endpoints. */
@Import(MaterialsTestConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CourseAccessTest extends MaterialsTestBase {

    private static final String API = "/learninggoalhub/api";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private IdentityService identities;

    @Autowired
    private CourseRepository courseRepository;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String uniA = "uni-a-" + run + ".test";
    private final String uniB = "uni-b-" + run + ".test";

    private RequestPostProcessor as(String org, String user) {
        return TestLogin.as(identities, org, user);
    }

    /** A course of {@code alice} (owner) at university A. */
    private Course courseOfAlice() {
        UUID org = TestLogin.login(identities, uniA, "alice").orgId();
        try {
            return OrgContext.call(org, () -> courseRepository.save(TestTenant.courseAs(uniA, "alice", "Algorithms")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void addMember(Course course, String user, String role) throws Exception {
        TestLogin.login(identities, uniA, user);
        mvc.perform(put("/core/api/courses/" + course.getCoreCourseId() + "/members").with(as(uniA, "alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalId\":\"" + user + "\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void withoutLoginEveryEndpointAnswers401() throws Exception {
        mvc.perform(get(API + "/courses").with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(post(API + "/courses").with(anonymous()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\"}")).andExpect(status().isUnauthorized());
        mvc.perform(get(API + "/courses/1").with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(get(API + "/courses/1/documents").with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(get(API + "/courses/1/learning-goals").with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(post(API + "/courses/1/extract").with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(delete(API + "/courses/1").with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(get(API + "/extractions/current").with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(get(API + "/intake/terms").with(anonymous())).andExpect(status().isUnauthorized());
    }

    @Test
    void anotherOrganisationSeesNothing() throws Exception {
        Course course = courseOfAlice();
        long id = course.getId();
        TestLogin.login(identities, uniB, "alice");

        mvc.perform(get(API + "/courses?size=200").with(as(uniB, "alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
        mvc.perform(get(API + "/courses/" + id).with(as(uniB, "alice"))).andExpect(status().isNotFound());
        mvc.perform(get(API + "/courses/" + id + "/documents").with(as(uniB, "alice")))
                .andExpect(status().isNotFound());
        mvc.perform(get(API + "/courses/" + id + "/learning-goals").with(as(uniB, "alice")))
                .andExpect(status().isNotFound());
        mvc.perform(post(API + "/courses/" + id + "/extract").with(as(uniB, "alice")))
                .andExpect(status().isNotFound());
        mvc.perform(patch(API + "/courses/" + id).with(as(uniB, "alice")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"mine now\"}")).andExpect(status().isNotFound());
        mvc.perform(delete(API + "/courses/" + id).with(as(uniB, "alice"))).andExpect(status().isNotFound());
        // The owner still has it.
        mvc.perform(get(API + "/courses/" + id).with(as(uniA, "alice"))).andExpect(status().isOk());
    }

    @Test
    void aUserWhoIsNoMemberDoesNotSeeTheCourse() throws Exception {
        Course course = courseOfAlice();
        TestLogin.login(identities, uniA, "bob");

        mvc.perform(get(API + "/courses?size=200").with(as(uniA, "bob")))
                .andExpect(jsonPath("$.content.length()").value(0));
        mvc.perform(get(API + "/courses/" + course.getId()).with(as(uniA, "bob")))
                .andExpect(status().isNotFound());
    }

    @Test
    void aViewerReadsButChangesNothing() throws Exception {
        Course course = courseOfAlice();
        addMember(course, "vera", "VIEWER");
        String base = API + "/courses/" + course.getId();

        mvc.perform(get(API + "/courses?size=200").with(as(uniA, "vera")))
                .andExpect(jsonPath("$.content.length()").value(1));
        mvc.perform(get(base).with(as(uniA, "vera"))).andExpect(status().isOk());
        mvc.perform(get(base + "/documents").with(as(uniA, "vera"))).andExpect(status().isOk());
        mvc.perform(get(base + "/learning-goals").with(as(uniA, "vera"))).andExpect(status().isOk());

        mvc.perform(patch(base).with(as(uniA, "vera")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"renamed\"}")).andExpect(status().isForbidden());
        mvc.perform(post(base + "/extract").with(as(uniA, "vera"))).andExpect(status().isForbidden());
        mvc.perform(post(base + "/competency-tree").with(as(uniA, "vera"))).andExpect(status().isForbidden());
        mvc.perform(post(base + "/learning-goals/terminal").with(as(uniA, "vera"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(delete(base + "/learning-goals/1").with(as(uniA, "vera"))).andExpect(status().isForbidden());
        mvc.perform(delete(base).with(as(uniA, "vera"))).andExpect(status().isForbidden());
    }

    @Test
    void anEditorChangesContentButOnlyTheOwnerDeletesTheCourse() throws Exception {
        Course course = courseOfAlice();
        addMember(course, "eddie", "EDITOR");
        String base = API + "/courses/" + course.getId();

        mvc.perform(patch(base).with(as(uniA, "eddie")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Renamed by editor\"}")).andExpect(status().isOk());
        mvc.perform(delete(base).with(as(uniA, "eddie"))).andExpect(status().isForbidden());
        mvc.perform(delete(base).with(as(uniA, "alice"))).andExpect(status().isNoContent());
        mvc.perform(get(base).with(as(uniA, "alice"))).andExpect(status().isNotFound());
    }

    @Test
    void aCreatedCourseBelongsToItsCreator() throws Exception {
        mvc.perform(post(API + "/courses").with(as(uniA, "alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Created over HTTP\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get(API + "/courses?size=200").with(as(uniA, "alice")))
                .andExpect(jsonPath("$.content[?(@.name == 'Created over HTTP')]").isNotEmpty());
        mvc.perform(get(API + "/courses?size=200").with(as(uniB, "alice")))
                .andExpect(jsonPath("$.content[?(@.name == 'Created over HTTP')]").isEmpty());
    }
}
