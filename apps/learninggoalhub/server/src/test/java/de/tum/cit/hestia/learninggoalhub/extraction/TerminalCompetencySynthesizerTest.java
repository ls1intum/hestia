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

import de.tum.cit.hestia.learninggoalhub.extraction.TerminalCompetencySynthesizer.Candidate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.StructuredOutputConverter;

class TerminalCompetencySynthesizerTest {

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
    void synthesizeReturnsCompetenciesFromChatClient() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);

        List<TerminalCompetency> expected = List.of(
                new TerminalCompetency("Deploy a cloud-native application to a managed environment.",
                        "Application Deployment"));
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(expected);

        TerminalCompetencySynthesizer synthesizer = new TerminalCompetencySynthesizer(builder, 0.2);

        List<TerminalCompetency> result = synthesizer.synthesize(
                List.of(new Candidate("Build a container image.", "APPLY"),
                        new Candidate("Deploy a workload to Kubernetes.", "APPLY")),
                null);

        assertThat(result).containsExactlyElementsOf(expected);
    }

    @Test
    void returnsEmptyAndSkipsLlmWhenNoInput() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        clearInvocations(spec);

        TerminalCompetencySynthesizer synthesizer = new TerminalCompetencySynthesizer(builder, 0.2);

        assertThat(synthesizer.synthesize(List.of(), null)).isEmpty();
        assertThat(synthesizer.synthesize(null, null)).isEmpty();
        verify(spec, never()).user(anyString());
    }

    @Test
    void promptSeedsFromDoingVerbsAndMergesAggressively() {
        assertThat(TerminalCompetencySynthesizer.PROMPT)
                .contains("TERMINAL COMPETENCIES")
                .contains("ALL of the course's session/exercise SKILL goals")
                .contains("APPLY and CREATE goals are the SEEDS")
                .contains("ANALYZE and EVALUATE skill goals may describe")
                .contains("MERGE AGGRESSIVELY")
                .contains("TOO FINE")
                .contains("supported by only ONE goal is SUSPICIOUS")
                .contains("ADD a competency for it")
                .contains("SINGLE leading action verb")
                .contains("shortLabel")
                .contains("2-6 word label naming the action and its topic")
                .contains("ERR ON THE SIDE OF FEWER")
                .contains("not target or pad to a number");
    }

    /**
     * Naming and assigning are separate calls now, so this prompt must not ask for an assignment:
     * a model that answers with goal indices here would place goals against a competency list that
     * is still being written, which is the failure the split exists to remove.
     */
    @Test
    void promptNamesOnlyAndDoesNotAskForGoalAssignment() {
        assertThat(TerminalCompetencySynthesizer.PROMPT)
                .contains("NAME ONLY")
                .doesNotContain("supporting");
    }

    @Test
    void labelsCandidatesWithBloomLevelInPrompt() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of());

        clearInvocations(spec);
        new TerminalCompetencySynthesizer(builder, 0.2)
                .synthesize(List.of(new Candidate("UNIQUE-GOAL-MARKER-99", "CREATE"),
                        new Candidate("LOWER-KNOWLEDGE-MARKER-100", "UNDERSTAND")), null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(spec).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("UNIQUE-GOAL-MARKER-99")
                .contains("(CREATE)")
                .contains("LOWER-KNOWLEDGE-MARKER-100")
                .contains("(UNDERSTAND)");
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
        new TerminalCompetencySynthesizer(builder, 0.2)
                .synthesize(List.of(new Candidate("a goal", "APPLY")), "German", "openai-gpt-oss-120b");

        ArgumentCaptor<ChatOptions> optionsCaptor = ArgumentCaptor.forClass(ChatOptions.class);
        verify(spec).options(optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getModel()).isEqualTo("openai-gpt-oss-120b");
    }

    @Test
    void instructsModelToUseRequestedLanguageAndPreserveBloomValues() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of());

        clearInvocations(spec);
        new TerminalCompetencySynthesizer(builder, 0.2)
                .synthesize(List.of(new Candidate("a goal", "APPLY")), "German", null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(spec).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("in German")
                .contains("English enum values")
                .contains("shortLabel");
    }
}
