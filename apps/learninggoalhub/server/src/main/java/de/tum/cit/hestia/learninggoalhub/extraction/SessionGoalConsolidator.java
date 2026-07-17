package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Consolidates the fine-grained candidate goals extracted from a single session's chunks into the
 * few broad outcomes the session as a whole teaches. This is the second stage of the two-stage
 * extraction: the per-chunk pass ({@link ExtractionService}) over-produces narrow candidates from
 * 8000-character windows, and this stage — seeing every candidate of one session at once together
 * with the session title — merges overlapping and narrow candidates into the handful of broad,
 * lecture-level outcomes an instructor would actually state.
 *
 * <p>It returns each outcome's {@code text} plus the indices of the candidates it was merged from, so
 * {@link ExtractionRunner} can derive the outcome's kind and source snippet and record the
 * candidate→goal provenance.
 */
@Service
public class SessionGoalConsolidator {

    static final String PROMPT_TEMPLATE = """
            You consolidate the learning goals of a single session (a lecture, chapter or exercise).

            Below is the full list of candidate learning goals that an earlier pass extracted from the
            session "%s", chunk by chunk. Because each chunk was read in isolation, the list is
            fragmented: it over-produces narrow goals and states the same broad outcome several times
            in slightly different words. Each candidate is prefixed with its index in square brackets.

            Consolidate them into the BROAD learning outcomes the session teaches — the handful of
            objectives an instructor would put on a "learning objectives" slide for this lecture, not
            a line-by-line inventory of every fact, step or example. A lecture typically has about
            three to seven such outcomes. Report as many as the material genuinely needs, but lean
            firmly towards FEW broad outcomes rather than many narrow ones: the consolidated list must
            be substantially shorter than the candidate list.

              - Merge candidates that express the SAME outcome restated in different words across
                chunks, AND roll up narrow candidates that are facets, steps, methods or examples of
                one larger competency into a single broader outcome that subsumes them. For example,
                several candidates about individual distance metrics, the decision rule and choosing a
                neighbourhood size all collapse into one outcome about understanding and applying
                k-nearest-neighbours classification.
              - Keep as separate outcomes only competencies that are genuinely UNRELATED. Do NOT split
                one topic into many sub-skill outcomes just because the candidates mention each part
                separately, and do NOT merge two clearly distinct topics just to shrink the count.
              - Set each outcome's cognitive level by what the STUDENT is expected to be able to do,
                not by the activity the material shows. The slides may derive, prove or demonstrate
                something the student only needs to UNDERSTAND, not reproduce — keep such an outcome
                at "understand"/"explain" and do NOT promote it to "derive", "construct", "evaluate"
                or "design". Mirror the candidates' verbs and, when in doubt, prefer the lower level.
              - Do NOT invent outcomes that the candidates do not support.
              - Do NOT simply copy every candidate through — collapse the redundant restatements.

            For each consolidated outcome return:
              - text: the outcome as a single concise sentence, starting with a verb.
              - supporting: the indices (the numbers in square brackets) of the candidate goals that
                this outcome was merged from. List every candidate the outcome genuinely covers, so
                each candidate maps to the outcome(s) it belongs to.

            Candidate learning goals:
            ---
            %s
            ---
            """;

    private final ChatClient chatClient;

    public SessionGoalConsolidator(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public List<ConsolidatedGoal> consolidate(String sessionTitle, List<String> candidates) {
        return consolidate(sessionTitle, candidates, null);
    }

    /**
     * @param sessionTitle  the session's title, given to the model as context for what these
     *                      candidates have in common. May be blank.
     * @param candidates    the candidate goal texts extracted from the session's chunks; the returned
     *                      {@code supporting} indices point back into this list positionally.
     * @param modelOverride optional SAIA model id; falls back to the configured default when blank.
     * @return zero or more consolidated session-level outcomes; empty when there are no candidates.
     */
    public List<ConsolidatedGoal> consolidate(String sessionTitle, List<String> candidates, String modelOverride) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        // Number the candidates so the model can point back to them by index in `supporting`.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            sb.append('[').append(i).append("] ").append(candidates.get(i)).append('\n');
        }
        String title = (sessionTitle == null || sessionTitle.isBlank()) ? "(untitled session)" : sessionTitle;
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        if (modelOverride != null && !modelOverride.isBlank()) {
            spec = spec.options(ChatOptions.builder().model(modelOverride).build());
        }
        return spec
                .user(PROMPT_TEMPLATE.formatted(title, sb.toString()))
                .call()
                .entity(new ParameterizedTypeReference<List<ConsolidatedGoal>>() {});
    }
}
