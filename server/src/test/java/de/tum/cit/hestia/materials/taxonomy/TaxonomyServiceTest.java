package de.tum.cit.hestia.materials.taxonomy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.hestia.materials.llm.FakeLlm;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaxonomyServiceTest {

    @Test
    void returnsParsedClassificationFromTheModel() {
        TaxonomyClassification expected = new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL);
        FakeLlm llm = FakeLlm.replying(expected);

        TaxonomyService service = new TaxonomyService(llm.client(), 0.0);

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
        FakeLlm llm = FakeLlm.replying(new TaxonomyClassification(BloomLevel.REMEMBER, SoloLevel.UNISTRUCTURAL));

        new TaxonomyService(llm.client(), 0.0).classify("UNIQUE-GOAL-MARKER-77");

        assertThat(llm.requests()).hasSize(1);
        assertThat(llm.prompt()).contains("UNIQUE-GOAL-MARKER-77");
    }

    @Test
    void appliesModelOverrideWhenProvided() {
        FakeLlm llm = FakeLlm.replying(new TaxonomyClassification(BloomLevel.ANALYZE, SoloLevel.MULTISTRUCTURAL));

        new TaxonomyService(llm.client(), 0.0).classify("goal text", "qwen3.6-35b-a3b");

        assertThat(llm.only().model().id()).isEqualTo("qwen3.6-35b-a3b");
    }

    @Test
    @SuppressWarnings("unchecked")
    void classifyBatchAlignsResultsToGoalsByIndex() {
        // Returned out of order and missing the goal at index 2 to prove index-based alignment.
        FakeLlm llm = FakeLlm.replying(List.of(
                        new BatchTaxonomyItem(3, BloomLevel.ANALYZE, SoloLevel.RELATIONAL),
                        new BatchTaxonomyItem(1, BloomLevel.REMEMBER, SoloLevel.UNISTRUCTURAL)));

        List<TaxonomyClassification> result = new TaxonomyService(llm.client(), 0.0)
                .classifyBatch(List.of("goal one", "goal two", "goal three"), null);

        assertThat(result).hasSize(3);
        assertThat(result.get(0)).isEqualTo(new TaxonomyClassification(BloomLevel.REMEMBER, SoloLevel.UNISTRUCTURAL));
        assertThat(result.get(1)).isNull();
        assertThat(result.get(2)).isEqualTo(new TaxonomyClassification(BloomLevel.ANALYZE, SoloLevel.RELATIONAL));
    }

    @Test
    void classifyBatchReturnsEmptyForEmptyInput() {
        FakeLlm llm = FakeLlm.replying();

        assertThat(new TaxonomyService(llm.client(), 0.0).classifyBatch(List.of(), null)).isEmpty();
        assertThat(llm.requests()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void classifyBatchNumbersEveryGoalInPrompt() {
        FakeLlm llm = FakeLlm.replying(List.of());

        new TaxonomyService(llm.client(), 0.0).classifyBatch(List.of("first goal", "second goal"), null);

        assertThat(llm.requests()).hasSize(1);
        assertThat(llm.prompt())
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
