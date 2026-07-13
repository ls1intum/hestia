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
import de.tum.cit.hestia.learninggoalhub.extraction.CompetencyTreeSynthesizer.Candidate;
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
    void assignReturnsAssignmentsFromChatClient() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        List<CompetencyAssignment> expected = List.of(new CompetencyAssignment(0, 1), new CompetencyAssignment(1, 0));
        when(chatClient.prompt().user(anyString()).call().entity(any(ParameterizedTypeReference.class)))
                .thenReturn(expected);

        List<CompetencyAssignment> result = synthesizerReturning(chatClient).assign(
                List.of("Containerise applications.", "Secure cloud workloads."),
                List.of(new Candidate("Build a container image.", "APPLY"),
                        new Candidate("Explain IAM roles.", "UNDERSTAND")),
                null);

        assertThat(result).containsExactlyElementsOf(expected);
    }

    @Test
    void assignReturnsEmptyAndSkipsLlmWhenNoCompetenciesOrGoals() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        clearInvocations(chatClient.prompt());
        CompetencyTreeSynthesizer synthesizer = synthesizerReturning(chatClient);

        assertThat(synthesizer.assign(List.of(), List.of(new Candidate("g", "APPLY")), null)).isEmpty();
        assertThat(synthesizer.assign(List.of("c"), List.of(), null)).isEmpty();
        assertThat(synthesizer.assign(null, null, null)).isEmpty();
        verify(chatClient.prompt(), never()).user(anyString());
    }

    @Test
    void expandReturnsKnowledgeLinksGapsAndMissingSubSkills() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        MissingSubSkill missing = new MissingSubSkill(
                "Sign a container image.", List.of(0), List.of("Explain why an image is signed."));
        CompetencyExpansion expected = new CompetencyExpansion(
                List.of(new KnowledgeLink(0, 0)),
                List.of(new Gap(0, "Define an image layer.")),
                List.of(missing));
        when(chatClient.prompt().user(anyString()).call().entity(any(ParameterizedTypeReference.class)))
                .thenReturn(expected);

        CompetencyExpansion result = synthesizerReturning(chatClient).expand(
                "Containerise applications.",
                List.of("Build a container image."),
                List.of("Explain image layers."),
                null);

        assertThat(result.knowledge()).containsExactly(new KnowledgeLink(0, 0));
        assertThat(result.gaps()).containsExactly(new Gap(0, "Define an image layer."));
        assertThat(result.missingSubSkills()).containsExactly(missing);
    }

    @Test
    void expandReturnsEmptyMissingSubSkillsWhenNoSubSkills() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        clearInvocations(chatClient.prompt());

        assertThat(synthesizerReturning(chatClient)
                .expand("c", List.of(), List.of("knowledge"), null).missingSubSkills())
                .isEmpty();
    }

    @Test
    void expandReturnsEmptyAndSkipsLlmWhenNoSubSkills() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        clearInvocations(chatClient.prompt());

        CompetencyExpansion result = synthesizerReturning(chatClient)
                .expand("c", List.of(), List.of("knowledge"), null);

        assertThat(result.knowledge()).isEmpty();
        assertThat(result.gaps()).isEmpty();
        verify(chatClient.prompt(), never()).user(anyString());
    }

    @Test
    void assignPromptDemandsFullCoverageAndSingleCompetency() {
        assertThat(CompetencyTreeSynthesizer.ASSIGN_PROMPT)
                .contains("TERMINAL")
                .contains("Assign EVERY learning goal")
                .contains("exactly ONE competency")
                .contains("competency = -1");
    }

    @Test
    void expandPromptAttachesKnowledgeAndDemandsBothGapTiers() {
        assertThat(CompetencyTreeSynthesizer.EXPAND_PROMPT)
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
    void labelsGoalsWithBloomLevelInAssignPrompt() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().user(anyString()).call().entity(any(ParameterizedTypeReference.class)))
                .thenReturn(List.of());
        clearInvocations(chatClient.prompt());

        synthesizerReturning(chatClient).assign(
                List.of("A competency."), List.of(new Candidate("UNIQUE-GOAL-MARKER-42", "CREATE")), null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClient.prompt()).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("UNIQUE-GOAL-MARKER-42")
                .contains("(CREATE)")
                .contains("[C0]");
    }

    @Test
    void appliesModelOverrideWhenProvided() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().options(any(ChatOptions.class)).user(anyString()).call()
                .entity(any(ParameterizedTypeReference.class)))
                .thenReturn(List.of());
        clearInvocations(chatClient.prompt());

        synthesizerReturning(chatClient).assign(
                List.of("A competency."), List.of(new Candidate("a goal", "APPLY")), "openai-gpt-oss-120b");

        ArgumentCaptor<ChatOptions> optionsCaptor = ArgumentCaptor.forClass(ChatOptions.class);
        verify(chatClient.prompt()).options(optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getModel()).isEqualTo("openai-gpt-oss-120b");
    }
}
