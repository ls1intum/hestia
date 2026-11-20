package de.tum.cit.hestia.materials.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.hestia.core.CourseAccessDeniedException;
import de.tum.cit.hestia.core.CourseNotFoundException;
import de.tum.cit.hestia.core.DomainModule;
import de.tum.cit.hestia.core.GoalKind;
import de.tum.cit.hestia.core.GoalOrigin;
import de.tum.cit.hestia.core.GoalStatus;
import de.tum.cit.hestia.core.TestLogin;
import de.tum.cit.hestia.core.internal.identity.IdentityService;
import de.tum.cit.hestia.core.spi.ExamGoalDerivation;
import de.tum.cit.hestia.materials.GoalFixtures;
import de.tum.cit.hestia.materials.MaterialsTestBase;
import de.tum.cit.hestia.materials.MaterialsTestConfiguration;
import de.tum.cit.hestia.materials.TestTenant;
import de.tum.cit.hestia.materials.goal.PlacedGoal;
import de.tum.cit.hestia.materials.taxonomy.BloomLevel;
import de.tum.cit.hestia.materials.taxonomy.TaxonomyClassification;
import de.tum.cit.hestia.materials.taxonomy.TaxonomyService;
import de.tum.cit.hestia.materials.topictree.TopicTreeSynthesizer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// As in production: without open-in-view, a lazy association read outside a transaction fails here too.
@Import(MaterialsTestConfiguration.class)
@SpringBootTest(properties = "spring.jpa.open-in-view=false")
@AutoConfigureMockMvc
class ExamGoalDerivationServiceTest extends MaterialsTestBase {

    @Autowired
    private ExamGoalDerivation derivation;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IdentityService identities;

    @MockitoBean
    private ExamGoalGenerator generator;

    @MockitoBean
    private TaxonomyService taxonomyService;

    @MockitoBean
    private TopicTreeSynthesizer topicTreeSynthesizer;

    @Test
    void derivesAndStoresGoalsPerTaskLikeTheEndpoint() {
        UUID course = TestTenant.course("Derivation: ML");
        when(generator.generate(anyString(), eq("singleChoice"), anyString(), anyString(), isNull()))
                .thenReturn(List.of(new GeneratedExamGoal("Recalling basic integer addition.", "Recall integer addition.")));
        when(generator.generate(anyString(), eq("freeText"), anyString(), anyString(), isNull()))
                .thenReturn(List.of(
                        new GeneratedExamGoal("Explain the impact of LLMs on knowledge work."),
                        new GeneratedExamGoal("Evaluate the use of LLMs with a personal example.")));
        when(taxonomyService.classifyBatch(anyList(), isNull())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> new TaxonomyClassification(BloomLevel.UNDERSTAND, null)).toList();
        });

        List<ExamGoalDerivation.TaskGoals> result = derivation.derive(course, List.of(
                new ExamGoalDerivation.ExamTask("1", "context", null, "The exam covers the ML lecture."),
                new ExamGoalDerivation.ExamTask("2", "task", "singleChoice", "What is 1 + 1?"),
                new ExamGoalDerivation.ExamTask("3", "TASK", "freeText", "Explain how LLMs shaped work.")));

        assertThat(result).extracting(ExamGoalDerivation.TaskGoals::blockId).containsExactly("2", "3");
        assertThat(result.get(0).goals()).hasSize(1);
        var first = result.get(0).goals().get(0);
        assertThat(first.text()).isEqualTo("Recalling basic integer addition");
        assertThat(first.courseId()).isEqualTo(course);
        assertThat(first.kind()).isEqualTo(GoalKind.IMPLICIT);
        assertThat(first.origin()).isEqualTo(GoalOrigin.EXAM);
        assertThat(first.status()).isEqualTo(GoalStatus.PENDING);
        assertThat(first.bloomLevel()).isEqualTo("UNDERSTAND");
        // The goals are attributed to the module that asked for them, not to materials.
        assertThat(first.createdVia()).isEqualTo(DomainModule.ASSESSMENT);
        assertThat(result.get(1).goals()).hasSize(2);

        List<PlacedGoal> stored = GoalFixtures.goals(course);
        assertThat(stored).hasSize(3);
        assertThat(stored).allSatisfy(goal -> {
            assertThat(goal.origin()).isEqualTo(GoalOrigin.EXAM);
            assertThat(goal.info().createdVia()).isEqualTo(DomainModule.ASSESSMENT);
            // The exam task each goal came from is stored in the placement.
            assertThat(goal.examBlock()).isNotNull();
        });
        assertThat(result.stream().flatMap(t -> t.goals().stream()).map(g -> g.id()).toList())
                .containsExactlyInAnyOrderElementsOf(stored.stream().map(PlacedGoal::id).toList());
    }

    @Test
    void anUnknownCourseIsNotFound() {
        assertThatThrownBy(() -> derivation.derive(UUID.randomUUID(),
                List.of(new ExamGoalDerivation.ExamTask("1", "TASK", "freeText", "Explain."))))
                .isInstanceOf(CourseNotFoundException.class);
        verifyNoInteractions(generator);
    }

    @Test
    void aUserWhoIsNoMemberOfTheCourseGetsNotFound() {
        UUID foreign = TestTenant.courseAs("derivation-other.test", "carol", "Not yours");

        assertThatThrownBy(() -> derivation.derive(foreign,
                List.of(new ExamGoalDerivation.ExamTask("1", "TASK", "freeText", "Explain."))))
                .isInstanceOf(CourseNotFoundException.class);
        verifyNoInteractions(generator);
    }

    @Test
    void aViewerCannotDeriveGoals() throws Exception {
        UUID course = TestTenant.course("Derivation: viewer");
        TestLogin.login(identities, TestTenant.ORG, "viewer-vera");
        mockMvc.perform(put("/core/api/courses/" + course + "/members")
                        .with(TestLogin.as(identities, TestTenant.ORG, TestTenant.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalId\":\"viewer-vera\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isOk());

        assertThatThrownBy(() -> TestLogin.runAs(identities, TestTenant.ORG, "viewer-vera",
                () -> derivation.derive(course,
                        List.of(new ExamGoalDerivation.ExamTask("1", "TASK", "freeText", "Explain.")))))
                .isInstanceOf(CourseAccessDeniedException.class);
        verifyNoInteractions(generator);
        assertThat(GoalFixtures.goals(course)).isEmpty();
    }

    @Test
    void anInvalidExamIsRejectedBeforeAnyModelCall() {
        UUID course = TestTenant.course("Derivation: invalid");

        assertThatThrownBy(() -> derivation.derive(course, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> derivation.derive(course,
                List.of(new ExamGoalDerivation.ExamTask("1", "CONTEXT", null, "Only context."))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(GoalFixtures.goals(course)).isEmpty();
        verifyNoInteractions(generator);
    }
}
