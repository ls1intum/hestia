package de.tum.cit.hestia.teaching.controller;

import de.tum.cit.hestia.core.spi.GoalDirectory;
import de.tum.cit.hestia.teaching.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The goal import reads the materials module through {@link GoalDirectory}; the client still gets
 * the JSON shape of LearningGoalHub's REST API (see apps/workshopper/client/src/lib/learning-goal-hub.ts).
 */
@org.springframework.test.context.ContextConfiguration(classes = TeachingWebMvcTestConfig.class)
@WebMvcTest(value = LearningGoalHubController.class, excludeAutoConfiguration =
        org.springframework.boot.autoconfigure.security.saml2.Saml2RelyingPartyAutoConfiguration.class)
@Import({SecurityConfig.class, LearningGoalHubController.class})
class LearningGoalHubControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private GoalDirectory goalDirectory;

    @Test
    @WithMockUser
    void coursesComeAsContentWithPageMetadata() throws Exception {
        when(goalDirectory.listCourses(0, 100)).thenReturn(new GoalDirectory.Page<>(
                List.of(new GoalDirectory.Course(7, "Algorithms"), new GoalDirectory.Course(8, "Databases")),
                0, 100, 2, 1));

        mvc.perform(get("/workshopper/api/workshop/lgh/courses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(7))
                .andExpect(jsonPath("$.content[0].name").value("Algorithms"))
                .andExpect(jsonPath("$.page.size").value(100))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.page.totalPages").value(1));
    }

    @Test
    @WithMockUser
    void pageAndSizeAreForwarded() throws Exception {
        when(goalDirectory.listCourses(2, 5)).thenReturn(new GoalDirectory.Page<>(List.of(), 2, 5, 11, 3));

        mvc.perform(get("/workshopper/api/workshop/lgh/courses?page=2&size=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.number").value(2))
                .andExpect(jsonPath("$.page.totalPages").value(3));
    }

    @Test
    @WithMockUser
    void goalsBySessionComeAsAnArrayOfGroups() throws Exception {
        var goal = new GoalDirectory.Goal(1, "Explain recursion", "COMPETENCE", "MAIN", "EXTRACTED", "ACCEPTED",
                "UNDERSTAND", null, 3, new GoalDirectory.Hierarchy("Module A", "Session 1", null),
                List.of(new GoalDirectory.Relationship("PREREQUISITE", 2, "Know functions", 0.8, "AI")));
        when(goalDirectory.goalsBySession(7)).thenReturn(List.of(
                new GoalDirectory.SessionGoals(11L, "SESSION", "Session 1", List.of(goal)),
                new GoalDirectory.SessionGoals(null, null, null, List.of())));

        mvc.perform(get("/workshopper/api/workshop/lgh/courses/7/learning-goals/by-session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].nodeId").value(11))
                .andExpect(jsonPath("$[0].level").value("SESSION"))
                .andExpect(jsonPath("$[0].label").value("Session 1"))
                .andExpect(jsonPath("$[0].goals[0].text").value("Explain recursion"))
                .andExpect(jsonPath("$[0].goals[0].bloomLevel").value("UNDERSTAND"))
                .andExpect(jsonPath("$[0].goals[0].lectureOrder").value(3))
                .andExpect(jsonPath("$[0].goals[0].hierarchy.session").value("Session 1"))
                .andExpect(jsonPath("$[0].goals[0].relationships[0].targetGoalId").value(2))
                .andExpect(jsonPath("$[1].nodeId").doesNotExist());
    }

    @Test
    @WithMockUser
    void unknownCourseIs404() throws Exception {
        when(goalDirectory.goalsBySession(99)).thenThrow(new NoSuchElementException("course 99"));

        mvc.perform(get("/workshopper/api/workshop/lgh/courses/99/learning-goals/by-session"))
                .andExpect(status().isNotFound());
    }

    @Test
    void withoutLoginBothEndpointsAnswer401() throws Exception {
        mvc.perform(get("/workshopper/api/workshop/lgh/courses")).andExpect(status().isUnauthorized());
        mvc.perform(get("/workshopper/api/workshop/lgh/courses/7/learning-goals/by-session"))
                .andExpect(status().isUnauthorized());
    }
}
