package de.tum.cit.hestia.core.internal.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.hestia.TestDatabase;
import de.tum.cit.hestia.core.AlignmentApi;
import de.tum.cit.hestia.core.AlignmentLinkInfo;
import de.tum.cit.hestia.core.AlignmentLinkNotFoundException;
import de.tum.cit.hestia.core.AlignmentReview;
import de.tum.cit.hestia.core.CourseAccessDeniedException;
import de.tum.cit.hestia.core.CourseApi;
import de.tum.cit.hestia.core.DomainModule;
import de.tum.cit.hestia.core.GoalInfo;
import de.tum.cit.hestia.core.GoalOrigin;
import de.tum.cit.hestia.core.LearningGoalApi;
import de.tum.cit.hestia.core.NewGoal;
import de.tum.cit.hestia.core.TestLogin;
import de.tum.cit.hestia.core.events.CourseDeleted;
import de.tum.cit.hestia.core.events.GoalChanged;
import de.tum.cit.hestia.core.events.GoalRemoved;
import de.tum.cit.hestia.core.internal.identity.IdentityService;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Change propagation: a changed or removed goal publishes an event and marks its alignment links
 * for review in the same transaction; the course's review list shows them until an editor confirms
 * them. Course roles and isolation between organisations apply to the list and to confirming.
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class AlignmentReviewIT {

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
    @Autowired TransactionTemplate transactions;
    @Autowired ApplicationEvents events;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String uniA = "uni-a-" + run + ".test";
    private final String uniB = "uni-b-" + run + ".test";
    private final String session = UUID.randomUUID().toString();
    private final String task = UUID.randomUUID().toString();

    private <T> T asAlice(Supplier<T> action) {
        return TestLogin.runAs(identities, uniA, "alice", action);
    }

    /** A course of alice with one goal linked to a session and an exam task. */
    private record Setup(UUID course, GoalInfo goal) {}

    private Setup linkedGoal() {
        return asAlice(() -> {
            UUID course = courses.create("Algorithms").id();
            GoalInfo goal = goals.create(course, NewGoal.of("Explain recursion", GoalOrigin.MANUAL,
                    DomainModule.MATERIALS));
            alignment.link(goal.id(), DomainModule.TEACHING, "SESSION", session);
            alignment.link(goal.id(), DomainModule.ASSESSMENT, "TASK", task);
            return new Setup(course, goal);
        });
    }

    private void rename(Setup s, String text) {
        asAlice(() -> goals.update(s.goal().id(), s.goal().asChange().withText(text)));
    }

    @Test
    void changingAGoalMarksTheLinkedSessionAndTaskForReview() {
        Setup s = linkedGoal();
        assertThat(asAlice(() -> alignment.openReviews(s.course()))).isEmpty();

        rename(s, "Explain recursion and its base case");

        assertThat(events.stream(GoalChanged.class))
                .containsExactly(new GoalChanged(orgOfAlice(), s.course(), s.goal().id(), 1, 2));
        asAlice(() -> {
            AlignmentLinkInfo sessionLink = alignment.goalsFor(DomainModule.TEACHING, "SESSION", session).getFirst();
            AlignmentLinkInfo taskLink = alignment.goalsFor(DomainModule.ASSESSMENT, "TASK", task).getFirst();
            assertThat(List.of(sessionLink, taskLink)).allSatisfy(l -> {
                assertThat(l.reviewState()).isEqualTo("REVIEW");
                assertThat(l.reviewReason()).isEqualTo("GOAL_CHANGED");
                assertThat(l.goalVersionSeen()).isEqualTo(1);
            });
            List<AlignmentReview> reviews = alignment.openReviews(s.course());
            assertThat(reviews).extracting(AlignmentReview::targetId).containsExactly(session, task);
            assertThat(reviews).allSatisfy(r -> {
                assertThat(r.seenText()).isEqualTo("Explain recursion");
                assertThat(r.currentText()).isEqualTo("Explain recursion and its base case");
                assertThat(r.goalVersion()).isEqualTo(2);
                assertThat(r.goalRemoved()).isFalse();
            });
            return null;
        });
    }

    @Test
    void theSameContentOrAStatusChangeMarksNothing() {
        Setup s = linkedGoal();
        asAlice(() -> goals.update(s.goal().id(), s.goal().asChange()));
        asAlice(() -> goals.setStatus(s.goal().id(), de.tum.cit.hestia.core.GoalStatus.APPROVED));

        assertThat(events.stream(GoalChanged.class)).isEmpty();
        assertThat(asAlice(() -> alignment.openReviews(s.course()))).isEmpty();
    }

    @Test
    void removingAGoalMarksItsLinksAndARemovedGoalStaysTheReason() {
        Setup s = linkedGoal();
        rename(s, "Explain recursion, version 2");
        asAlice(() -> {
            goals.remove(s.goal().id());
            return null;
        });

        assertThat(events.stream(GoalRemoved.class))
                .containsExactly(new GoalRemoved(orgOfAlice(), s.course(), s.goal().id(), 2));
        List<AlignmentReview> reviews = asAlice(() -> alignment.openReviews(s.course()));
        assertThat(reviews).hasSize(2).allSatisfy(r -> {
            assertThat(r.reason()).isEqualTo("GOAL_REMOVED");
            assertThat(r.goalRemoved()).isTrue();
        });

        // a later change of the removed goal does not hide that it was removed
        rename(s, "Explain recursion, version 3");
        assertThat(asAlice(() -> alignment.openReviews(s.course())))
                .extracting(AlignmentReview::reason).containsOnly("GOAL_REMOVED");
    }

    @Test
    void marksRollBackWithTheChangeOfTheGoal() {
        Setup s = linkedGoal();
        assertThatThrownBy(() -> asAlice(() -> transactions.execute(tx -> {
            goals.update(s.goal().id(), s.goal().asChange().withText("Changed, then rolled back"));
            throw new IllegalStateException("abort");
        }))).hasMessage("abort");

        assertThat(asAlice(() -> alignment.openReviews(s.course()))).isEmpty();
        assertThat(asAlice(() -> goals.goal(s.goal().id())).version()).isEqualTo(1);
    }

    @Test
    void confirmingOverHttpRemovesTheMarkAndRemembersTheVersion() throws Exception {
        Setup s = linkedGoal();
        rename(s, "Explain recursion and its base case");
        UUID linkId = asAlice(() -> alignment.openReviews(s.course())).getFirst().linkId();

        mvc.perform(get("/core/api/courses/{c}/reviews", s.course()).with(as(uniA, "alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].reason").value("GOAL_CHANGED"))
                .andExpect(jsonPath("$[0].seenText").value("Explain recursion"))
                .andExpect(jsonPath("$[0].currentText").value("Explain recursion and its base case"));

        mvc.perform(confirm(s.course(), linkId, 2).with(as(uniA, "alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewState").value("OK"))
                .andExpect(jsonPath("$.goalVersionSeen").value(2));

        mvc.perform(get("/core/api/courses/{c}/reviews", s.course()).with(as(uniA, "alice")))
                .andExpect(jsonPath("$.length()").value(1));
        assertThat(asAlice(() -> alignment.goalsFor(DomainModule.TEACHING, "SESSION", session)).getFirst())
                .satisfies(l -> {
                    assertThat(l.reviewState()).isEqualTo("OK");
                    assertThat(l.reviewReason()).isNull();
                });

        // a link of another course cannot be confirmed through this course's path
        UUID otherCourse = asAlice(() -> courses.create("Databases").id());
        mvc.perform(confirm(otherCourse, asAlice(() -> alignment.openReviews(s.course())).getFirst().linkId(), 2)
                        .with(as(uniA, "alice")))
                .andExpect(status().isNotFound());
    }

    @Test
    void confirmingAVersionTheTeacherDidNotSeeIsRefusedAndTheMarkStays() throws Exception {
        Setup s = linkedGoal();
        rename(s, "Explain recursion and its base case");
        AlignmentReview shown = asAlice(() -> alignment.openReviews(s.course())).getFirst();
        assertThat(shown.goalVersion()).isEqualTo(2);

        // another editor changes the goal again while the teacher looks at version 2
        GoalInfo v2 = asAlice(() -> goals.goal(s.goal().id()));
        asAlice(() -> goals.update(v2.id(), v2.asChange().withText("Explain recursion, its base case and depth")));

        mvc.perform(confirm(s.course(), shown.linkId(), shown.goalVersion()).with(as(uniA, "alice")))
                .andExpect(status().isConflict());
        AlignmentReview reloaded = asAlice(() -> alignment.openReviews(s.course())).getFirst();
        assertThat(reloaded.goalVersion()).isEqualTo(3);
        assertThat(reloaded.goalVersionSeen()).isEqualTo(1);
        assertThat(reloaded.seenText()).isEqualTo("Explain recursion");

        // with the version now shown it goes through, and confirming again changes nothing
        asAlice(() -> alignment.confirm(shown.linkId(), 3));
        AlignmentLinkInfo again = asAlice(() -> alignment.confirm(shown.linkId(), 3));
        assertThat(again.reviewState()).isEqualTo("OK");
        assertThat(again.goalVersionSeen()).isEqualTo(3);

        // a later change marks the confirmed link again, keeping the version that was checked
        GoalInfo v3 = asAlice(() -> goals.goal(s.goal().id()));
        asAlice(() -> goals.update(v3.id(), v3.asChange().withText("Version 4")));
        assertThat(asAlice(() -> alignment.openReviews(s.course())))
                .filteredOn(r -> r.linkId().equals(shown.linkId()))
                .singleElement().satisfies(r -> {
                    assertThat(r.goalVersionSeen()).isEqualTo(3);
                    assertThat(r.goalVersion()).isEqualTo(4);
                });
    }

    @Test
    void confirmingNeedsTheGoalVersion() throws Exception {
        Setup s = linkedGoal();
        rename(s, "Explain recursion and its base case");
        UUID linkId = asAlice(() -> alignment.openReviews(s.course())).getFirst().linkId();
        mvc.perform(post("/core/api/courses/{c}/reviews/{l}/confirm", s.course(), linkId).with(as(uniA, "alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());
        assertThat(asAlice(() -> alignment.openReviews(s.course()))).hasSize(2);
    }

    @Test
    void viewersReadTheListButOnlyEditorsConfirmAndLink() throws Exception {
        Setup s = linkedGoal();
        TestLogin.login(identities, uniA, "bob");
        mvc.perform(put("/core/api/courses/{c}/members", s.course()).with(as(uniA, "alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalId\":\"bob\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isOk());
        rename(s, "Explain recursion and its base case");
        UUID linkId = asAlice(() -> alignment.openReviews(s.course())).getFirst().linkId();

        mvc.perform(get("/core/api/courses/{c}/reviews", s.course()).with(as(uniA, "bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(confirm(s.course(), linkId, 2).with(as(uniA, "bob")))
                .andExpect(status().isForbidden());
        TestLogin.runAs(identities, uniA, "bob", () -> {
            assertThatThrownBy(() -> alignment.link(s.goal().id(), DomainModule.TEACHING, "SESSION", "other"))
                    .isInstanceOf(CourseAccessDeniedException.class);
            assertThatThrownBy(() -> alignment.unlink(s.goal().id(), DomainModule.TEACHING, "SESSION", session))
                    .isInstanceOf(CourseAccessDeniedException.class);
            return null;
        });

        // no membership: as if the course and the link did not exist; links are not listed either
        mvc.perform(get("/core/api/courses/{c}/reviews", s.course()).with(as(uniA, "carol")))
                .andExpect(status().isNotFound());
        mvc.perform(confirm(s.course(), linkId, 2).with(as(uniA, "carol")))
                .andExpect(status().isNotFound());
        TestLogin.runAs(identities, uniA, "carol", () -> {
            assertThat(alignment.goalsFor(DomainModule.TEACHING, "SESSION", session)).isEmpty();
            assertThatThrownBy(() -> alignment.confirm(linkId, 2)).isInstanceOf(AlignmentLinkNotFoundException.class);
            return null;
        });
    }

    @Test
    void anotherOrganisationSeesNeitherTheListNorTheLinks() throws Exception {
        Setup s = linkedGoal();
        rename(s, "Explain recursion and its base case");
        UUID linkId = asAlice(() -> alignment.openReviews(s.course())).getFirst().linkId();

        mvc.perform(get("/core/api/courses/{c}/reviews", s.course()).with(as(uniB, "alice")))
                .andExpect(status().isNotFound());
        mvc.perform(confirm(s.course(), linkId, 2).with(as(uniB, "alice")))
                .andExpect(status().isNotFound());
        TestLogin.runAs(identities, uniB, "alice", () -> {
            assertThat(alignment.goalsFor(DomainModule.TEACHING, "SESSION", session)).isEmpty();
            assertThatThrownBy(() -> alignment.confirm(linkId, 2)).isInstanceOf(AlignmentLinkNotFoundException.class);
            return null;
        });
        assertThat(asAlice(() -> alignment.openReviews(s.course()))).hasSize(2);
    }

    @Test
    void deletingACoursePublishesCourseDeleted() {
        Setup s = linkedGoal();
        asAlice(() -> {
            courses.delete(s.course());
            return null;
        });
        assertThat(events.stream(CourseDeleted.class)).containsExactly(new CourseDeleted(orgOfAlice(), s.course()));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder confirm(UUID course, UUID linkId,
            int goalVersion) {
        return post("/core/api/courses/{c}/reviews/{l}/confirm", course, linkId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"goalVersion\":" + goalVersion + "}");
    }

    private UUID orgOfAlice() {
        return TestLogin.login(identities, uniA, "alice").orgId();
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor as(String org, String user) {
        return TestLogin.as(identities, org, user);
    }
}
