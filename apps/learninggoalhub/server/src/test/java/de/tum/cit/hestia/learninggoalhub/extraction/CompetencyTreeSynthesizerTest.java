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

import de.tum.cit.hestia.learninggoalhub.extraction.CompetencyExpansion.Gap;
import de.tum.cit.hestia.learninggoalhub.extraction.CompetencyExpansion.KnowledgeLink;
import de.tum.cit.hestia.learninggoalhub.extraction.CompetencyExpansion.MissingSubSkill;
import de.tum.cit.hestia.learninggoalhub.extraction.CompetencyTreeSynthesizer.ExpansionInput;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.ParameterizedTypeReference;

class CompetencyTreeSynthesizerTest {

    private static CompetencyTreeSynthesizer synthesizerReturning(ChatClient chatClient) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        return new CompetencyTreeSynthesizer(builder);
    }

    @Test
    void expandAllReturnsMultipleCompetencyExpansionsFromChatClient() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        MissingSubSkill missing = new MissingSubSkill(
                "Sign a container image.", List.of(0), List.of("Explain why an image is signed."));
        List<CompetencyExpansion> expected = List.of(
                new CompetencyExpansion(0, List.of(new KnowledgeLink(0, 0)),
                        List.of(new Gap(0, "Define an image layer.")), List.of(missing)),
                new CompetencyExpansion(1, List.of(), List.of(), List.of()));
        when(chatClient.prompt().user(anyString()).call().entity(any(ParameterizedTypeReference.class)))
                .thenReturn(expected);
        clearInvocations(chatClient.prompt());

        List<CompetencyExpansion> result = synthesizerReturning(chatClient).expandAll(
                List.of(new ExpansionInput("Containerise applications.",
                                List.of("Build a container image."), List.of("Explain image layers.")),
                        new ExpansionInput("Secure cloud workloads.",
                                List.of("Configure workload identity."), List.of())), null);

        assertThat(result).containsExactlyElementsOf(expected);
        verify(chatClient.prompt()).user(anyString());
    }

    @Test
    void expandAllDropsMissingCompetenciesAndOutOfRangeResponseIndices() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        List<CompetencyExpansion> response = List.of(
                new CompetencyExpansion(1, List.of(), List.of(), List.of()),
                new CompetencyExpansion(99, List.of(), List.of(), List.of()));
        when(chatClient.prompt().user(anyString()).call().entity(any(ParameterizedTypeReference.class)))
                .thenReturn(response);

        List<CompetencyExpansion> result = synthesizerReturning(chatClient).expandAll(
                List.of(new ExpansionInput("First competency.", List.of("Do it first."), List.of()),
                        new ExpansionInput("Second competency.", List.of("Do it second."), List.of())), null);

        assertThat(result).containsExactly(response.get(0));
    }

    @Test
    void expandAllReturnsEmptyAndSkipsLlmWhenNoCompetencies() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        clearInvocations(chatClient.prompt());
        CompetencyTreeSynthesizer synthesizer = synthesizerReturning(chatClient);

        assertThat(synthesizer.expandAll(List.of(), null)).isEmpty();
        assertThat(synthesizer.expandAll(null, null)).isEmpty();
        verify(chatClient.prompt(), never()).user(anyString());
    }

    @Test
    void expandAllMapsKnowledgeLinksGapsAndMissingSubSkills() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        MissingSubSkill missing = new MissingSubSkill(
                "Sign a container image.", List.of(0), List.of("Explain why an image is signed."));
        CompetencyExpansion expected = new CompetencyExpansion(
                0,
                List.of(new KnowledgeLink(0, 0)),
                List.of(new Gap(0, "Define an image layer.")),
                List.of(missing));
        when(chatClient.prompt().user(anyString()).call().entity(any(ParameterizedTypeReference.class)))
                .thenReturn(List.of(expected));

        CompetencyExpansion result = synthesizerReturning(chatClient).expandAll(
                List.of(new ExpansionInput("Containerise applications.",
                        List.of("Build a container image."), List.of("Explain image layers."))), null).get(0);

        assertThat(result.competencyIndex()).isZero();
        assertThat(result.knowledge()).containsExactly(new KnowledgeLink(0, 0));
        assertThat(result.gaps()).containsExactly(new Gap(0, "Define an image layer."));
        assertThat(result.missingSubSkills()).containsExactly(missing);
    }

    @Test
    void expandPromptAttachesKnowledgeAndDemandsBothGapTiers() {
        assertThat(CompetencyTreeSynthesizer.EXPAND_PROMPT)
                .contains("ALL listed terminal competencies")
                .contains("[C0]")
                .contains("local indices")
                .contains("sub-skill")
                .contains("ATTACH each knowledge goal")
                .contains("MISSING SUB-SKILLS")
                .contains("MISSING KNOWLEDGE")
                .contains("SINGLE, ATOMIC")
                .contains("MUST bottom out in")
                .contains("Gaps are the EXCEPTION")
                .contains("APPLIED capability");
    }

    @Test
    void labelsCompetenciesWithLocalIndicesInExpandPrompt() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().user(anyString()).call().entity(any(ParameterizedTypeReference.class)))
                .thenReturn(List.of());
        clearInvocations(chatClient.prompt());

        synthesizerReturning(chatClient).expandAll(
                List.of(new ExpansionInput("COMPETENCY-MARKER-0", List.of("SUB-SKILL-MARKER-0"),
                                List.of("KNOWLEDGE-MARKER-0")),
                        new ExpansionInput("COMPETENCY-MARKER-1", List.of("SUB-SKILL-MARKER-1"),
                                List.of("KNOWLEDGE-MARKER-1"))), null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClient.prompt()).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("COMPETENCY-MARKER-0")
                .contains("COMPETENCY-MARKER-1")
                .contains("SUB-SKILL-MARKER-0")
                .contains("KNOWLEDGE-MARKER-1")
                .contains("[C0]")
                .contains("[C1]");
    }

    @Test
    void appliesModelOverrideWhenProvided() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().options(any(ChatOptions.class)).user(anyString()).call()
                .entity(any(ParameterizedTypeReference.class)))
                .thenReturn(List.of());
        clearInvocations(chatClient.prompt());

        synthesizerReturning(chatClient).expandAll(
                List.of(new ExpansionInput("A competency.", List.of("A sub-skill."), List.of())),
                "openai-gpt-oss-120b");

        ArgumentCaptor<ChatOptions> optionsCaptor = ArgumentCaptor.forClass(ChatOptions.class);
        verify(chatClient.prompt()).options(optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getModel()).isEqualTo("openai-gpt-oss-120b");
    }
}
