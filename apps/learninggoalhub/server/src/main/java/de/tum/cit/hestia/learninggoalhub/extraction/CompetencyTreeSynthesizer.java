package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Builds the second layer of the competency tree beneath the {@link TerminalCompetencySynthesizer}'s
 * terminal competencies. The tree has a fixed three-tier shape — {@code terminal → sub-skill →
 * knowledge} — where every node except the gap nodes is an already-extracted, grounded goal; this
 * synthesiser only decides how those goals hang together and what knowledge is missing.
 *
 * <p>This is the second of two course-wide synthesis calls made by {@link ExtractionRunner}; it is
 * called once after terminal synthesis:
 *
 * <ul>
 *   <li><b>Expansion</b> ({@link #expandAll}) — one course-wide call: for every terminal competency
 *       that has sub-skills, attaches each knowledge goal under the sub-skill it supports and, since
 *       the lowest node must always be a knowledge aspect, names the knowledge a sub-skill needs but
 *       the material does not cover (the gap analysis).</li>
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

            Gaps are the EXCEPTION, not the rule. A well-taught competency yields FEW or ZERO gaps. Your
            default is to name none; only flag a gap when its absence would genuinely stop a learner from
            reaching the competency. Do NOT pad, do NOT enumerate every conceivable prerequisite, do NOT
            restate an attached goal. Quality over coverage: a handful of sharp gaps beats a long list.

            Every gap you name (a missing sub-skill or missing knowledge) MUST be a SINGLE, ATOMIC
            learning goal. This rule is STRICT and overrides any urge to be thorough in one line:
              - Exactly ONE action verb, and the goal starts with it. Never two verbs joined by "and"
                (e.g. NOT "diagnose ... and adjust ...").
              - Exactly ONE concept. NO lists — do not enumerate items with commas or "and"/"or"/"as
                well as", and do not append qualifier clauses ("..., explaining its properties,
                robustness, and cost").
              - Keep it short, roughly under twelve words.
            If a need spans several ideas, split it into several separate gaps, or keep only the single
            most essential one. Examples:
              BAD:  "Compare L1 and L2 loss, explaining their geometry, robustness to outliers, and cost."
              GOOD: "Explain why L1 loss is robust to outliers."
              BAD:  "Define accuracy, precision, recall, and F1-score."
              GOOD: "Define precision as a classification metric."
              BAD:  "Diagnose convergence issues and adjust optimization settings."
              GOOD: "Diagnose convergence failure in gradient descent."

            Do three things:

            1. ATTACH each knowledge goal under the ONE sub-skill it most directly underpins. Return a
               knowledge link {knowledgeIndex, subSkillIndex} for each. Attach EVERY knowledge goal to
               its best fit: these sub-skills are the only places it can live, so prefer the closest
               match over dropping it. Only omit a knowledge goal when it genuinely underpins none of
               these sub-skills — that should be the rare exception, not a convenient default.

            2. MISSING SUB-SKILLS. A competency is an APPLIED capability — students should be able to DO
               it, not merely know about it. Name a doing-capability the competency clearly requires that
               the course does NOT bring students to. This covers two cases:
                 (a) the material explains the underlying knowledge but never has students actually
                     perform the skill (theory taught, practice missing); or
                 (b) the capability is simply ABSENT from the material — neither taught nor practised —
                     yet the competency genuinely needs it.
               Phrase each as an applied learning goal starting with a doing verb (e.g. "Implement",
               "Design", "Configure", "Build", "Fit", "Tune"). Flag the clear gaps; do not invent
               contrived ones. Return each as a missingSubSkill {subSkill, knowledgeIndices,
               knowledgeGaps} that MUST bottom out in knowledge — supply at least one of (either or both,
               but never neither):
                 - knowledgeIndices: indices of the knowledge goals above that underpin it — use for
                   case (a), where the course taught the knowledge but never the doing; do NOT list an
                   index you already attached to a real sub-skill in step 1.
                 - knowledgeGaps: foundational knowledge it needs that is ALSO missing from the material
                   — use for case (b); each phrased as a recall/understanding learning goal (verbs:
                   Define, Describe, Explain, Identify, Distinguish, State).

            3. MISSING KNOWLEDGE. ONLY when a sub-skill's attached knowledge is so thin that a learner
               truly could not perform it, name the SINGLE most important foundational aspect that is
               missing. If the attached knowledge is adequate, return no gap for that sub-skill — most
               sub-skills need none. Never add a gap merely to give a sub-skill a child. Return each as a
               gap {subSkillIndex, knowledge} phrased as a recall/understanding learning goal (verbs:
               Define, Describe, Explain, Identify, Distinguish, State — NOT doing verbs; a doing need
               belongs in step 2). Examples: "Define the idempotency of an HTTP method", "Explain why a
               container image is signed".

            A competency the course fully covers yields no gaps at all — returning empty lists is the
            correct, expected answer in that case.

            Return one result for every competency, each with its competencyIndex (the C number),
            knowledge links, missing sub-skills and gaps. A result has the shape
            {competencyIndex, knowledge, missingSubSkills, gaps}. Use the local S/K index spaces shown
            for that competency.
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
