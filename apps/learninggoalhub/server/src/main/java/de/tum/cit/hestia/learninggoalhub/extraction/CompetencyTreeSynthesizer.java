package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Builds the second layer of the competency tree beneath the {@link TerminalCompetencySynthesizer}'s
 * terminal competencies. The tree has a fixed three-tier shape — {@code terminal → sub-skill →
 * knowledge} — where every node is an already-extracted, grounded goal; this synthesiser only
 * decides how those goals hang together.
 *
 * <p>This is the second of two course-wide synthesis calls made by {@link ExtractionRunner}; it is
 * called once after terminal synthesis:
 *
 * <ul>
 *   <li><b>Expansion</b> ({@link #expandAll}) — one course-wide call: for every terminal competency
 *       that has sub-skills, attaches each knowledge goal under the sub-skill it supports.</li>
 * </ul>
 */
@Service
public class CompetencyTreeSynthesizer {

    static final String EXPAND_PROMPT = """
            You arrange the goals of ALL listed terminal competencies into independent trees of shape
            sub-skill → knowledge. Process each competency separately. Competencies are prefixed with
            an index like [C0]. Within EACH competency, its SUB-SKILLS (the things a student does
            toward it) are prefixed with local indices like [S0], and its candidate KNOWLEDGE goals
            (lower-level understanding that underpins those sub-skills) are prefixed with local indices
            like [K0]. Do not use indices from one competency for another.

            Competencies:
            ---
            %s
            ---

            ATTACH each knowledge goal under the ONE sub-skill it most directly underpins. Return a
            knowledge link {knowledgeIndex, subSkillIndex} for each. Attach EVERY knowledge goal to
            its best fit: these sub-skills are the only places it can live, so prefer the closest
            match over dropping it. Only omit a knowledge goal when it genuinely underpins none of
            these sub-skills — that should be the rare exception, not a convenient default.

            Return one result for every competency, each with its competencyIndex (the C number) and
            knowledge links. A result has the shape {competencyIndex, knowledge}. Use the local S/K
            index spaces shown for that competency.
            """;

    private final ChatClient chatClient;

    public CompetencyTreeSynthesizer(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /** One terminal competency's local input for the course-wide expansion call. */
    public record ExpansionInput(String text, List<String> subSkills, List<String> knowledge) {
        public ExpansionInput {
            subSkills = subSkills == null ? List.of() : List.copyOf(subSkills);
            knowledge = knowledge == null ? List.of() : List.copyOf(knowledge);
        }
    }

    /**
     * Expands all terminal competencies that have sub-skills in one course-wide LLM call.
     *
     * @param competencies terminal competencies with local sub-skill and knowledge lists; their
     *                     position is the {@code competencyIndex} used in the response.
     * @param modelOverride optional SAIA model id; falls back to the configured default when blank.
     * @return one mapped expansion per response item; missing competency indices are handled by the
     *         caller as failed expansions.
     */
    public List<CompetencyExpansion> expandAll(List<ExpansionInput> competencies, String modelOverride) {
        if (competencies == null || competencies.isEmpty()) {
            return List.of();
        }
        String prompt = EXPAND_PROMPT.formatted(numberedExpansionInputs(competencies));
        List<CompetencyExpansion> result = chat(prompt, modelOverride,
                new ParameterizedTypeReference<List<CompetencyExpansion>>() {});
        if (result == null) {
            return List.of();
        }
        return result.stream()
                .filter(expansion -> expansion != null
                        && expansion.competencyIndex() >= 0
                        && expansion.competencyIndex() < competencies.size())
                .toList();
    }

    private <T> T chat(String prompt, String modelOverride, ParameterizedTypeReference<T> type) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        if (modelOverride != null && !modelOverride.isBlank()) {
            spec = spec.options(ChatOptions.builder().model(modelOverride).build());
        }
        return spec.user(prompt).call().entity(type);
    }

    /** Numbers each competency and its local S/K lists for the course-wide expansion prompt. */
    private static String numberedExpansionInputs(List<ExpansionInput> competencies) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < competencies.size(); i++) {
            ExpansionInput input = competencies.get(i);
            sb.append("[C").append(i).append("] ").append(input.text()).append('\n');
            sb.append("Sub-skills:\n---\n")
                    .append(numberedLines("S", input.subSkills()))
                    .append("---\nKnowledge goals:\n---\n")
                    .append(numberedLines("K", input.knowledge()))
                    .append("---\n\n");
        }
        return sb.toString();
    }

    /** Numbers a plain text list with a single-letter prefix, e.g. [S0], [K0]. */
    private static String numberedLines(String prefix, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "(none)\n";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append('[').append(prefix).append(i).append("] ").append(lines.get(i)).append('\n');
        }
        return sb.toString();
    }
}
