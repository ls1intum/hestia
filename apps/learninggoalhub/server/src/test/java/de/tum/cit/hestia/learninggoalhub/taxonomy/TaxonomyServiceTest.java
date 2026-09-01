package de.tum.cit.hestia.learninggoalhub.taxonomy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.springframework.ai.converter.StructuredOutputConverter;

class TaxonomyServiceTest {

    /**
     * The request spec returns itself from options() so the whole call chain is one mock.
     * Rebuilding the chain inside a verify() would otherwise be recorded as another invocation
     * and make every count off by one.
     */
    private static ChatClient.ChatClientRequestSpec stubSpec(ChatClient chatClient) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        when(spec.options(any(ChatOptions.class))).thenReturn(spec);
        return spec;
    }

    @Test
    void returnsParsedClassificationFromChatClient() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);

        TaxonomyClassification expected = new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL);
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(expected);

        TaxonomyService service = new TaxonomyService(builder, 0.0);

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
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(new TaxonomyClassification(BloomLevel.REMEMBER, SoloLevel.UNISTRUCTURAL));

        clearInvocations(spec);
        new TaxonomyService(builder, 0.0).classify("UNIQUE-GOAL-MARKER-77");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(spec).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("UNIQUE-GOAL-MARKER-77");
    }

    @Test
    void appliesModelOverrideWhenProvided() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(new TaxonomyClassification(BloomLevel.ANALYZE, SoloLevel.MULTISTRUCTURAL));

        clearInvocations(spec);
        new TaxonomyService(builder, 0.0).classify("goal text", "qwen3.6-35b-a3b");

        ArgumentCaptor<ChatOptions> optionsCaptor = ArgumentCaptor.forClass(ChatOptions.class);
        verify(spec).options(optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getModel()).isEqualTo("qwen3.6-35b-a3b");
    }

    @Test
    @SuppressWarnings("unchecked")
    void classifyBatchAlignsResultsToGoalsByIndex() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);

        // Returned out of order and missing the goal at index 2 to prove index-based alignment.
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of(
                        new BatchTaxonomyItem(3, BloomLevel.ANALYZE, SoloLevel.RELATIONAL),
                        new BatchTaxonomyItem(1, BloomLevel.REMEMBER, SoloLevel.UNISTRUCTURAL)));

        List<TaxonomyClassification> result = new TaxonomyService(builder, 0.0)
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

        assertThat(new TaxonomyService(builder, 0.0).classifyBatch(List.of(), null)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void classifyBatchNumbersEveryGoalInPrompt() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of());

        clearInvocations(spec);
        new TaxonomyService(builder, 0.0).classifyBatch(List.of("first goal", "second goal"), null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(spec).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("1. first goal")
                .contains("2. second goal");
    }

    /**
     * Bloom belongs to the verb and SOLO to the whole statement. Left to "best describe it", the
     * classifier read the level off a goal's tail clause instead — goals came back at ANALYZE while
     * their own verb said "Understanding", overriding a verb that extraction had chosen with the
     * source material in view and an explicit rule against escalating.
     */
    @Test
    void promptTakesBloomFromTheVerbAndSoloFromTheWholeStatement() {
        for (String prompt : List.of(TaxonomyService.PROMPT_TEMPLATE, TaxonomyService.BATCH_PROMPT_TEMPLATE)) {
            assertThat(prompt)
                    .contains("Bloom is carried by the goal's VERB, and only by the verb.")
                    .contains("NOT re-derive the level from the rest of the sentence")
                    .contains("a long goal is not a higher one")
                    // SOLO must keep reading everything, or the two taxonomies collapse into one.
                    .contains("it describes how the whole statement is structured, so read all of")
                    .contains("RELATIONAL on SOLO");
        }
    }
}
