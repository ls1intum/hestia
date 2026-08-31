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
import org.springframework.ai.converter.StructuredOutputConverter;

class SessionGoalConsolidatorTest {

    /**
     * The request spec returns itself from system() and options() so the whole call chain is one
     * mock. Rebuilding the chain inside a verify() would otherwise be recorded as another
     * invocation and make every count off by one.
     */
    private static ChatClient.ChatClientRequestSpec stubSpec(ChatClient chatClient) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.options(any(ChatOptions.class))).thenReturn(spec);
        return spec;
    }

    @Test
    void returnsConsolidatedGoalsFromChatClient() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);

        List<ConsolidatedGoal> expected = List.of(
                new ConsolidatedGoal("Explaining how gradient descent minimises a loss function.",
                        "Explain Gradient Descent",
                        List.of(0, 2)));
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(expected);

        SessionGoalConsolidator consolidator = new SessionGoalConsolidator(builder, 0.2);

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
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        clearInvocations(spec);

        SessionGoalConsolidator consolidator = new SessionGoalConsolidator(builder, 0.2);

        assertThat(consolidator.consolidate("Lecture 1", List.of())).isEmpty();
        assertThat(consolidator.consolidate("Lecture 1", null)).isEmpty();
        verify(spec, never()).user(anyString());
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
                .contains("shortLabel")
                .contains("2-6 word label naming the action and its topic")
                .contains("supporting");
    }

    @Test
    void passesSessionTitleAndCandidatesIntoPrompt() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of());

        clearInvocations(spec);
        new SessionGoalConsolidator(builder, 0.2)
                .consolidate("UNIQUE-TITLE-MARKER-7", List.of("UNIQUE-CANDIDATE-MARKER-99"));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(spec).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("UNIQUE-TITLE-MARKER-7")
                .contains("UNIQUE-CANDIDATE-MARKER-99");
    }

    @Test
    void appliesModelOverrideWhenProvided() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of());

        clearInvocations(spec);
        new SessionGoalConsolidator(builder, 0.2).consolidate(
                "Lecture 1", List.of("a candidate"), "German", "qwen3.6-35b-a3b");

        ArgumentCaptor<ChatOptions> optionsCaptor = ArgumentCaptor.forClass(ChatOptions.class);
        verify(spec).options(optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getModel()).isEqualTo("qwen3.6-35b-a3b");
    }

    @Test
    void instructsModelToUseRequestedLanguage() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of());

        clearInvocations(spec);
        new SessionGoalConsolidator(builder, 0.2)
                .consolidate("Lecture 1", List.of("a candidate"), "German", null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(spec).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("in German").contains("supporting");
    }
}
