package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Assigns every course goal to one of the already-named terminal competencies, in one course-wide
 * LLM call.
 *
 * <p>This is deliberately a <em>separate</em> call from {@link TerminalCompetencySynthesizer}. When
 * one call both invented the competencies and assigned the goals, a goal could only be placed among
 * the competencies written so far — so a goal whose proper competency was named later got committed
 * to whichever earlier competency was closest, and nothing could move it afterwards. Here the model
 * sees the finished list before it places anything.
 *
 * <p>Each skill carries its session/document label, but only as a TIE-BREAKER: terminal competencies
 * are supposed to cut ACROSS sessions, so clustering by document would defeat their purpose. The
 * prompt says so explicitly.
 *
 * <p>A goal that fits no competency is reported as such rather than force-fitted; the caller decides
 * what happens to those.
 */
@Service
public class CompetencyAssignmentSynthesizer {

    /** One goal offered for assignment: its text, Bloom level and session label (any may be null). */
    public record Candidate(String text, String bloomLevel, String session) {}

    static final String PROMPT = """
            You assign each of a course's SKILL goals to exactly ONE of its TERMINAL COMPETENCIES.
            The competencies are already fixed — do NOT rename, merge, split or add to them.

            Competencies, each prefixed with its index:
            ---
            %s
            ---

            Skill goals, each prefixed with its index, then its Bloom level in parentheses, then
            the session or document it came from in braces:
            ---
            %s
            ---

            Decide by CAPABILITY: put each goal under the competency it actually serves. A terminal
            competency is meant to cut ACROSS sessions, so goals from different sessions routinely
            belong to the same competency, and goals from the SAME session routinely belong to
            different ones. The session label is context for telling terse or near-duplicate goals
            apart — use it only to break a genuine tie, NEVER as the thing you group by. Grouping the
            goals by their session or document is the one clearly wrong answer.

            Lower-Bloom skills (REMEMBER, UNDERSTAND) belong under the competency whose capability they
            underpin, not under whichever competency mentions similar words.

            If a goal serves NONE of the listed competencies, return it with a null competencyIndex.
            Do not stretch a competency to cover a goal that plainly belongs to a different topic: a
            null is a better answer than a wrong parent. But use it sparingly — it means the
            competency list has a genuine hole, which is rare.

            Return one entry for EVERY goal index, with the shape {goalIndex, competencyIndex}, where
            competencyIndex is the index of its competency or null. Do not omit goals and do not
            return an index twice.
            """;

    private final ChatClient chatClient;

    public CompetencyAssignmentSynthesizer(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Assigns each candidate goal to one competency, or to none.
     *
     * @param competencies  the complete, already-filtered competency list; positions are the
     *                      {@code competencyIndex} values in the response.
     * @param goals         every goal to place; positions are the {@code goalIndex} values.
     * @param modelOverride optional SAIA model id; falls back to the configured default when blank.
     * @return for each goal index that the model answered for, its competency index or {@code null}.
     *         Out-of-range and duplicate answers are dropped, so a goal missing from the result is
     *         one the model did not place — the caller treats it like an explicit no-match.
     */
    public Map<Integer, Integer> assign(List<String> competencies, List<Candidate> goals,
                                        String modelOverride) {
        if (competencies == null || competencies.isEmpty() || goals == null || goals.isEmpty()) {
            return Map.of();
        }
        String prompt = PROMPT.formatted(numberedCompetencies(competencies), numberedGoals(goals));
        List<CompetencyAssignment> result = chat(prompt, modelOverride,
                new ParameterizedTypeReference<List<CompetencyAssignment>>() {});
        if (result == null) {
            return Map.of();
        }
        Map<Integer, Integer> byGoal = new LinkedHashMap<>();
        for (CompetencyAssignment assignment : result) {
            if (assignment == null
                    || assignment.goalIndex() < 0
                    || assignment.goalIndex() >= goals.size()
                    || byGoal.containsKey(assignment.goalIndex())) {
                continue;
            }
            Integer competencyIndex = assignment.competencyIndex();
            if (competencyIndex != null && (competencyIndex < 0 || competencyIndex >= competencies.size())) {
                // A hallucinated competency index is not a placement; treat it as "fits none" so the
                // goal reaches the caller's fallback instead of landing under an arbitrary parent.
                competencyIndex = null;
            }
            byGoal.put(assignment.goalIndex(), competencyIndex);
        }
        return byGoal;
    }

    private <T> T chat(String prompt, String modelOverride, ParameterizedTypeReference<T> type) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        if (modelOverride != null && !modelOverride.isBlank()) {
            spec = spec.options(ChatOptions.builder().model(modelOverride).build());
        }
        return spec.user(prompt).call().entity(type);
    }

    private static String numberedCompetencies(List<String> competencies) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < competencies.size(); i++) {
            sb.append('[').append(i).append("] ").append(competencies.get(i)).append('\n');
        }
        return sb.toString();
    }

    private static String numberedGoals(List<Candidate> goals) {
        List<String> lines = new ArrayList<>(goals.size());
        for (int i = 0; i < goals.size(); i++) {
            Candidate c = goals.get(i);
            String bloom = (c.bloomLevel() == null || c.bloomLevel().isBlank()) ? "?" : c.bloomLevel();
            String session = (c.session() == null || c.session().isBlank()) ? "?" : c.session();
            lines.add("[%d] (%s) {%s} %s".formatted(i, bloom, session, c.text()));
        }
        return String.join("\n", lines) + "\n";
    }
}
