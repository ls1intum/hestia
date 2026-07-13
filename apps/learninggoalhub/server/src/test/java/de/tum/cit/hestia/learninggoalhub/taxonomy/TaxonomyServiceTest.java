package de.tum.cit.hestia.learninggoalhub.taxonomy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.hestia.learninggoalhub.goal.BloomLevel;
import de.tum.cit.hestia.learninggoalhub.goal.SoloLevel;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.ParameterizedTypeReference;

class TaxonomyServiceTest {

    @Test
    void returnsParsedClassificationFromChatClient() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);

        TaxonomyClassification expected = new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL);
        when(chatClient.prompt().user(anyString()).call().entity(eq(TaxonomyClassification.class)))
                .thenReturn(expected);

        TaxonomyService service = new TaxonomyService(builder);

        TaxonomyClassification result = service.classify("Apply test-driven development to a new feature.");

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void promptInstructsModelOnBothTaxonomies() {
        assertThat(TaxonomyService.PROMPT_TEMPLATE)
                .contains("Bloom")
                .contains("SOLO")
                .contains("APPLY")
                .contains("RELATIONAL")
                .contains("EXTENDED_ABSTRACT");
    }

    @Test
    void passesGoalTextIntoPrompt() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt().user(anyString()).call().entity(eq(TaxonomyClassification.class)))
                .thenReturn(new TaxonomyClassification(BloomLevel.REMEMBER, SoloLevel.UNISTRUCTURAL));

        clearInvocations(chatClient.prompt());
        new TaxonomyService(builder).classify("UNIQUE-GOAL-MARKER-77");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClient.prompt()).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("UNIQUE-GOAL-MARKER-77");
    }

    @Test
    void appliesModelOverrideWhenProvided() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt().options(any(ChatOptions.class)).user(anyString()).call()
                .entity(eq(TaxonomyClassification.class)))
                .thenReturn(new TaxonomyClassification(BloomLevel.ANALYZE, SoloLevel.MULTISTRUCTURAL));

        clearInvocations(chatClient.prompt());
        new TaxonomyService(builder).classify("goal text", "qwen3.6-35b-a3b");

        ArgumentCaptor<ChatOptions> optionsCaptor = ArgumentCaptor.forClass(ChatOptions.class);
        verify(chatClient.prompt()).options(optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getModel()).isEqualTo("qwen3.6-35b-a3b");
    }

    @Test
    @SuppressWarnings("unchecked")
    void classifyBatchAlignsResultsToGoalsByIndex() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);

        // Returned out of order and missing the goal at index 2 to prove index-based alignment.
        when(chatClient.prompt().user(anyString()).call().entity(any(ParameterizedTypeReference.class)))
                .thenReturn(List.of(
                        new BatchTaxonomyItem(3, BloomLevel.ANALYZE, SoloLevel.RELATIONAL),
                        new BatchTaxonomyItem(1, BloomLevel.REMEMBER, SoloLevel.UNISTRUCTURAL)));

        List<TaxonomyClassification> result = new TaxonomyService(builder)
                .classifyBatch(List.of("goal one", "goal two", "goal three"), null);

        assertThat(result).hasSize(3);
        assertThat(result.get(0)).isEqualTo(new TaxonomyClassification(BloomLevel.REMEMBER, SoloLevel.UNISTRUCTURAL));
        assertThat(result.get(1)).isNull();
        assertThat(result.get(2)).isEqualTo(new TaxonomyClassification(BloomLevel.ANALYZE, SoloLevel.RELATIONAL));
    }

    @Test
    void classifyBatchReturnsEmptyForEmptyInput() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class, RETURNS_DEEP_STUBS));

        assertThat(new TaxonomyService(builder).classifyBatch(List.of(), null)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void classifyBatchNumbersEveryGoalInPrompt() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt().user(anyString()).call().entity(any(ParameterizedTypeReference.class)))
                .thenReturn(List.of());

        clearInvocations(chatClient.prompt());
        new TaxonomyService(builder).classifyBatch(List.of("first goal", "second goal"), null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClient.prompt()).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("1. first goal")
                .contains("2. second goal");
    }
}
