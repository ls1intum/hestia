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

class ModuleGoalSynthesizerTest {

    @Test
    void condenseSessionReturnsHeadlinesFromChatClient() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);

        List<SynthesizedModuleGoal> expected = List.of(
                new SynthesizedModuleGoal("Apply k-nearest-neighbours to classification problems.", List.of(0, 1)));
        when(chatClient.prompt().user(anyString()).call().entity(any(ParameterizedTypeReference.class)))
                .thenReturn(expected);

        ModuleGoalSynthesizer synthesizer = new ModuleGoalSynthesizer(builder);

        List<SynthesizedModuleGoal> result = synthesizer.condenseSession(
                "k-NN", List.of("Explain the k-neighbourhood.", "Choose a distance metric."), null);

        assertThat(result).containsExactlyElementsOf(expected);
    }

    @Test
    void integrateReturnsModuleGoalsFromChatClient() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);

        List<SynthesizedModuleGoal> expected = List.of(
                new SynthesizedModuleGoal("Design a complete machine learning workflow for a real problem."));
        when(chatClient.prompt().user(anyString()).call().entity(any(ParameterizedTypeReference.class)))
                .thenReturn(expected);

        ModuleGoalSynthesizer synthesizer = new ModuleGoalSynthesizer(builder);

        List<SynthesizedModuleGoal> result = synthesizer.integrate(
                List.of("Apply k-NN classification.", "Train neural networks."), null);

        assertThat(result).containsExactlyElementsOf(expected);
    }

    @Test
    void returnsEmptyAndSkipsLlmWhenNoInput() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        clearInvocations(chatClient.prompt());

        ModuleGoalSynthesizer synthesizer = new ModuleGoalSynthesizer(builder);

        assertThat(synthesizer.condenseSession("k-NN", List.of(), null)).isEmpty();
        assertThat(synthesizer.condenseSession("k-NN", null, null)).isEmpty();
        assertThat(synthesizer.integrate(List.of(), null)).isEmpty();
        assertThat(synthesizer.integrate(null, null)).isEmpty();
        verify(chatClient.prompt(), never()).user(anyString());
    }

    @Test
    void condensePromptDistilsToSingleTopicHeadlinesWithSingleVerb() {
        assertThat(ModuleGoalSynthesizer.CONDENSE_PROMPT)
                .contains("headline competencies")
                .contains("SINGLE leading action verb")
                .contains("chain verbs")
                .contains("supporting");
    }

    @Test
    void integratePromptSeeksConservativeCrossCuttingOutcomes() {
        assertThat(ModuleGoalSynthesizer.INTEGRATE_PROMPT)
                .contains("MODULE-level")
                .contains("INTEGRATE across")
                .contains("conservative")
                .contains("SINGLE leading action verb")
                .contains("chain verbs")
                .contains("EMPTY list")
                .doesNotContain("at most about 5");
    }

    @Test
    void verbFidelityGuidanceKeepsTheStudentLevel() {
        assertThat(ModuleGoalSynthesizer.VERB_FIDELITY)
                .contains("UNDERSTAND")
                .contains("prefer the lower level");
    }

    @Test
    void passesSessionTitleAndGoalsIntoCondensePrompt() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt().user(anyString()).call().entity(any(ParameterizedTypeReference.class)))
                .thenReturn(List.of());

        clearInvocations(chatClient.prompt());
        new ModuleGoalSynthesizer(builder)
                .condenseSession("UNIQUE-TITLE-MARKER-7", List.of("UNIQUE-GOAL-MARKER-99"), null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClient.prompt()).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("UNIQUE-TITLE-MARKER-7")
                .contains("UNIQUE-GOAL-MARKER-99");
    }

    @Test
    void passesHeadlinesIntoIntegratePrompt() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt().user(anyString()).call().entity(any(ParameterizedTypeReference.class)))
                .thenReturn(List.of());

        clearInvocations(chatClient.prompt());
        new ModuleGoalSynthesizer(builder).integrate(List.of("UNIQUE-HEADLINE-MARKER-42"), null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClient.prompt()).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("UNIQUE-HEADLINE-MARKER-42");
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
        new ModuleGoalSynthesizer(builder).integrate(List.of("a headline"), "qwen3.6-35b-a3b");

        ArgumentCaptor<ChatOptions> optionsCaptor = ArgumentCaptor.forClass(ChatOptions.class);
        verify(chatClient.prompt()).options(optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getModel()).isEqualTo("qwen3.6-35b-a3b");
    }
}
