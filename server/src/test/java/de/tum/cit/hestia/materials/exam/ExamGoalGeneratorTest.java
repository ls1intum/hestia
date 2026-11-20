package de.tum.cit.hestia.materials.exam;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.hestia.materials.llm.FakeLlm;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExamGoalGeneratorTest {

    private FakeLlm llm;
    private ExamGoalGenerator generator;

    private void setUp(List<GeneratedExamGoal> response) {
        llm = FakeLlm.replying(response);
        generator = new ExamGoalGenerator(llm.client(), 0.2);
    }

    @Test
    void returnsParsedGoalsFromTheModel() {
        List<GeneratedExamGoal> expected = List.of(
                new GeneratedExamGoal("Recall basic integer addition."),
                new GeneratedExamGoal("Explain the effect of LLMs on knowledge work."));
        setUp(expected);

        List<GeneratedExamGoal> result = generator.generate("", "singleChoice", "What is 1 + 1?", null);

        assertThat(result).containsExactlyElementsOf(expected);
    }

    @Test
    void passesTaskDescriptionTaskTypeAndContextIntoPrompt() {
        setUp(List.of());

        generator.generate("CONTEXT-MARKER-7", "freeText", "TASK-MARKER-42", "German", null);

        assertThat(llm.requests()).hasSize(1);
        assertThat(llm.only().module()).isEqualTo("materials");
        assertThat(llm.only().purpose()).isEqualTo("exam-goals");
        assertThat(llm.prompt())
                .contains("TASK-MARKER-42")
                .contains("CONTEXT-MARKER-7")
                .contains("freeText")
                .contains("in German")
                // The shared wording invariant is filled in, not left as a placeholder.
                .contains("Outcome wording invariant")
                .doesNotContain("%s");
    }

    @Test
    void omitsContextSectionWhenContextIsBlank() {
        setUp(List.of());

        generator.generate("  ", null, "What is 1 + 1?", null);

        assertThat(llm.requests()).hasSize(1);
        assertThat(llm.prompt())
                .doesNotContain("Shared exam context")
                .contains("task type: unspecified");
    }

    @Test
    void appliesModelOverrideWhenProvided() {
        setUp(List.of());

        generator.generate("", null, "task", "qwen3.6-35b-a3b");

                assertThat(llm.only().model().id()).isEqualTo("qwen3.6-35b-a3b");
    }

    @Test
    void promptDemandsAtomicConservativeGoals() {
        assertThat(ExamGoalGenerator.PROMPT_TEMPLATE)
                .contains("shortLabel")
                .contains("not ending with a period")
                .contains("ATOMIC")
                .contains("never more than")
                .contains("do not escalate");
    }
}
