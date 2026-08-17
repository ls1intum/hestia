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
import org.springframework.ai.converter.StructuredOutputConverter;

class ExtractionServiceTest {

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
    void returnsParsedCandidatesFromChatClient() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);

        List<ExtractedGoal> expected = List.of(
                new ExtractedGoal("Apply test-driven development.", GoalKind.EXPLICIT, "...write a failing test first..."),
                new ExtractedGoal("Recognise the value of refactoring.", GoalKind.IMPLICIT, "...keep the design clean..."));

        when(stubSpec(chatClient).user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(expected);

        ExtractionService service = new ExtractionService(builder, 0.2);

        List<ExtractedGoal> result = service.extract("Lecture 3 covers TDD ...");

        assertThat(result).containsExactlyElementsOf(expected);
    }

    @Test
    void promptInstructsModelToReturnCandidateFieldsAndDropsSectionLogic() {
        assertThat(ExtractionService.PROMPT_TEMPLATE)
                .contains("text")
                .contains("kind")
                .contains("sourceSnippet")
                .contains("candidate")
                .doesNotContain("section")
                .doesNotContain("hierarchyPath");
    }

    @Test
    void passesChunkTextIntoPrompt() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of());

        clearInvocations(spec);
        new ExtractionService(builder, 0.2).extract("UNIQUE-DOCUMENT-MARKER-42");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(spec).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("UNIQUE-DOCUMENT-MARKER-42");
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
        new ExtractionService(builder, 0.2).extract("text", "German", "qwen3.6-35b-a3b");

        ArgumentCaptor<ChatOptions> optionsCaptor = ArgumentCaptor.forClass(ChatOptions.class);
        verify(spec).options(optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getModel()).isEqualTo("qwen3.6-35b-a3b");
        assertThat(optionsCaptor.getValue().getTemperature()).isEqualTo(0.2);
    }

    @Test
    void instructsModelToUseRequestedLanguageAndPreserveSnippets() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of());

        clearInvocations(spec);
        new ExtractionService(builder, 0.2).extract("text", "German", null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(spec).system(systemCaptor.capture());
        verify(spec).user(promptCaptor.capture());
        assertThat(systemCaptor.getValue()).contains("German");
        assertThat(promptCaptor.getValue())
                .contains("in German")
                .contains("never translate")
                .contains("EXPLICIT or IMPLICIT");
    }
}
