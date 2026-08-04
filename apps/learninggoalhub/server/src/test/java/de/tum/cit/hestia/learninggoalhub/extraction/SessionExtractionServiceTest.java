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
import de.tum.cit.hestia.learninggoalhub.document.PageDescriptionService;
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

        List<ExtractedSkill> expected = List.of(
                new ExtractedSkill("Apply the testing strategy.", "Testing Strategy", GoalKind.EXPLICIT,
                        0, 0,
                        List.of(new ExtractedSkill.Knowledge("Explain the testing strategy.", "Testing Strategy",
                                GoalKind.EXPLICIT, 0, 0))),
                new ExtractedSkill("Apply the strategy to a small project.", "Testing Practice", GoalKind.IMPLICIT,
                        0, 0, List.of()));
        when(chatClient.prompt().user(anyString()).call().entity(any(ParameterizedTypeReference.class)))
                .thenReturn(expected);

        clearInvocations(chatClient.prompt());
        List<ExtractedSkill> result = new SessionExtractionService(builder)
                .extract("Session 4: Testing", "FULL-SESSION-MARKER-42");

        assertThat(result).containsExactlyElementsOf(expected);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClient.prompt()).user(promptCaptor.capture());
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
                .contains("2-5 word noun phrase")
                .contains("sourceStartLine")
                .contains("sourceEndLine")
                .doesNotContain("sourceSnippet");
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
        new SessionExtractionService(builder).extract("title", "text", "German", "qwen3.6-35b-a3b");

        ArgumentCaptor<ChatOptions> optionsCaptor = ArgumentCaptor.forClass(ChatOptions.class);
        verify(chatClient.prompt()).options(optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getModel()).isEqualTo("qwen3.6-35b-a3b");
    }

    @Test
    void instructsModelToUseRequestedLanguageAndReturnLineIndices() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt().user(anyString()).call().entity(any(ParameterizedTypeReference.class)))
                .thenReturn(List.of());

        clearInvocations(chatClient.prompt());
        new SessionExtractionService(builder).extract("title", "text", "German", null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClient.prompt()).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("in German")
                .contains("never translate")
                .contains("EXPLICIT or IMPLICIT");
    }

    @Test
    void emptyFigureListLeavesTheDirectPromptWithoutFigureInstructions() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt().user(anyString()).call().entity(any(ParameterizedTypeReference.class)))
                .thenReturn(List.of());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        clearInvocations(chatClient.prompt());
        SessionExtractionService service = new SessionExtractionService(builder);
        service.extract("title", "text", "English", null);
        service.extract("title", "text", "English", null, List.of());

        verify(chatClient.prompt(), times(2)).user(promptCaptor.capture());
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
        when(chatClient.prompt().user(anyString()).call().entity(any(ParameterizedTypeReference.class)))
                .thenReturn(List.of());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        clearInvocations(chatClient.prompt());
        new SessionExtractionService(builder).extract("title", "text", "English", null,
                List.of(new PageDescriptionService.FigureDescription(12, "A process diagram.")));

        verify(chatClient.prompt()).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("Figure descriptions (AI-generated from rendered slides — NOT verbatim text):")
                .contains("[F0] (page 12) A process diagram.")
                .contains("ONLY when no numbered lines support an outcome")
                .contains("sourceFigure");
    }
}
