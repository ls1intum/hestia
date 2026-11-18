package de.tum.cit.hestia.materials.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import de.tum.cit.hestia.core.spi.ExamGoalDerivation;
import de.tum.cit.hestia.materials.MaterialsTestBase;
import de.tum.cit.hestia.materials.MaterialsTestConfiguration;
import de.tum.cit.hestia.materials.course.Course;
import de.tum.cit.hestia.materials.course.CourseRepository;
import de.tum.cit.hestia.materials.goal.LearningGoal;
import de.tum.cit.hestia.materials.goal.LearningGoalRepository;
import de.tum.cit.hestia.materials.taxonomy.BloomLevel;
import de.tum.cit.hestia.materials.taxonomy.TaxonomyClassification;
import de.tum.cit.hestia.materials.taxonomy.TaxonomyService;
import de.tum.cit.hestia.materials.topictree.TopicTreeSynthesizer;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// As in production: without open-in-view, a lazy association read outside a transaction fails here too.
@Import(MaterialsTestConfiguration.class)
@SpringBootTest(properties = "spring.jpa.open-in-view=false")
class ExamGoalDerivationServiceTest extends MaterialsTestBase {

    @Autowired
    private ExamGoalDerivation derivation;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private LearningGoalRepository goalRepository;

    @MockitoBean
    private ExamGoalGenerator generator;

    @MockitoBean
    private TaxonomyService taxonomyService;

    @MockitoBean
    private TopicTreeSynthesizer topicTreeSynthesizer;

    @Test
    void derivesAndStoresGoalsPerTaskLikeTheEndpoint() {
        Course course = courseRepository.save(new Course("Derivation: ML"));
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

        List<ExamGoalDerivation.TaskGoals> result = derivation.derive(course.getId(), List.of(
                new ExamGoalDerivation.ExamTask("1", "context", null, "The exam covers the ML lecture."),
                new ExamGoalDerivation.ExamTask("2", "task", "singleChoice", "What is 1 + 1?"),
                new ExamGoalDerivation.ExamTask("3", "TASK", "freeText", "Explain how LLMs shaped work.")));

        assertThat(result).extracting(ExamGoalDerivation.TaskGoals::blockId).containsExactly("2", "3");
        assertThat(result.get(0).goals()).hasSize(1);
        assertThat(result.get(0).goals().get(0).text()).isEqualTo("Recalling basic integer addition");
        assertThat(result.get(0).goals().get(0).kind()).isEqualTo("IMPLICIT");
        assertThat(result.get(0).goals().get(0).origin()).isEqualTo("EXAM");
        assertThat(result.get(0).goals().get(0).status()).isEqualTo("PENDING");
        assertThat(result.get(0).goals().get(0).bloomLevel()).isEqualTo("UNDERSTAND");
        assertThat(result.get(0).goals().get(0).relationships()).isEmpty();
        assertThat(result.get(1).goals()).hasSize(2);

        List<LearningGoal> stored = goalRepository.findByCourseId(course.getId());
        assertThat(stored).hasSize(3);
        assertThat(result.stream().flatMap(t -> t.goals().stream()).map(g -> g.id()).toList())
                .containsExactlyInAnyOrderElementsOf(stored.stream().map(LearningGoal::getId).toList());
    }

    @Test
    void unknownCourseIsNoSuchElement() {
        assertThatThrownBy(() -> derivation.derive(987_654_321L,
                List.of(new ExamGoalDerivation.ExamTask("1", "TASK", "freeText", "Explain."))))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void anInvalidExamIsRejectedBeforeAnyModelCall() {
        Course course = courseRepository.save(new Course("Derivation: invalid"));

        assertThatThrownBy(() -> derivation.derive(course.getId(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> derivation.derive(course.getId(),
                List.of(new ExamGoalDerivation.ExamTask("1", "CONTEXT", null, "Only context."))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(goalRepository.findByCourseId(course.getId())).isEmpty();
    }
}
