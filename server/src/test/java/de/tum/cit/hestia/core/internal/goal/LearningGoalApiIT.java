package de.tum.cit.hestia.core.internal.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.hestia.TestDatabase;
import de.tum.cit.hestia.core.AlignmentApi;
import de.tum.cit.hestia.core.AlignmentLinkInfo;
import de.tum.cit.hestia.core.CourseAccessDeniedException;
import de.tum.cit.hestia.core.CourseApi;
import de.tum.cit.hestia.core.CourseInfo;
import de.tum.cit.hestia.core.CourseNotFoundException;
import de.tum.cit.hestia.core.CourseRole;
import de.tum.cit.hestia.core.DomainModule;
import de.tum.cit.hestia.core.GoalInfo;
import de.tum.cit.hestia.core.GoalNotFoundException;
import de.tum.cit.hestia.core.GoalOrigin;
import de.tum.cit.hestia.core.GoalRelationInfo;
import de.tum.cit.hestia.core.GoalStatus;
import de.tum.cit.hestia.core.LearningGoalApi;
import de.tum.cit.hestia.core.NewGoal;
import de.tum.cit.hestia.core.OrgContext;
import de.tum.cit.hestia.core.TestLogin;
import de.tum.cit.hestia.core.internal.identity.IdentityService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

/**
 * Learning goals, their versions and alignment links in {@code core}, and the personal catch-all
 * course: versioning, soft removal, course roles and isolation between organisations.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LearningGoalApiIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @MockitoBean
    RelyingPartyRegistrationRepository relyingPartyRegistrationRepository;

    @Autowired MockMvc mvc;
    @Autowired IdentityService identities;
    @Autowired CourseApi courses;
    @Autowired LearningGoalApi goals;
    @Autowired AlignmentApi alignment;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String uniA = "uni-a-" + run + ".test";
    private final String uniB = "uni-b-" + run + ".test";

    private <T> T asAlice(java.util.function.Supplier<T> action) {
        return TestLogin.runAs(identities, uniA, "alice", action);
    }

    private UUID courseWithGoal(String text) {
        return asAlice(() -> {
            UUID course = courses.create("Algorithms").id();
            goals.create(course, NewGoal.of(text, GoalOrigin.MANUAL, DomainModule.MATERIALS));
            return course;
        });
    }

    @Test
    void renamingWritesANewVersionAndKeepsTheOriginal() {
        UUID course = courseWithGoal("Explain recursion");
        asAlice(() -> {
            GoalInfo goal = goals.goals(course).getFirst();
            assertThat(goal.version()).isEqualTo(1);
            GoalInfo renamed = goals.update(goal.id(), goal.asChange().withText("Explain recursion and its base case"));
            assertThat(renamed.version()).isEqualTo(2);
            assertThat(renamed.text()).isEqualTo("Explain recursion and its base case");
            assertThat(renamed.originalText()).isEqualTo("Explain recursion");

            // the same content again writes no version, nor does a status change
            assertThat(goals.update(goal.id(), renamed.asChange()).version()).isEqualTo(2);
            assertThat(goals.setStatus(goal.id(), GoalStatus.APPROVED).version()).isEqualTo(2);
            assertThat(goals.history(goal.id())).extracting(v -> v.text())
                    .containsExactly("Explain recursion", "Explain recursion and its base case");
            return null;
        });
    }

    @Test
    void removalIsSoftAndKeepsLinksButDropsRelationships() {
        UUID course = courseWithGoal("Explain recursion");
        asAlice(() -> {
            GoalInfo first = goals.goals(course).getFirst();
            GoalInfo second = goals.create(course, NewGoal.of("Define a base case", GoalOrigin.MANUAL,
                    DomainModule.TEACHING));
            GoalRelationInfo relation = goals.relate(first.id(), second.id(), "PREREQUISITE", 0.9, "LLM");
            assertThat(goals.relate(first.id(), second.id(), "PREREQUISITE", 0.5, "LLM").id()).isEqualTo(relation.id());
            alignment.link(second.id(), DomainModule.TEACHING, "SESSION", "42");

            goals.remove(second.id());

            assertThat(goals.goals(course)).extracting(GoalInfo::id).containsExactly(first.id());
            assertThat(goals.goal(second.id()).removed()).isTrue();
            assertThat(goals.relationships(course)).isEmpty();
            assertThat(alignment.goalsFor(DomainModule.TEACHING, "SESSION", "42"))
                    .extracting(AlignmentLinkInfo::goalId).containsExactly(second.id());
            return null;
        });
    }

    @Test
    void linksAreFoundByTargetAndMovingDropsOnlyTheOldCoursesLinks() {
        UUID courseA = courseWithGoal("Goal of A");
        asAlice(() -> {
            UUID courseB = courses.create("Databases").id();
            GoalInfo goalA = goals.goals(courseA).getFirst();
            GoalInfo goalB = goals.create(courseB, NewGoal.of("Goal of B", GoalOrigin.MANUAL, DomainModule.ASSESSMENT));
            AlignmentLinkInfo link = alignment.link(goalA.id(), DomainModule.ASSESSMENT, "TASK", "t1");
            assertThat(link.courseId()).isEqualTo(courseA);
            assertThat(link.goalVersionSeen()).isEqualTo(1);
            assertThat(alignment.link(goalA.id(), DomainModule.ASSESSMENT, "TASK", "t1").id()).isEqualTo(link.id());
            alignment.link(goalB.id(), DomainModule.ASSESSMENT, "TASK", "t1");
            alignment.link(goalB.id(), DomainModule.ASSESSMENT, "TASK", "t2");

            assertThat(alignment.goalsForTargets(DomainModule.ASSESSMENT, "TASK", List.of("t1", "t2", "t3")))
                    .containsOnlyKeys("t1", "t2");

            alignment.unlinkTargetInCourse(DomainModule.ASSESSMENT, "TASK", "t1", courseA);
            assertThat(alignment.goalsFor(DomainModule.ASSESSMENT, "TASK", "t1"))
                    .extracting(AlignmentLinkInfo::goalId).containsExactly(goalB.id());

            alignment.unlinkTarget(DomainModule.ASSESSMENT, "TASK", "t1");
            assertThat(alignment.goalsFor(DomainModule.ASSESSMENT, "TASK", "t1")).isEmpty();
            assertThat(alignment.linksOfGoal(goalB.id())).extracting(AlignmentLinkInfo::targetId).containsExactly("t2");
            return null;
        });
    }

    @Test
    void viewersReadButCannotWriteAndOtherUsersSeeNothing() throws Exception {
        UUID course = courseWithGoal("Explain recursion");
        UUID goalId = asAlice(() -> goals.goals(course).getFirst().id());
        TestLogin.login(identities, uniA, "bob");
        TestLogin.login(identities, uniA, "carol");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/core/api/courses/" + course + "/members")
                        .with(TestLogin.as(identities, uniA, "alice"))
                        .contentType("application/json")
                        .content("{\"externalId\":\"bob\",\"role\":\"" + CourseRole.VIEWER + "\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        TestLogin.runAs(identities, uniA, "bob", () -> {
            assertThat(goals.goal(goalId).text()).isEqualTo("Explain recursion");
            assertThatThrownBy(() -> goals.update(goalId, goals.goal(goalId).asChange().withText("x")))
                    .isInstanceOf(CourseAccessDeniedException.class);
            assertThatThrownBy(() -> goals.create(course, NewGoal.of("x", GoalOrigin.MANUAL, DomainModule.MATERIALS)))
                    .isInstanceOf(CourseAccessDeniedException.class);
            return null;
        });
        TestLogin.runAs(identities, uniA, "carol", () -> {
            assertThatThrownBy(() -> goals.goal(goalId)).isInstanceOf(GoalNotFoundException.class);
            assertThatThrownBy(() -> goals.goals(course)).isInstanceOf(CourseNotFoundException.class);
            assertThat(goals.goalsById(List.of(goalId))).isEmpty();
            return null;
        });
        TestLogin.runAs(identities, uniB, "alice", () -> {
            assertThatThrownBy(() -> goals.goal(goalId)).isInstanceOf(GoalNotFoundException.class);
            assertThat(goals.goalsById(List.of(goalId))).isEmpty();
            return null;
        });
    }

    @Test
    void thePersonalCourseIsCreatedOnceAndCannotBeDeleted() {
        asAlice(() -> {
            CourseInfo personal = courses.personalCourse();
            assertThat(personal.personal()).isTrue();
            assertThat(personal.role()).isEqualTo(CourseRole.OWNER);
            assertThat(courses.personalCourse().id()).isEqualTo(personal.id());
            assertThat(courses.listMine(0, 50)).extracting(CourseInfo::id).contains(personal.id());
            assertThatThrownBy(() -> courses.delete(personal.id())).isInstanceOf(IllegalArgumentException.class);
            return null;
        });
        UUID bobs = TestLogin.runAs(identities, uniA, "bob", () -> courses.personalCourse().id());
        UUID alices = asAlice(() -> courses.personalCourse().id());
        assertThat(bobs).isNotEqualTo(alices);
    }

    @Test
    void backgroundTasksKeepTheUserAndTheOrganisation() throws Exception {
        UUID course = courseWithGoal("Explain recursion");
        var executor = Executors.newSingleThreadExecutor();
        try {
            List<GoalInfo> found = asAlice(() -> {
                try {
                    return executor.submit(OrgContext.wrap(() -> goals.goals(course))).get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            assertThat(found).extracting(GoalInfo::text).containsExactly("Explain recursion");
        } finally {
            executor.shutdown();
        }
    }
}
