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

import de.tum.cit.hestia.learninggoalhub.extraction.CompetencyAssignmentSynthesizer.Candidate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.ParameterizedTypeReference;

class CompetencyAssignmentSynthesizerTest {

    private static final List<String> TWO_COMPETENCIES =
            List.of("Containerise applications.", "Secure cloud workloads.");

    private static CompetencyAssignmentSynthesizer synthesizerReturning(ChatClient chatClient) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        return new CompetencyAssignmentSynthesizer(builder);
    }

    private static List<Candidate> twoGoals() {
        return List.of(new Candidate("Build a container image.", "APPLY", "Containers"),
                new Candidate("Rotate a workload credential.", "APPLY", "Security"));
    }

    @Test
    void mapsEachGoalToItsCompetency() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().user(anyString()).call().entity(any(ParameterizedTypeReference.class)))
                .thenReturn(List.of(new CompetencyAssignment(0, 0), new CompetencyAssignment(1, 1)));

        Map<Integer, Integer> result =
                synthesizerReturning(chatClient).assign(TWO_COMPETENCIES, twoGoals(), null);

        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(0, 0, 1, 1));
    }

    /**
     * A null competencyIndex is the model saying "no competency fits this goal". It has to survive
     * as a null rather than being dropped, because the caller turns exactly those goals into the
     * catch-all instead of forcing them under an unrelated competency.
     */
    @Test
    void keepsExplicitNoMatchAsNull() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().user(anyString()).call().entity(any(ParameterizedTypeReference.class)))
                .thenReturn(List.of(new CompetencyAssignment(0, 0), new CompetencyAssignment(1, null)));

        Map<Integer, Integer> result =
                synthesizerReturning(chatClient).assign(TWO_COMPETENCIES, twoGoals(), null);

        assertThat(result).containsEntry(0, 0).containsKey(1);
        assertThat(result.get(1)).isNull();
    }

    /**
     * An index pointing at a competency that does not exist is not a placement. Treating it as a
     * no-match sends the goal to the catch-all instead of silently parking it under whichever
     * competency happened to sit at a clamped index.
     */
    @Test
    void turnsOutOfRangeCompetencyIndexIntoNoMatch() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().user(anyString()).call().entity(any(ParameterizedTypeReference.class)))
                .thenReturn(List.of(new CompetencyAssignment(0, 99), new CompetencyAssignment(1, -3)));

        Map<Integer, Integer> result =
                synthesizerReturning(chatClient).assign(TWO_COMPETENCIES, twoGoals(), null);

        assertThat(result).containsKeys(0, 1);
        assertThat(result.get(0)).isNull();
        assertThat(result.get(1)).isNull();
    }

    @Test
    void dropsUnknownGoalIndicesAndKeepsTheFirstAnswerForARepeatedGoal() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().user(anyString()).call().entity(any(ParameterizedTypeReference.class)))
                .thenReturn(List.of(new CompetencyAssignment(0, 0),
                        new CompetencyAssignment(0, 1),
                        new CompetencyAssignment(42, 1),
                        new CompetencyAssignment(-1, 0)));

        Map<Integer, Integer> result =
                synthesizerReturning(chatClient).assign(TWO_COMPETENCIES, twoGoals(), null);

        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(0, 0));
    }

    @Test
    void returnsEmptyAndSkipsLlmWithoutCompetenciesOrGoals() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        CompetencyAssignmentSynthesizer synthesizer = synthesizerReturning(chatClient);
        clearInvocations(chatClient.prompt());

        assertThat(synthesizer.assign(List.of(), twoGoals(), null)).isEmpty();
        assertThat(synthesizer.assign(TWO_COMPETENCIES, List.of(), null)).isEmpty();
        assertThat(synthesizer.assign(null, null, null)).isEmpty();
        verify(chatClient.prompt(), never()).user(anyString());
    }

    /**
     * The session label rides along so the model can tell terse goals apart, but grouping BY session
     * would rebuild the per-lecture silos that terminal competencies exist to cut across. The prompt
     * has to say so, or the extra context makes the assignment worse rather than better.
     */
    @Test
    void promptOffersSessionAsTieBreakerOnlyAndForbidsGroupingByIt() {
        assertThat(CompetencyAssignmentSynthesizer.PROMPT)
                .contains("Decide by CAPABILITY")
                .contains("cut ACROSS sessions")
                .contains("only to break a genuine tie")
                .contains("Grouping the")
                .contains("null competencyIndex");
    }

    @Test
    void putsCompetenciesGoalsBloomAndSessionInThePrompt() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().user(anyString()).call().entity(any(ParameterizedTypeReference.class)))
                .thenReturn(List.of());
        clearInvocations(chatClient.prompt());

        synthesizerReturning(chatClient).assign(List.of("UNIQUE-COMPETENCY-7"),
                List.of(new Candidate("UNIQUE-GOAL-8", "UNDERSTAND", "UNIQUE-SESSION-9")), null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClient.prompt()).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("[0] UNIQUE-COMPETENCY-7")
                .contains("UNIQUE-GOAL-8")
                .contains("(UNDERSTAND)")
                .contains("{UNIQUE-SESSION-9}");
    }

    @Test
    void appliesModelOverrideWhenProvided() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().options(any(ChatOptions.class)).user(anyString()).call()
                .entity(any(ParameterizedTypeReference.class)))
                .thenReturn(List.of());
        clearInvocations(chatClient.prompt());

        synthesizerReturning(chatClient).assign(TWO_COMPETENCIES, twoGoals(), "openai-gpt-oss-120b");

        ArgumentCaptor<ChatOptions> optionsCaptor = ArgumentCaptor.forClass(ChatOptions.class);
        verify(chatClient.prompt()).options(optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getModel()).isEqualTo("openai-gpt-oss-120b");
    }
}
