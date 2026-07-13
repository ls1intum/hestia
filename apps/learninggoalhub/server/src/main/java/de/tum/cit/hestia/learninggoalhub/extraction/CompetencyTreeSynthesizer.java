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
 * <p>Two passes, called in order by {@link ExtractionRunner}:
 *
 * <ul>
 *   <li><b>Assignment</b> ({@link #assign}) — course-wide, one call: routes every session/exercise
 *       goal to the single terminal competency it belongs under, giving the tree full coverage (the
 *       terminal clustering only saw the higher-Bloom goals and left its {@code supporting} hints
 *       sparse). The caller then splits each competency's goals by Bloom into sub-skills
 *       ({@code APPLY}/{@code CREATE}) and candidate knowledge (lower Bloom).</li>
 *   <li><b>Expansion</b> ({@link #expand}) — per competency: attaches each knowledge goal under the
 *       sub-skill it supports and, since the lowest node must always be a knowledge aspect, names the
 *       knowledge a sub-skill needs but the material does not cover (the gap analysis).</li>
 * </ul>
 */
@Service
public class CompetencyTreeSynthesizer {

    /** One candidate goal handed to a pass: its text and its Bloom level (may be null). */
    public record Candidate(String text, String bloomLevel) {}

    static final String ASSIGN_PROMPT = """
            You organise a course's learning goals into a competency tree. The course's TERMINAL
            COMPETENCIES — the broad applied capabilities a student gains — are already fixed and listed
            below, each prefixed with a competency index like [C0]. Beneath them are the course's
            individual learning goals, each prefixed with its own index in square brackets and its Bloom
            level in parentheses.

            Assign EVERY learning goal to the SINGLE terminal competency whose capability it most
            directly serves — a "doing" goal as a sub-skill of that competency, a lower-level
            "understand/remember/compare" goal as supporting knowledge for it. Judge by topic and
            capability, not by wording.

            Rules:
              - Assign each goal to exactly ONE competency: the best fit. Do not assign a goal to several.
              - Prefer to place a goal rather than discard it: most goals support some competency.
              - ONLY leave a goal unassigned (competency = -1) when it genuinely serves no competency —
                course administration, logistics, exam/submission mechanics, or one-off throwaway trivia.
              - Do NOT invent competencies; use only the indices listed.

            For every learning goal return its index (goal) and the chosen competency index (competency,
            or -1 if none).

            Terminal competencies:
            ---
            %s
            ---

            Learning goals:
            ---
            %s
            ---
            """;

    static final String EXPAND_PROMPT = """
            You arrange the goals of ONE terminal competency into a tree of shape
            sub-skill → knowledge. The competency is:

            %s

            Its SUB-SKILLS (the things a student does toward this competency) are listed first, each
            prefixed with an index like [S0]. Its candidate KNOWLEDGE goals (lower-level understanding
            that underpins those sub-skills) follow, each prefixed like [K0].

            Sub-skills:
            ---
            %s
            ---

            Knowledge goals:
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
               knowledge link {knowledgeIndex, subSkillIndex} for each. If a knowledge goal underpins
               none of these sub-skills, omit it.

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

            Return knowledge links, missing sub-skills and gaps.
            """;

    private final ChatClient chatClient;

    public CompetencyTreeSynthesizer(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Routes every candidate goal to the terminal competency it belongs under.
     *
     * @param competencies  the fixed terminal-competency texts; an assignment's {@code competency} is
     *                      a positional index into this list (or negative for "no competency").
     * @param candidates    every session/exercise goal, each with its Bloom level; an assignment's
     *                      {@code goal} is a positional index into this list.
     * @param modelOverride optional SAIA model id; falls back to the configured default when blank.
     * @return one assignment per goal the model placed; empty when there is nothing to assign.
     */
    public List<CompetencyAssignment> assign(List<String> competencies, List<Candidate> candidates,
                                             String modelOverride) {
        if (competencies == null || competencies.isEmpty() || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        String prompt = ASSIGN_PROMPT.formatted(numberedCompetencies(competencies), numbered(candidates));
        return chat(prompt, modelOverride, new ParameterizedTypeReference<List<CompetencyAssignment>>() {});
    }

    /**
     * Arranges one competency's sub-skills and knowledge into the {@code sub-skill → knowledge} tier
     * and names the knowledge missing beneath each sub-skill.
     *
     * @param competencyText the terminal competency being expanded.
     * @param subSkills      its {@code APPLY}/{@code CREATE} goals, in order; links index into this.
     * @param knowledge      its lower-Bloom candidate knowledge goals, in order; links index into this.
     * @param modelOverride  optional SAIA model id; falls back to the configured default when blank.
     * @return the knowledge attachments and gaps; never null.
     */
    public CompetencyExpansion expand(String competencyText, List<String> subSkills, List<String> knowledge,
                                      String modelOverride) {
        if (subSkills == null || subSkills.isEmpty()) {
            return new CompetencyExpansion(List.of(), List.of(), List.of());
        }
        String prompt = EXPAND_PROMPT.formatted(
                competencyText, numberedLines("S", subSkills), numberedLines("K", knowledge));
        CompetencyExpansion result = chat(prompt, modelOverride,
                new ParameterizedTypeReference<CompetencyExpansion>() {});
        return result == null ? new CompetencyExpansion(List.of(), List.of(), List.of()) : result;
    }

    private <T> T chat(String prompt, String modelOverride, ParameterizedTypeReference<T> type) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        if (modelOverride != null && !modelOverride.isBlank()) {
            spec = spec.options(ChatOptions.builder().model(modelOverride).build());
        }
        return spec.user(prompt).call().entity(type);
    }

    /** Numbers the candidate goals and labels each with its Bloom level so the model can cite them back. */
    private static String numbered(List<Candidate> candidates) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            String bloom = (c.bloomLevel() == null || c.bloomLevel().isBlank()) ? "?" : c.bloomLevel();
            sb.append('[').append(i).append("] (").append(bloom).append(") ").append(c.text()).append('\n');
        }
        return sb.toString();
    }

    /** Numbers the fixed terminal competencies as [C0], [C1], … for the assignment prompt. */
    private static String numberedCompetencies(List<String> competencies) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < competencies.size(); i++) {
            sb.append("[C").append(i).append("] ").append(competencies.get(i)).append('\n');
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
