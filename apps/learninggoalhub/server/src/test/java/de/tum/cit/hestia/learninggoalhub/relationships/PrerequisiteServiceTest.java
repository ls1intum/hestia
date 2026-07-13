package de.tum.cit.hestia.learninggoalhub.relationships;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;

class PrerequisiteServiceTest {

    @Test
    void alignsJudgmentsToPairsByIndex() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);

        // Returned out of order and missing the pair at index 2 to prove index-based alignment.
        when(chatClient.prompt().user(anyString()).call().entity(eq(PrerequisitePairJudgments.class)))
                .thenReturn(new PrerequisitePairJudgments(List.of(
                        new PrerequisitePairJudgment(3, PrerequisiteDirection.B_BEFORE_A, 0.8),
                        new PrerequisitePairJudgment(1, PrerequisiteDirection.A_BEFORE_B, 0.9))));

        List<PrerequisitePairJudgment> result = new PrerequisiteService(builder).judge(List.of(
                new GoalPair("a1", "b1"), new GoalPair("a2", "b2"), new GoalPair("a3", "b3")));

        assertThat(result).hasSize(3);
        assertThat(result.get(0).direction()).isEqualTo(PrerequisiteDirection.A_BEFORE_B);
        assertThat(result.get(1)).isNull();
        assertThat(result.get(2).direction()).isEqualTo(PrerequisiteDirection.B_BEFORE_A);
    }

    @Test
    void returnsEmptyForEmptyInput() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class, RETURNS_DEEP_STUBS));

        assertThat(new PrerequisiteService(builder).judge(List.of())).isEmpty();
    }

    @Test
    void promptNumbersEveryPair() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt().user(anyString()).call().entity(eq(PrerequisitePairJudgments.class)))
                .thenReturn(new PrerequisitePairJudgments(List.of()));

        clearInvocations(chatClient.prompt());
        new PrerequisiteService(builder).judge(List.of(
                new GoalPair("UNIQUE-A-71", "UNIQUE-B-71"), new GoalPair("UNIQUE-A-72", "UNIQUE-B-72")));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(chatClient.prompt()).user(captor.capture());
        assertThat(captor.getValue())
                .contains("Pair 1:").contains("UNIQUE-A-71").contains("UNIQUE-B-71")
                .contains("Pair 2:").contains("UNIQUE-A-72").contains("UNIQUE-B-72");
    }

    @Test
    void appliesModelOverride() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt().options(any(ChatOptions.class)).user(anyString()).call()
                .entity(eq(PrerequisitePairJudgments.class)))
                .thenReturn(new PrerequisitePairJudgments(List.of()));

        clearInvocations(chatClient.prompt());
        new PrerequisiteService(builder).judge(List.of(new GoalPair("a", "b")), "qwen3.6-35b-a3b");

        ArgumentCaptor<ChatOptions> captor = ArgumentCaptor.forClass(ChatOptions.class);
        verify(chatClient.prompt()).options(captor.capture());
        assertThat(captor.getValue().getModel()).isEqualTo("qwen3.6-35b-a3b");
    }
}
