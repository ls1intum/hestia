package de.tum.cit.hestia.learninggoalhub.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.ParameterizedTypeReference;

class SessionGoalConsolidatorTest {

    @Test
    void returnsConsolidatedGoalsFromChatClient() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);

        List<ConsolidatedGoal> expected = List.of(
                new ConsolidatedGoal("Explain how gradient descent minimises a loss function.", List.of(0, 2)));
        when(chatClient.prompt().user(anyString()).call().entity(any(ParameterizedTypeReference.class)))
                .thenReturn(expected);

        SessionGoalConsolidator consolidator = new SessionGoalConsolidator(builder);

        List<ConsolidatedGoal> result = consolidator.consolidate(
                "Lecture 2: Optimisation",
                List.of("Compute a gradient.", "Pick a learning rate.", "Run gradient descent."));

        assertThat(result).containsExactlyElementsOf(expected);
    }

    @Test
    void returnsEmptyAndSkipsLlmWhenNoCandidates() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        clearInvocations(chatClient.prompt());

        SessionGoalConsolidator consolidator = new SessionGoalConsolidator(builder);

        assertThat(consolidator.consolidate("Lecture 1", List.of())).isEmpty();
        assertThat(consolidator.consolidate("Lecture 1", null)).isEmpty();
        verify(chatClient.prompt(), never()).user(anyString());
    }

    @Test
    void promptInstructsBroadAggregationAndLevelPreservation() {
        assertThat(SessionGoalConsolidator.PROMPT_TEMPLATE)
                .contains("Consolidate")
                .contains("BROAD")
                .contains("roll up")
                .contains("substantially shorter")
                .contains("UNRELATED")
                .contains("cognitive level")
                .contains("text")
                .contains("supporting");
    }

    @Test
    void passesSessionTitleAndCandidatesIntoPrompt() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt().user(anyString()).call().entity(any(ParameterizedTypeReference.class)))
                .thenReturn(List.of());

        clearInvocations(chatClient.prompt());
        new SessionGoalConsolidator(builder).consolidate("UNIQUE-TITLE-MARKER-7", List.of("UNIQUE-CANDIDATE-MARKER-99"));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClient.prompt()).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("UNIQUE-TITLE-MARKER-7")
                .contains("UNIQUE-CANDIDATE-MARKER-99");
    }

    @Test
    void appliesModelOverrideWhenProvided() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt().options(any(ChatOptions.class)).user(anyString()).call()
                .entity(any(ParameterizedTypeReference.class)))
                .thenReturn(List.of());

        clearInvocations(chatClient.prompt());
        new SessionGoalConsolidator(builder).consolidate("Lecture 1", List.of("a candidate"), "qwen3.6-35b-a3b");

        ArgumentCaptor<ChatOptions> optionsCaptor = ArgumentCaptor.forClass(ChatOptions.class);
        verify(chatClient.prompt()).options(optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getModel()).isEqualTo("qwen3.6-35b-a3b");
    }
}
