package de.tum.cit.hestia.learninggoalhub.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.hestia.learninggoalhub.goal.GoalKind;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.ParameterizedTypeReference;

class SessionExtractionServiceTest {

    @Test
    void returnsStructuredOutcomesFromChatClient() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);

        List<ExtractedGoal> expected = List.of(
                new ExtractedGoal("Explain the testing strategy.", GoalKind.EXPLICIT, "...learning objectives..."),
                new ExtractedGoal("Apply the strategy to a small project.", GoalKind.IMPLICIT, "...project example..."));
        when(chatClient.prompt().user(anyString()).call().entity(any(ParameterizedTypeReference.class)))
                .thenReturn(expected);

        clearInvocations(chatClient.prompt());
        List<ExtractedGoal> result = new SessionExtractionService(builder)
                .extract("Session 4: Testing", "FULL-SESSION-MARKER-42");

        assertThat(result).containsExactlyElementsOf(expected);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClient.prompt()).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("Session 4: Testing")
                .contains("FULL-SESSION-MARKER-42")
                .contains("three to seven")
                .contains("learning objectives")
                .contains("Choose each outcome's verb by what the STUDENT")
                .contains("Do not invent outcomes")
                .contains("sourceSnippet");
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
        new SessionExtractionService(builder).extract("title", "text", "qwen3.6-35b-a3b");

        ArgumentCaptor<ChatOptions> optionsCaptor = ArgumentCaptor.forClass(ChatOptions.class);
        verify(chatClient.prompt()).options(optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getModel()).isEqualTo("qwen3.6-35b-a3b");
    }
}
