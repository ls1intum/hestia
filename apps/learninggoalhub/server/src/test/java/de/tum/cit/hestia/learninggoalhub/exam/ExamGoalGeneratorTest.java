package de.tum.cit.hestia.learninggoalhub.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.ParameterizedTypeReference;

class ExamGoalGeneratorTest {

    private ChatClient chatClient;
    private ExamGoalGenerator generator;

    private void setUp(List<GeneratedExamGoal> response) {
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt().user(anyString()).call().entity(any(ParameterizedTypeReference.class)))
                .thenReturn(response);
        generator = new ExamGoalGenerator(builder);
    }

    @Test
    void returnsParsedGoalsFromChatClient() {
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

        clearInvocations(chatClient.prompt());
        generator.generate("CONTEXT-MARKER-7", "freeText", "TASK-MARKER-42", "German", null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClient.prompt()).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("TASK-MARKER-42")
                .contains("CONTEXT-MARKER-7")
                .contains("freeText")
                .contains("in German");
    }

    @Test
    void omitsContextSectionWhenContextIsBlank() {
        setUp(List.of());

        clearInvocations(chatClient.prompt());
        generator.generate("  ", null, "What is 1 + 1?", null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClient.prompt()).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .doesNotContain("Shared exam context")
                .contains("task type: unspecified");
    }

    @Test
    void appliesModelOverrideWhenProvided() {
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt().options(any(ChatOptions.class)).user(anyString()).call()
                .entity(any(ParameterizedTypeReference.class)))
                .thenReturn(List.of());
        generator = new ExamGoalGenerator(builder);

        clearInvocations(chatClient.prompt());
        generator.generate("", null, "task", "qwen3.6-35b-a3b");

        ArgumentCaptor<ChatOptions> optionsCaptor = ArgumentCaptor.forClass(ChatOptions.class);
        verify(chatClient.prompt()).options(optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getModel()).isEqualTo("qwen3.6-35b-a3b");
    }

    @Test
    void promptDemandsAtomicConservativeGoals() {
        assertThat(ExamGoalGenerator.PROMPT_TEMPLATE)
                .contains("ATOMIC")
                .contains("never more than")
                .contains("do not escalate");
    }
}
