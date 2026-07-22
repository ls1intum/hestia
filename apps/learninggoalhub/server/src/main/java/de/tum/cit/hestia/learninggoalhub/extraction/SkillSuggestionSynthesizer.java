package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
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

            Write every generated text and shortLabel in %s. Return a JSON array with 2 to 4 objects,
            using exactly the properties text and shortLabel. The shortLabel is a concise 2-5 word noun
            phrase naming the topic, without a leading verb or final period.

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

    private final ChatClient chatClient;

    public SkillSuggestionSynthesizer(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public List<Suggestion> suggest(List<String> existingTerminalSkills, List<Evidence> evidence,
                                    String languageName, String modelOverride) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        return call(PROMPT.formatted(languageName, numberedSkills(existingTerminalSkills), numberedEvidence(evidence)),
                modelOverride);
    }

    private List<Suggestion> call(String prompt, String modelOverride) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        if (modelOverride != null && !modelOverride.isBlank()) {
            spec = spec.options(ChatOptions.builder().model(modelOverride).build());
        }
        List<Suggestion> suggestions = spec
                .user(prompt)
                .call()
                .entity(new ParameterizedTypeReference<List<Suggestion>>() {});
        return suggestions == null ? List.of() : suggestions;
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
