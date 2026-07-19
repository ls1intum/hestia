package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Derives a course's <em>terminal competencies</em> — the applied capabilities a student should be
 * able to perform after the whole course — and assigns all of the course's extracted goals to them
 * in one course-wide LLM call.
 *
 * <p>This keeps "understand" goals at "understand" in the ordinary extracted hierarchy. A
 * competency tree instead needs the <em>doing</em>
 * capabilities, which live in the {@code APPLY}/{@code CREATE} goals and cut across several sessions
 * (e.g. Docker + Kubernetes → "containerise and orchestrate applications"). So this synthesiser:
 *
 * <ul>
 *   <li>seeds competencies from {@code APPLY}/{@code CREATE} goals and treats {@code ANALYZE}/
 *       {@code EVALUATE} "compare/understand" goals as supporting knowledge, not competencies in
 *       their own right;</li>
 *   <li>clusters across the whole course in a single call, so a capability spanning several topics
 *       is named once instead of split per session;</li>
 *   <li>assigns every input goal, including lower-Bloom knowledge goals, through complete
 *       {@code supporting} index lists;</li>
 *   <li>drops course-administration / tooling-trivia candidates that carry a high-Bloom verb but are
 *       not learning competencies;</li>
 *   <li>does <b>not</b> target a fixed number — it names as many distinct competencies as the
 *       material genuinely supports.</li>
 * </ul>
 *
 * <p>The caller passes every session/exercise goal; each candidate carries its Bloom level so the
 * prompt can tell seeds from supporting knowledge. The returned {@code supporting} indices are the
 * complete per-goal assignment and point back into the candidate list positionally.
 */
@Service
public class TerminalCompetencySynthesizer {

    /** One candidate goal handed to the synthesiser: its text and its Bloom level (may be null). */
    public record Candidate(String text, String bloomLevel) {}

    static final String PROMPT = """
            You identify a course's TERMINAL COMPETENCIES: the BROAD applied capabilities a student
            should be able to perform after completing the whole course — the handful an instructor
            would list as "by the end of this course you can ...", each built around a concrete DOING
            verb (deploy, build, configure, secure, automate, design ...).

            Below are ALL of the course's session/exercise learning goals, each prefixed with its index
            in square brackets and its Bloom level in parentheses. They come from many different
            sessions and include higher-level goals as well as lower-level knowledge goals.

            How to read the input:
              - APPLY and CREATE goals are the SEEDS: they describe things the student actually does and
                are what terminal competencies are built from.
              - ANALYZE and EVALUATE goals are usually "compare X and Y" / "understand the trade-offs":
                these are SUPPORTING KNOWLEDGE beneath a competency, not competencies in their own
                right. Fold them under the competency they serve; do not elevate a bare "compare ..."
                goal into a terminal competency.

            MERGE AGGRESSIVELY into broad, course-level competencies:
              - A whole course converges on only a HANDFUL of broad competencies. One competency per
                tool, per topic, or per session is TOO FINE — merge such goals together.
              - Merge goals that target the SAME underlying capability through different tools,
                providers, layers or examples into ONE competency, and merge the sub-steps of one
                workflow into the competency that workflow serves. (Illustration from a cloud course:
                several per-platform "autoscaling" goals collapse into one "Scale applications
                elastically"; container and orchestration goals collapse into one "Orchestrate
                containerized applications". Apply this PATTERN to whatever THIS course's domain is —
                do not look for these specific cloud topics.)
              - A competency that ends up supported by only ONE goal is SUSPICIOUS: check whether it
                folds into a broader competency before keeping it standalone.

            Other rules:
              - ASSIGNMENT: use each competency's supporting list to assign every input goal to exactly
                ONE competency. This includes ANALYZE, EVALUATE, REMEMBER and UNDERSTAND goals. The
                first matching competency in list order wins if a goal appears more than once.
              - COVERAGE: every APPLY and CREATE candidate must end up under at least one competency.
                If a genuine doing-capability fits none of the broad competencies, that is a signal to
                ADD its own competency for it — NOT to drop it. Likewise, place every lower-level goal
                under the best-fit competency when it supports one.
              - The ONLY candidates you may leave unassigned are course administration, logistics,
                exam/submission mechanics, ONE-OFF tool usage (e.g. a tunneling utility), or a single
                exercise's throwaway implementation task — these are not course-level competencies.
              - One competency covers exactly ONE capability, stated with a SINGLE leading action verb.
                Do NOT chain verbs with "and" or commas — choose a single verb broad enough to cover
                the merged goals (e.g. "Orchestrate ...", "Secure ...", "Scale ...").
              - Each competency SUBSUMES SEVERAL candidates; do NOT restate a single goal verbatim and do
                NOT copy the list through.
              - Do NOT invent capabilities the candidates do not support.
              - State as many BROAD competencies as the course genuinely builds toward and no more — do
                not target or pad to a number, but ERR ON THE SIDE OF FEWER, BROADER competencies.

            For each terminal competency return:
              - text: the competency as a single concise sentence built around ONE action verb.
              - shortLabel: a 2-5 word noun phrase naming the topic, such as "Bias-Variance Tradeoff";
                do not start it with a verb or end it with a period.
              - supporting: the complete list of indices (the numbers in square brackets) of the
                candidate goals assigned to this competency. Do not use supporting as sparse hints:
                every non-administrative candidate should occur in exactly one supporting list.

            Candidate goals:
            ---
            %s
            ---
            """;

    private final ChatClient chatClient;

    public TerminalCompetencySynthesizer(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Clusters all course candidate goals into terminal competencies and assigns each relevant goal
     * through the returned {@code supporting} indices.
     *
     * @param candidates    every session/exercise goal in the course, each with its Bloom level; the
     *                      returned {@code supporting} indices point back into this list positionally.
     * @param modelOverride optional SAIA model id; falls back to the configured default when blank.
     * @return zero or more terminal competencies; empty when there is nothing to cluster.
     */
    public List<TerminalCompetency> synthesize(List<Candidate> candidates, String modelOverride) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return call(PROMPT.formatted(numbered(candidates)), modelOverride);
    }

    private List<TerminalCompetency> call(String prompt, String modelOverride) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        if (modelOverride != null && !modelOverride.isBlank()) {
            spec = spec.options(ChatOptions.builder().model(modelOverride).build());
        }
        return spec
                .user(prompt)
                .call()
                .entity(new ParameterizedTypeReference<List<TerminalCompetency>>() {});
    }

    /** Numbers the candidates and labels each with its Bloom level so the model can cite them back. */
    private static String numbered(List<Candidate> candidates) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            String bloom = (c.bloomLevel() == null || c.bloomLevel().isBlank()) ? "?" : c.bloomLevel();
            sb.append('[').append(i).append("] (").append(bloom).append(") ").append(c.text()).append('\n');
        }
        return sb.toString();
    }
}
