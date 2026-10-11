package de.tum.cit.hestia.learninggoalhub.extraction;

import de.tum.cit.hestia.learninggoalhub.llm.LenientJson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * The two model calls of a topic search that the extraction pipeline has no prompt for: turning a
 * typed topic into search terms, and telling which newly read outcomes the course already has.
 */
@Service
public class TopicSearchSynthesizer {

    static final String TERMS_PROMPT = """
            List the words and short phrases that lecture slides teaching the topic below would contain,
            so that those slides can be found by plain text search.

            Topic: %s

            - Return two to six terms: the topic's own name, its common synonyms and abbreviations, and
              the names of its central methods or concepts.
            - Slides often use a short form on its own. Include the distinctive head word of a
              multi-word name in its singular form ("tariff" for "import tariffs") when that
              word alone still points to this topic.
            - Write each term as it would appear on a slide, in %s. Add the English term as well when
              slides in that language commonly use it.
            - Each term is one to three words. Do not return sentences, and do not return generic words
              that appear on slides about any topic.

            Return only {"terms":["...","..."]}.
            """;

    static final String DUPLICATES_PROMPT = """
            Decide which newly extracted learning outcomes state the same outcome as an existing one.

            Two outcomes are the same when a student who achieves one has achieved the other: the same
            content at the same depth. Different wording, or a different verb for the same performance,
            does not make them different. An outcome that covers only part of an existing one, or goes
            beyond it, is not the same.

            New outcomes:
            ---
            %s
            ---

            Existing outcomes:
            ---
            %s
            ---

            Return only {"duplicates":[[0,2],[1,-1]]}.

            - Each pair is [new outcome index, existing outcome index], or -1 when no existing outcome is
              the same.
            - Every new outcome index appears exactly once.
            - Do not return explanations or additional fields.
            """;

    record Terms(List<String> terms) {}

    record Duplicates(List<List<Integer>> duplicates) {}

    private final ChatClient chatClient;
    private final String model;
    private final double temperature;

    public TopicSearchSynthesizer(
            ChatClient.Builder chatClientBuilder,
            @Value("${hestia.extraction.taxonomy-planner-model:openai-gpt-oss-120b}") String model,
            @Value("${hestia.extraction.assignment-temperature:0.0}") double temperature) {
        this.chatClient = chatClientBuilder.build();
        this.model = model;
        this.temperature = temperature;
    }

    /** Search terms for a topic, trimmed and without blanks. */
    public List<String> searchTerms(String topic, String languageName, String modelOverride) {
        Terms answer = call(TERMS_PROMPT.formatted(topic, languageName), modelOverride,
                new ParameterizedTypeReference<Terms>() {});
        List<String> terms = new ArrayList<>();
        if (answer != null && answer.terms() != null) {
            for (String term : answer.terms()) {
                if (term != null && !term.isBlank()) {
                    terms.add(term.strip());
                }
            }
        }
        return terms;
    }

    /**
     * For each new outcome, the index of the existing outcome it duplicates, or -1. Indices outside
     * the lists and repeated answers are ignored.
     */
    public List<Integer> duplicates(List<String> proposed, List<String> existing, String modelOverride) {
        Duplicates answer = call(DUPLICATES_PROMPT.formatted(numbered(proposed), numbered(existing)),
                modelOverride, new ParameterizedTypeReference<Duplicates>() {});
        return normalizeDuplicates(answer, proposed.size(), existing.size());
    }

    static List<Integer> normalizeDuplicates(Duplicates answer, int proposedCount, int existingCount) {
        List<Integer> result = new ArrayList<>(Collections.nCopies(proposedCount, -1));
        if (answer == null || answer.duplicates() == null) {
            return result;
        }
        Set<Integer> answered = new HashSet<>();
        for (List<Integer> pair : answer.duplicates()) {
            if (pair == null || pair.size() != 2 || pair.get(0) == null || pair.get(1) == null) {
                continue;
            }
            int index = pair.get(0);
            int existingIndex = pair.get(1);
            if (index < 0 || index >= proposedCount || !answered.add(index)) {
                continue;
            }
            if (existingIndex >= 0 && existingIndex < existingCount) {
                result.set(index, existingIndex);
            }
        }
        return result;
    }

    private <T> T call(String prompt, String modelOverride, ParameterizedTypeReference<T> type) {
        ChatOptions.Builder options = ChatOptions.builder().temperature(temperature);
        String effectiveModel = modelOverride == null || modelOverride.isBlank() ? model : modelOverride;
        if (effectiveModel != null && !effectiveModel.isBlank()) {
            options.model(effectiveModel);
        }
        return chatClient.prompt()
                .options(options.build())
                .user(prompt)
                .call()
                .entity(LenientJson.converter(type));
    }

    private static String numbered(List<String> items) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < items.size(); index++) {
            result.append('[').append(index).append("] ").append(items.get(index)).append('\n');
        }
        return result.toString();
    }
}
