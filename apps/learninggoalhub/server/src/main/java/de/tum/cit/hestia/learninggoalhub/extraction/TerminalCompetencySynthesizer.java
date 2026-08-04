package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Names a course's <em>terminal competencies</em> — the applied capabilities a student should be
 * able to perform after the whole course — in one course-wide LLM call.
 *
 * <p>This keeps "understand" goals at "understand" in the ordinary extracted hierarchy. A
 * competency tree instead needs the extracted <em>skill</em> goals, which cut across several sessions
 * (e.g. Docker + Kubernetes → "containerise and orchestrate applications"). So this synthesiser:
 *
 * <ul>
 *   <li>seeds competencies from {@code APPLY}/{@code CREATE} skill goals while also using
 *       {@code ANALYZE}/{@code EVALUATE} skills to shape the competency boundaries;</li>
 *   <li>clusters across the whole course in a single call, so a capability spanning several topics
 *       is named once instead of split per session;</li>
 *   <li>drops course-administration / tooling-trivia candidates that carry a high-Bloom verb but are
 *       not learning competencies;</li>
 *   <li>does <b>not</b> target a fixed number — it names as many distinct competencies as the
 *       material genuinely supports.</li>
 * </ul>
 *
 * <p>It <b>names only</b>. Which goal belongs to which competency is decided afterwards by
 * {@link CompetencyAssignmentSynthesizer}, against the finished competency list. Naming and
 * assigning used to share this one call, which let the model commit a goal to an early competency
 * before the competency that actually fits it had been written.
 *
 * <p>The caller passes <b>every</b> session/exercise skill, not just the seeds: a capability carried
 * mainly by {@code ANALYZE}/{@code EVALUATE} skills would otherwise go unnamed, and lower-Bloom skills
 * still tell the model how broad a competency may be. Each candidate carries its Bloom level so the
 * prompt can distinguish seeds from context.
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

            Write every generated text and shortLabel value in %s. Keep the JSON property names text
            and shortLabel exactly as written. The Bloom labels in the input are fixed
            English enum values and must remain exactly as provided.

            Below are ALL of the course's session/exercise SKILL goals, each prefixed with its index
            in square brackets and its Bloom level in parentheses. They come from many different
            sessions and can have different Bloom levels.

            How to read the input:
              - APPLY and CREATE goals are the SEEDS: they describe things the student actually does and
                are what terminal competencies are built from.
              - ANALYZE and EVALUATE skill goals may describe "compare X and Y" / "understand the
                trade-offs". Use them to shape the competency they serve; do not elevate a bare
                "compare ..." goal into a separate terminal competency.

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
              - NAME ONLY. Do NOT state which goals belong to which competency — a separate step
                assigns every goal afterwards, against your finished list. Your job is to make that
                list complete and well-cut.
              - COVERAGE: the list must leave no genuine doing-capability homeless. Read through the
                APPLY and CREATE candidates and check each one is covered by some competency; if one
                fits none of them, that is a signal to ADD a competency for it — NOT to drop it.
              - Capabilities carried mainly by ANALYZE or EVALUATE goals still deserve a competency
                when no APPLY/CREATE goal covers the same ground; do not leave a whole topic unnamed
                just because it lacks a doing verb in the candidates.
              - Course administration, logistics, exam/submission mechanics, ONE-OFF tool usage (e.g.
                a tunneling utility) and a single exercise's throwaway implementation task are NOT
                course-level competencies — do not name competencies for them.
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
        return synthesize(candidates, "English", modelOverride);
    }

    public List<TerminalCompetency> synthesize(List<Candidate> candidates, String languageName,
                                               String modelOverride) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return call(PROMPT.formatted(languageName, numbered(candidates)), modelOverride);
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
