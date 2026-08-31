package de.tum.cit.hestia.learninggoalhub.extraction;

import de.tum.cit.hestia.learninggoalhub.llm.LenientJson;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/** Generates the sub-skill and knowledge tiers for one accepted terminal skill. */
@Service
public class SubtreeSynthesizer {

    private static final String CORRECTION_RETRY = """

            Your previous response violated the outcome-wording invariant. Regenerate the complete
            subtree. Make every text an expanded action-noun phrase and every shortLabel a distinct
            compact action label.
            """;

    public record GeneratedSubtree(List<GeneratedSubSkill> subSkills) {
        public GeneratedSubtree {
            subSkills = subSkills == null ? List.of() : List.copyOf(subSkills);
        }
    }

    public record GeneratedSubSkill(String text, String shortLabel, List<GeneratedKnowledge> knowledge) {
        public GeneratedSubSkill {
            knowledge = knowledge == null ? List.of() : List.copyOf(knowledge);
        }
    }

    /** One knowledge leaf: its full text plus a compact verb-phrase label, mirroring pipeline goals. */
    public record GeneratedKnowledge(String text, String shortLabel) {}

    static final String PROMPT = """
            Build a complete three-level competency subtree for the accepted terminal competency below.
            Write every generated text and shortLabel in %s. Return exactly one JSON object with a
            property subSkills. Each subSkills item must have a concise text describing one distinct
            applied sub-skill, a shortLabel naming it, and a non-empty knowledge array containing the
            declarative knowledge that supports that sub-skill.

            Every text is an expanded action-noun outcome; every shortLabel is a compact 2-6 word label
            naming the action and its topic, reusing that text's verb (e.g. "Analyse the bias-variance
            tradeoff"; German puts the infinitive last: "Bias-Varianz-Abwägung analysieren"), not
            ending with a period.

            The shape is:
            {"subSkills":[{"text":"...","shortLabel":"...","knowledge":[{"text":"...","shortLabel":"..."}]}]}

            Rules:
              - Create several meaningful sub-skills that together cover the terminal competency.
              - Every sub-skill must have at least one knowledge item.
              - Keep sub-skill and knowledge texts concise, distinct, and grounded in the terminal
                competency. Do not add unrelated topics or duplicate wording.
              - Do not include the terminal competency itself as a sub-skill.
              - Return only the structured JSON object.

            Accepted terminal competency:
            ---
            %s
            ---
            """;

    private final ChatClient chatClient;
    private final double temperature;

    public SubtreeSynthesizer(ChatClient.Builder chatClientBuilder,
                              @Value("${hestia.extraction.temperature:0.2}") double temperature) {
        this.chatClient = chatClientBuilder.build();
        this.temperature = temperature;
    }

    public GeneratedSubtree generateSubtree(String terminalText, String languageName, String modelOverride) {
        String prompt = PROMPT.formatted(languageName, terminalText);
        GeneratedSubtree generated = call(prompt, languageName, modelOverride, temperature);
        try {
            return validate(generated, languageName);
        } catch (IllegalArgumentException invalidResponse) {
            generated = call(prompt + CORRECTION_RETRY, languageName, modelOverride, 0.0);
            return validate(generated, languageName);
        }
    }

    private GeneratedSubtree call(String prompt, String languageName, String modelOverride, double callTemperature) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .system(LanguagePrompt.systemInstruction(languageName));
        ChatOptions.Builder options = ChatOptions.builder().temperature(callTemperature);
        if (modelOverride != null && !modelOverride.isBlank()) {
            options.model(modelOverride);
        }
        GeneratedSubtree generated = spec
                .options(options.build())
                .user(prompt)
                .call()
                .entity(LenientJson.converter(new ParameterizedTypeReference<GeneratedSubtree>() {}));
        return generated;
    }

    /** Validates a model response before any database node is created. */
    public static GeneratedSubtree validate(GeneratedSubtree generated) {
        if (generated == null || generated.subSkills().isEmpty()) {
            throw new IllegalArgumentException("Generated subtree must contain at least one sub-skill");
        }

        Set<String> nodeTexts = new HashSet<>();
        List<GeneratedSubSkill> validSubSkills = new ArrayList<>();
        for (GeneratedSubSkill subSkill : generated.subSkills()) {
            if (subSkill == null || subSkill.text() == null || subSkill.text().isBlank()) {
                throw new IllegalArgumentException("Generated subtree contains a blank sub-skill");
            }
            if (!nodeTexts.add(normalized(subSkill.text()))) {
                throw new IllegalArgumentException("Generated subtree contains duplicate node text");
            }
            if (subSkill.knowledge().isEmpty()) {
                throw new IllegalArgumentException("Every generated sub-skill needs knowledge items");
            }
            List<GeneratedKnowledge> knowledge = new ArrayList<>();
            for (GeneratedKnowledge item : subSkill.knowledge()) {
                if (item == null || item.text() == null || item.text().isBlank()) {
                    throw new IllegalArgumentException("Generated subtree contains blank knowledge");
                }
                if (!nodeTexts.add(normalized(item.text()))) {
                    throw new IllegalArgumentException("Generated subtree contains duplicate node text");
                }
                knowledge.add(new GeneratedKnowledge(item.text().strip(), blankToNull(item.shortLabel())));
            }
            validSubSkills.add(new GeneratedSubSkill(subSkill.text().strip(), blankToNull(subSkill.shortLabel()), knowledge));
        }
        return new GeneratedSubtree(validSubSkills);
    }

    static GeneratedSubtree validate(GeneratedSubtree generated, String languageName) {
        GeneratedSubtree valid = validate(generated);
        for (GeneratedSubSkill subSkill : valid.subSkills()) {
            OutcomeWording.validate(subSkill.text(), subSkill.shortLabel(), languageName,
                    "Every generated sub-skill");
            for (GeneratedKnowledge knowledge : subSkill.knowledge()) {
                OutcomeWording.validate(knowledge.text(), knowledge.shortLabel(), languageName,
                        "Every generated knowledge item");
            }
        }
        return valid;
    }

    private static String normalized(String text) {
        return text.strip().toLowerCase(Locale.ROOT);
    }

    /** shortLabel is best-effort: a missing one falls back to the full text on display, not an error. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
