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
import org.springframework.core.ParameterizedTypeReference;

class TerminalCompetencySynthesizerTest {

    @Test
    void synthesizeReturnsCompetenciesFromChatClient() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);

        List<TerminalCompetency> expected = List.of(
                new TerminalCompetency("Deploy a cloud-native application to a managed environment.", List.of(0, 1)));
        when(chatClient.prompt().user(anyString()).call().entity(any(ParameterizedTypeReference.class)))
                .thenReturn(expected);

        TerminalCompetencySynthesizer synthesizer = new TerminalCompetencySynthesizer(builder);

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
        clearInvocations(chatClient.prompt());

        TerminalCompetencySynthesizer synthesizer = new TerminalCompetencySynthesizer(builder);

        assertThat(synthesizer.synthesize(List.of(), null)).isEmpty();
        assertThat(synthesizer.synthesize(null, null)).isEmpty();
        verify(chatClient.prompt(), never()).user(anyString());
    }

    @Test
    void promptSeedsFromDoingVerbsAndMergesAggressively() {
        assertThat(TerminalCompetencySynthesizer.PROMPT)
                .contains("TERMINAL COMPETENCIES")
                .contains("ALL of the course's session/exercise learning goals")
                .contains("APPLY and CREATE goals are the SEEDS")
                .contains("ANALYZE and EVALUATE goals are usually")
                .contains("MERGE AGGRESSIVELY")
                .contains("TOO FINE")
                .contains("supported by only ONE goal is SUSPICIOUS")
                .contains("COVERAGE: every APPLY and CREATE candidate")
                .contains("ADD its own competency")
                .contains("assign every input goal to exactly")
                .contains("REMEMBER and UNDERSTAND")
                .contains("SINGLE leading action verb")
                .contains("ERR ON THE SIDE OF FEWER")
                .contains("not target or pad to a number")
                .contains("complete list")
                .contains("supporting");
    }

    @Test
    void labelsCandidatesWithBloomLevelInPrompt() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt().user(anyString()).call().entity(any(ParameterizedTypeReference.class)))
                .thenReturn(List.of());

        clearInvocations(chatClient.prompt());
        new TerminalCompetencySynthesizer(builder)
                .synthesize(List.of(new Candidate("UNIQUE-GOAL-MARKER-99", "CREATE"),
                        new Candidate("LOWER-KNOWLEDGE-MARKER-100", "UNDERSTAND")), null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClient.prompt()).user(promptCaptor.capture());
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
        when(chatClient.prompt().options(any(ChatOptions.class)).user(anyString()).call()
                .entity(any(ParameterizedTypeReference.class)))
                .thenReturn(List.of());

        clearInvocations(chatClient.prompt());
        new TerminalCompetencySynthesizer(builder)
                .synthesize(List.of(new Candidate("a goal", "APPLY")), "openai-gpt-oss-120b");

        ArgumentCaptor<ChatOptions> optionsCaptor = ArgumentCaptor.forClass(ChatOptions.class);
        verify(chatClient.prompt()).options(optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getModel()).isEqualTo("openai-gpt-oss-120b");
    }
}
