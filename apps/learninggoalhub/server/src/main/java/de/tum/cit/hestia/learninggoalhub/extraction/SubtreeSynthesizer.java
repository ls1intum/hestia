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

/**
 * Generates the skill, sub-skill and knowledge tiers for one topic from its name alone. Nothing it
 * writes has a source in the course material.
 */
@Service
public class SubtreeSynthesizer {

    public static final int MAX_SKILLS = 5;

    private static final String CORRECTION_RETRY = """

            Your previous response violated the subtree shape or outcome-wording invariant.
            Regenerate the complete subtree with at most five skills, at least one sub-skill under every
            skill and at least one knowledge item under every sub-skill. Name every skill in base form,
            make every sub-skill and knowledge text an expanded action-noun phrase, and give every
            shortLabel a distinct compact action label.
            """;

    public record GeneratedSubtree(List<GeneratedSkill> skills) {
        public GeneratedSubtree {
            skills = skills == null ? List.of() : List.copyOf(skills);
        }
    }

    /** One skill under the topic: a base-form name, a compact label and the sub-skills making it up. */
    public record GeneratedSkill(String text, String shortLabel, List<GeneratedSubSkill> subSkills) {
        public GeneratedSkill {
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
            Build a competency subtree for the course topic below. Write every generated text and
            shortLabel in %s.

            The subtree has three levels beneath the topic:
            - skills: the parts of the topic that a student learns and is assessed on as one unit.
            - sub-skills: the narrower assessable actions that make up one skill.
            - knowledge: the declarative knowledge that one sub-skill relies on.

            The shape is:
            {"skills":[{"text":"...","shortLabel":"...","subSkills":[{"text":"...","shortLabel":"...","knowledge":[{"text":"...","shortLabel":"..."}]}]}]}

            SKILLS
              - Create between one and five skills that together cover the topic. This is a hard
                maximum: never return more than five.
              - Name each skill as one sentence of at most twelve words stating what the student can
                do, starting with the action in base form ("Explain ...", "Compute ...", "Compare ...").
              - Its shortLabel is two to six words naming the action and what it acts on, reusing the
                verb of the name, such as "Compute the cost of capital".
              - Do not repeat the topic itself as a skill.

            SUB-SKILLS AND KNOWLEDGE
              - Give every skill between one and four sub-skills, each narrower than its skill.
              - Give every sub-skill at least one knowledge item.
              - Every sub-skill and knowledge text is an expanded action-noun outcome; every
                shortLabel is a compact 2-6 word label naming the action and its topic, reusing that
                text's verb (e.g. "Analyse the bias-variance tradeoff"; German puts the infinitive
                last: "Bias-Varianz-Abwägung analysieren"), not ending with a period.

            Rules:
              - Keep all texts concise and distinct. Do not add content the topic does not cover, and
                do not repeat wording across levels.
              - Return only the structured JSON object.

            Topic:
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

    public GeneratedSubtree generateSubtree(String topicText, String languageName, String modelOverride) {
        String prompt = PROMPT.formatted(languageName, topicText);
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
        if (generated == null || generated.skills().isEmpty()) {
            throw new IllegalArgumentException("Generated subtree must contain at least one skill");
        }
        if (generated.skills().size() > MAX_SKILLS) {
            throw new IllegalArgumentException("Generated subtree must not contain more than five skills");
        }

        Set<String> nodeTexts = new HashSet<>();
        List<GeneratedSkill> validSkills = new ArrayList<>();
        for (GeneratedSkill skill : generated.skills()) {
            if (skill == null || skill.text() == null || skill.text().isBlank()) {
                throw new IllegalArgumentException("Generated subtree contains a blank skill");
            }
            requireDistinct(nodeTexts, skill.text());
            if (skill.subSkills().isEmpty()) {
                throw new IllegalArgumentException("Every generated skill needs sub-skills");
            }
            List<GeneratedSubSkill> subSkills = new ArrayList<>();
            for (GeneratedSubSkill subSkill : skill.subSkills()) {
                if (subSkill == null || subSkill.text() == null || subSkill.text().isBlank()) {
                    throw new IllegalArgumentException("Generated subtree contains a blank sub-skill");
                }
                requireDistinct(nodeTexts, subSkill.text());
                if (subSkill.knowledge().isEmpty()) {
                    throw new IllegalArgumentException("Every generated sub-skill needs knowledge items");
                }
                List<GeneratedKnowledge> knowledge = new ArrayList<>();
                for (GeneratedKnowledge item : subSkill.knowledge()) {
                    if (item == null || item.text() == null || item.text().isBlank()) {
                        throw new IllegalArgumentException("Generated subtree contains blank knowledge");
                    }
                    requireDistinct(nodeTexts, item.text());
                    knowledge.add(new GeneratedKnowledge(item.text().strip(), blankToNull(item.shortLabel())));
                }
                subSkills.add(new GeneratedSubSkill(subSkill.text().strip(), blankToNull(subSkill.shortLabel()),
                        knowledge));
            }
            validSkills.add(new GeneratedSkill(skill.text().strip(), blankToNull(skill.shortLabel()), subSkills));
        }
        return new GeneratedSubtree(validSkills);
    }

    /**
     * Also applies the outcome wording to sub-skills and knowledge. Skills are exempt: like the
     * capabilities the pipeline names, they are base-form sentences rather than action-noun phrases.
     */
    static GeneratedSubtree validate(GeneratedSubtree generated, String languageName) {
        GeneratedSubtree valid = validate(generated);
        for (GeneratedSkill skill : valid.skills()) {
            for (GeneratedSubSkill subSkill : skill.subSkills()) {
                OutcomeWording.validate(subSkill.text(), subSkill.shortLabel(), languageName,
                        "Every generated sub-skill");
                for (GeneratedKnowledge knowledge : subSkill.knowledge()) {
                    OutcomeWording.validate(knowledge.text(), knowledge.shortLabel(), languageName,
                            "Every generated knowledge item");
                }
            }
        }
        return valid;
    }

    private static void requireDistinct(Set<String> nodeTexts, String text) {
        if (!nodeTexts.add(normalized(text))) {
            throw new IllegalArgumentException("Generated subtree contains duplicate node text");
        }
    }

    private static String normalized(String text) {
        return text.strip().toLowerCase(Locale.ROOT);
    }

    /** shortLabel is best-effort: a missing one falls back to the full text on display, not an error. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
