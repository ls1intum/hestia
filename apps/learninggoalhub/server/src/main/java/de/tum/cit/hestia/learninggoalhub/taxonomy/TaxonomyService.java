package de.tum.cit.hestia.learninggoalhub.taxonomy;

import java.util.Arrays;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

@Service
public class TaxonomyService {

    static final String PROMPT_TEMPLATE = """
            You classify a single learning goal along two well-known educational taxonomies.

            Bloom's revised taxonomy (cognitive process the goal targets):
              - REMEMBER: recall facts and basic concepts.
              - UNDERSTAND: explain ideas or concepts.
              - APPLY: use information in new situations.
              - ANALYZE: draw connections among ideas; break material into parts.
              - EVALUATE: justify a stance or decision.
              - CREATE: produce new or original work.

            SOLO taxonomy (structure of the observed learning outcome):
              - PRESTRUCTURAL: misses the point.
              - UNISTRUCTURAL: identifies one relevant aspect.
              - MULTISTRUCTURAL: identifies several relevant aspects without integrating them.
              - RELATIONAL: integrates aspects into a coherent whole.
              - EXTENDED_ABSTRACT: generalises beyond the given context.

            Pick exactly one Bloom level and one SOLO level that best describe the goal.
            Return them as the JSON fields `bloom` and `solo`, using the enum names above.

            Learning goal:
            ---
            %s
            ---
            """;

    static final String BATCH_PROMPT_TEMPLATE = """
            You classify learning goals along two well-known educational taxonomies.

            Bloom's revised taxonomy (cognitive process the goal targets):
              - REMEMBER: recall facts and basic concepts.
              - UNDERSTAND: explain ideas or concepts.
              - APPLY: use information in new situations.
              - ANALYZE: draw connections among ideas; break material into parts.
              - EVALUATE: justify a stance or decision.
              - CREATE: produce new or original work.

            SOLO taxonomy (structure of the observed learning outcome):
              - PRESTRUCTURAL: misses the point.
              - UNISTRUCTURAL: identifies one relevant aspect.
              - MULTISTRUCTURAL: identifies several relevant aspects without integrating them.
              - RELATIONAL: integrates aspects into a coherent whole.
              - EXTENDED_ABSTRACT: generalises beyond the given context.

            For EACH numbered learning goal below, pick exactly one Bloom level and one SOLO level
            that best describe it, using the enum names above. Return one JSON object per goal with
            the fields `index` (the goal's number, copied from the list), `bloom` and `solo`. Return
            exactly one object for every goal in the list and keep its number in `index`.

            Learning goals:
            ---
            %s
            ---
            """;

    private final ChatClient chatClient;

    public TaxonomyService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public TaxonomyClassification classify(String goalText) {
        return classify(goalText, null);
    }

    public TaxonomyClassification classify(String goalText, String modelOverride) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        if (modelOverride != null && !modelOverride.isBlank()) {
            spec = spec.options(ChatOptions.builder().model(modelOverride).build());
        }
        return spec
                .user(PROMPT_TEMPLATE.formatted(goalText))
                .call()
                .entity(TaxonomyClassification.class);
    }

    /**
     * Classifies several goals in a single LLM call. Returns a list aligned to {@code goalTexts}:
     * entry {@code i} is the classification for {@code goalTexts.get(i)}, or null when the model did
     * not return a usable Bloom+SOLO pair for that goal. The goals are numbered in the prompt and
     * mapped back by the index the model echoes, so a reordered, partial or padded answer still lands
     * on the right goal. One call per batch instead of one per goal cuts request count (and rate-limit
     * pressure) and lets the model grade goals relative to each other.
     *
     * @param goalTexts     the goals to classify, in order; an empty list yields an empty result.
     * @param modelOverride optional SAIA model id; falls back to the configured default when blank.
     */
    public List<TaxonomyClassification> classifyBatch(List<String> goalTexts, String modelOverride) {
        if (goalTexts == null || goalTexts.isEmpty()) {
            return List.of();
        }
        StringBuilder numbered = new StringBuilder();
        for (int i = 0; i < goalTexts.size(); i++) {
            numbered.append(i + 1).append(". ").append(goalTexts.get(i)).append('\n');
        }
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        if (modelOverride != null && !modelOverride.isBlank()) {
            spec = spec.options(ChatOptions.builder().model(modelOverride).build());
        }
        List<BatchTaxonomyItem> items = spec
                .user(BATCH_PROMPT_TEMPLATE.formatted(numbered.toString()))
                .call()
                .entity(new ParameterizedTypeReference<List<BatchTaxonomyItem>>() {});

        TaxonomyClassification[] aligned = new TaxonomyClassification[goalTexts.size()];
        if (items != null) {
            for (BatchTaxonomyItem item : items) {
                int i = item.index() - 1;
                if (i >= 0 && i < aligned.length && item.bloom() != null && item.solo() != null) {
                    aligned[i] = new TaxonomyClassification(item.bloom(), item.solo());
                }
            }
        }
        return Arrays.asList(aligned);
    }
}
