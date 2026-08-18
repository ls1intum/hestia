package de.tum.cit.hestia.learninggoalhub.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.hestia.learninggoalhub.goal.GoalKind;
import de.tum.cit.hestia.learninggoalhub.document.LanguageDetectionService;
import de.tum.cit.hestia.learninggoalhub.document.PageDescriptionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.StructuredOutputConverter;

class SessionExtractionServiceTest {

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
    void returnsStructuredOutcomesFromChatClient() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);

        List<ExtractedSkill> expected = List.of(
                new ExtractedSkill("Apply the testing strategy.", "Testing Strategy", GoalKind.EXPLICIT,
                        0, 0,
                        List.of(new ExtractedSkill.Knowledge("Explain the testing strategy.", "Testing Strategy",
                                GoalKind.EXPLICIT, 0, 0))),
                new ExtractedSkill("Apply the strategy to a small project.", "Testing Practice", GoalKind.IMPLICIT,
                        0, 0, List.of()));
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(expected);

        clearInvocations(spec);
        List<ExtractedSkill> result = new SessionExtractionService(builder, mock(LanguageDetectionService.class), 0.2)
                .extract("Session 4: Testing", "FULL-SESSION-MARKER-42");

        assertThat(result).containsExactlyElementsOf(expected);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(spec).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("Session 4: Testing")
                .contains("[0] FULL-SESSION-MARKER-42")
                // Asserted in fragments: the template wraps both sentences across two lines.
                .contains("HARD CAP")
                .contains("more than seven skills")
                .contains("If you are unsure whether something")
                .contains("is a skill or knowledge, make it knowledge")
                .contains("knowledge children")
                .contains("Apply Bayes' theorem")
                // Knowledge must be demanded as an OUTCOME, not as a bare fact: the word
                // "declarative" used to licence propositions and produced 55% bare statements.
                .doesNotContain("declarative")
                .contains("start with a verb naming what the student does with it")
                .contains("Never state a bare fact")
                .contains("WRONG:")
                .contains("RIGHT:")
                .contains("The verb-initial rule and every")
                .contains("source-line rule")
                // Guards the recomposed-quote failure: heading + its bullets read as one block.
                .contains("are SEPARATE")
                .contains("learning objectives")
                .contains("Choose each outcome's verb by what the STUDENT")
                .contains("Do not invent outcomes")
                .contains("shortLabel")
                .contains("2-6 word label naming the action and its topic")
                .contains("sourceStartLine")
                .contains("sourceEndLine")
                .doesNotContain("sourceSnippet");
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
        new SessionExtractionService(builder, mock(LanguageDetectionService.class), 0.2)
                .extract("title", "text", "German", "qwen3.6-35b-a3b");

        ArgumentCaptor<ChatOptions> optionsCaptor = ArgumentCaptor.forClass(ChatOptions.class);
        verify(spec).options(optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getModel()).isEqualTo("qwen3.6-35b-a3b");
    }

    @Test
    void instructsModelToUseRequestedLanguageAndReturnLineIndices() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of());

        clearInvocations(spec);
        new SessionExtractionService(builder, mock(LanguageDetectionService.class), 0.2)
                .extract("title", "text", "German", null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(spec).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("in German")
                .contains("never translate")
                .contains("Final language requirement")
                .contains("EXPLICIT or IMPLICIT");
        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(spec).system(systemCaptor.capture());
        assertThat(systemCaptor.getValue())
                .contains("every GENERATED field")
                .contains("must be written in German")
                .contains("Verbatim quotes")
                .contains("Do not translate");
    }

    @Test
    void emptyFigureListLeavesTheDirectPromptWithoutFigureInstructions() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        clearInvocations(spec);
        SessionExtractionService service = new SessionExtractionService(builder, mock(LanguageDetectionService.class), 0.2);
        service.extract("title", "text", "English", null);
        service.extract("title", "text", null, "English", null, List.of());

        verify(spec, times(2)).user(promptCaptor.capture());
        assertThat(promptCaptor.getAllValues().get(0))
                .isEqualTo(promptCaptor.getAllValues().get(1));
        assertThat(promptCaptor.getAllValues().get(1))
                .doesNotContain("Figure descriptions")
                .doesNotContain("sourceFigure")
                .contains("[0] text");
    }

    @Test
    void appendsFigureDescriptionsAndFigureOnlySourceRuleWhenProvided() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        clearInvocations(spec);
        new SessionExtractionService(builder, mock(LanguageDetectionService.class), 0.2)
                .extract("title", "text", null, "English", null,
                List.of(new PageDescriptionService.FigureDescription(12, "A process diagram.")));

        verify(spec).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("Figure descriptions (AI-generated from rendered slides — NOT verbatim text):")
                .contains("[F0] (page 12) A process diagram.")
                .contains("ONLY when no numbered lines support an outcome")
                .contains("sourceFigure")
                .contains("Final language requirement");
    }

    @Test
    void retriesOnceWhenGeneratedLanguageConfidentlyMismatches() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        List<ExtractedSkill> first = List.of(new ExtractedSkill("English outcome", "English label",
                GoalKind.EXPLICIT, 0, 0, List.of()));
        List<ExtractedSkill> retry = List.of(new ExtractedSkill("Deutsches Ergebnis", "Deutsche Bezeichnung",
                GoalKind.EXPLICIT, 0, 0, List.of()));
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(first, retry);
        LanguageDetectionService detector = mock(LanguageDetectionService.class);
        when(detector.detect(anyString())).thenReturn("en", "de");

        List<ExtractedSkill> result = new SessionExtractionService(builder, detector, 0.2)
                .extract("title", "text", "de", "German", null);

        assertThat(result).containsExactlyElementsOf(retry);
        ArgumentCaptor<ChatOptions> optionsCaptor = ArgumentCaptor.forClass(ChatOptions.class);
        verify(spec, times(2)).options(optionsCaptor.capture());
        assertThat(optionsCaptor.getAllValues().get(0).getTemperature()).isEqualTo(0.2);
        assertThat(optionsCaptor.getAllValues().get(1).getTemperature()).isEqualTo(0.0);
        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(spec, times(2)).system(systemCaptor.capture());
        assertThat(systemCaptor.getAllValues().get(1)).contains("language-correction retry");
    }
}
