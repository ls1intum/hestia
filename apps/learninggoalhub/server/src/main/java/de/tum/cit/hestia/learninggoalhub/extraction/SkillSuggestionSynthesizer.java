package de.tum.cit.hestia.learninggoalhub.extraction;

import de.tum.cit.hestia.learninggoalhub.llm.LenientJson;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/** Suggests broad terminal skills that are not already covered by the course's terminal skills. */
@Service
public class SkillSuggestionSynthesizer {

    /** Grounding supplied by one already-extracted session/exercise goal. */
    public record Evidence(String text, String bloomLevel, String sourceSnippet) {
    }

    /** One transient suggestion shown to the instructor before acceptance. */
    public record Suggestion(String text, String shortLabel) {
    }

    static final String PROMPT = """
            You suggest missing TERMINAL COMPETENCIES for a course. A terminal competency is a broad,
            applied capability a student should be able to perform after completing the course, stated
            with one concrete action verb. Suggest only capabilities genuinely supported by the evidence.

            Word each text as ONE expanded action-noun phrase matching the voice of the existing
            terminal skills listed below. Never open with a subject clause such as "Der Studierende
            kann" or "Students can", and do not chain actions with "and" or commas.

            Write every generated text and shortLabel in %s. Return a JSON array with 2 to 4 objects,
            using exactly the properties text and shortLabel. The shortLabel is a compact 2-6 word label
            naming the action and its topic, reusing the text's action verb (e.g. "Analyse the
            bias-variance tradeoff"; German puts the infinitive last: "Bias-Varianz-Abwägung
            analysieren"), without a final period.

            Existing terminal skills are already covered. Do NOT repeat them, paraphrase them, or suggest
            a narrower version of one of them:
            ---
            %s
            ---

            The following extracted session/exercise goals are the course evidence. Each item includes its
            Bloom level and, where available, a short source snippet. Use the evidence to identify a
            meaningful broad capability that is missing from the existing terminal skills. Do not invent
            topics absent from this evidence and do not suggest individual facts, tools, or one-off tasks.
            ---
            %s
            ---

            Return only the structured JSON array. If the evidence does not support a genuinely missing
            capability, return an empty array.
            """;

    private static final String WORDING_RETRY = """

            Your previous response violated the outcome-wording invariant. Regenerate the COMPLETE
            suggestion list with expanded action-noun text and a distinct compact shortLabel for
            every suggestion.
            """;

    private final ChatClient chatClient;
    private final double temperature;

    public SkillSuggestionSynthesizer(ChatClient.Builder chatClientBuilder,
                                      @Value("${hestia.extraction.temperature:0.2}") double temperature) {
        this.chatClient = chatClientBuilder.build();
        this.temperature = temperature;
    }

    public List<Suggestion> suggest(List<String> existingTerminalSkills, List<Evidence> evidence,
                                    String languageName, String modelOverride) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        String prompt = PROMPT.formatted(
                languageName, numberedSkills(existingTerminalSkills), numberedEvidence(evidence));
        try {
            return validate(call(prompt, languageName, modelOverride, temperature), languageName);
        } catch (IllegalArgumentException invalidResponse) {
            return validate(call(prompt + WORDING_RETRY, languageName, modelOverride, 0.0), languageName);
        }
    }

    private List<Suggestion> call(String prompt, String languageName, String modelOverride,
                                  double callTemperature) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .system(LanguagePrompt.systemInstruction(languageName));
        ChatOptions.Builder options = ChatOptions.builder().temperature(callTemperature);
        if (modelOverride != null && !modelOverride.isBlank()) {
            options.model(modelOverride);
        }
        List<Suggestion> suggestions = spec
                .options(options.build())
                .user(prompt)
                .call()
                .entity(LenientJson.converter(new ParameterizedTypeReference<List<Suggestion>>() {}));
        return suggestions == null ? List.of() : suggestions;
    }

    private static List<Suggestion> validate(List<Suggestion> suggestions, String languageName) {
        return suggestions.stream().map(suggestion -> {
            if (suggestion == null) {
                throw new IllegalArgumentException("Every skill suggestion must be present");
            }
            OutcomeWording.validate(suggestion.text(), suggestion.shortLabel(), languageName,
                    "Every skill suggestion");
            return new Suggestion(suggestion.text().strip(), suggestion.shortLabel().strip());
        }).toList();
    }

    private static String numberedSkills(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "(none)\n";
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            result.append('[').append(i + 1).append("] ").append(values.get(i)).append('\n');
        }
        return result.toString();
    }

    private static String numberedEvidence(List<Evidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return "(none)\n";
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < evidence.size(); i++) {
            Evidence item = evidence.get(i);
            result.append('[').append(i + 1).append("] (")
                    .append(item.bloomLevel() == null || item.bloomLevel().isBlank() ? "?" : item.bloomLevel())
                    .append(") ").append(item.text());
            if (item.sourceSnippet() != null && !item.sourceSnippet().isBlank()) {
                result.append(" | source: ").append(item.sourceSnippet());
            }
            result.append('\n');
        }
        return result.toString();
    }
}
