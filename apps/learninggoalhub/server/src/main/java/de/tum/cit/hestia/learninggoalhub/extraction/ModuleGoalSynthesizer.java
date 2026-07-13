package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Derives a course's module-level learning outcomes bottom-up from its already-extracted session-
 * and exercise-level goals, in two stages (a hierarchical map-reduce over the goals):
 *
 * <ol>
 *   <li><b>Condense (map, one call per session):</b> {@link #condenseSession} reads a single
 *       session's consolidated goals in isolation and distils them into that session's one-to-three
 *       headline competencies — one notch more integrative than the individual goals, but still
 *       single-topic. Because each call sees only one topic, the verbs stay clean and no
 *       cross-topic chaining creeps in.</li>
 *   <li><b>Integrate (reduce, one course-wide call):</b> {@link #integrate} reads the headlines of
 *       all sessions together and names the broad, cross-cutting competencies the course as a whole
 *       builds toward. Its input is the small, already-clean set of headlines rather than the full
 *       fine-grained goal list, so it can actually find integration across topics.</li>
 * </ol>
 *
 * <p>Both stages are deliberately conservative: each names an outcome only when the input genuinely
 * converges on a shared higher-level competency, never restates a single input verbatim, and may
 * legitimately return an empty list. The intermediate headlines are scaffolding — only the final
 * integrated outcomes are persisted (see {@code ExtractionRunner}).
 */
@Service
public class ModuleGoalSynthesizer {

    /** Shared verb-fidelity guidance, mirroring the extraction/consolidation prompts. */
    static final String VERB_FIDELITY = """
            Set each outcome's cognitive level by what the STUDENT is expected to be able to do, not by
            the activity the material shows. Keep an outcome the student only needs to UNDERSTAND at
            "understand"/"explain" and do NOT promote it to "apply", "derive", "construct", "evaluate"
            or "design"; when in doubt prefer the lower level.""";

    static final String CONDENSE_PROMPT = """
            You distil a single session's learning goals into its headline competencies.

            Below are the consolidated learning goals of the session "%s" (a lecture, chapter or
            exercise), each prefixed with its index in square brackets. State the ONE to THREE headline
            competencies this session builds toward: each one notch more integrative than the
            individual goals — the way an instructor would summarise "after this session you can ..." —
            while staying within this single topic.

              - Each headline subsumes SEVERAL of the session's goals; do NOT restate a single goal
                verbatim and do NOT simply copy the list through.
              - One competency per headline with a SINGLE leading action verb; do NOT chain verbs
                with "and" or commas ("understand X and apply Y" is two headlines, not one).
              - %s
              - State as few headlines as the session needs (often just one); do not pad to three.

            For each headline return:
              - text: the headline as a single concise sentence built around ONE action verb.
              - supporting: the indices (the numbers in square brackets) of the session goals this
                headline genuinely subsumes.

            Session learning goals:
            ---
            %s
            ---
            """;

    static final String INTEGRATE_PROMPT = """
            You identify a course's overarching, cross-cutting module-level outcomes.

            Below are the headline competencies distilled from each of the course's sessions/topics,
            each prefixed with its index in square brackets. Identify the MODULE-level learning
            outcomes: the broad, integrative competencies that span SEVERAL topics and that the course
            as a whole builds toward, reached through the combination of these session headlines.

            Be conservative:
              - Prefer outcomes that genuinely INTEGRATE across several sessions' headlines. An outcome
                that merely echoes a single session's headline is weak — keep it only when that
                competency truly stands on its own at course level.
              - One outcome covers exactly ONE competency, stated with a SINGLE leading action verb.
                Do NOT chain verbs with "and" or commas — split such a statement into separate
                outcomes, each with its own verb.
              - %s
              - Do NOT invent competencies the headlines do not support.
              - Returning an EMPTY list is a valid and correct answer when the headlines do not
                converge on any shared, cross-cutting competency.
              - State as many outcomes as there are distinct cross-cutting competencies; do not force a
                target number and do not pad — a course often has only a handful.

            For each module outcome return:
              - text: the outcome as a single concise sentence built around ONE action verb.
              - supporting: the indices (the numbers in square brackets) of ONLY those session
                headlines that genuinely build toward this outcome.

            Session headlines:
            ---
            %s
            ---
            """;

    private final ChatClient chatClient;

    public ModuleGoalSynthesizer(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * Map stage: distils one session's consolidated goals into its headline competencies.
     *
     * @param sessionTitle  the session's title, given as context for what its goals have in common.
     * @param sessionGoals  the session's consolidated goal texts; the returned {@code supporting}
     *                      indices point back into this list positionally.
     * @param modelOverride optional SAIA model id; falls back to the configured default when blank.
     * @return one or more single-topic headlines; empty when there are no goals to condense.
     */
    public List<SynthesizedModuleGoal> condenseSession(String sessionTitle, List<String> sessionGoals,
                                                       String modelOverride) {
        if (sessionGoals == null || sessionGoals.isEmpty()) {
            return List.of();
        }
        String title = (sessionTitle == null || sessionTitle.isBlank()) ? "(untitled session)" : sessionTitle;
        return call(CONDENSE_PROMPT.formatted(title, VERB_FIDELITY, numbered(sessionGoals)), modelOverride);
    }

    /**
     * Reduce stage: integrates the per-session headlines into the course's cross-cutting outcomes.
     *
     * @param sessionHeadlines the headline texts distilled from every session; the returned
     *                         {@code supporting} indices point back into this list positionally.
     * @param modelOverride    optional SAIA model id; falls back to the configured default when blank.
     * @return zero or more cross-cutting module outcomes; empty when the headlines do not converge or
     *         when there are none to integrate.
     */
    public List<SynthesizedModuleGoal> integrate(List<String> sessionHeadlines, String modelOverride) {
        if (sessionHeadlines == null || sessionHeadlines.isEmpty()) {
            return List.of();
        }
        return call(INTEGRATE_PROMPT.formatted(VERB_FIDELITY, numbered(sessionHeadlines)), modelOverride);
    }

    private List<SynthesizedModuleGoal> call(String prompt, String modelOverride) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        if (modelOverride != null && !modelOverride.isBlank()) {
            spec = spec.options(ChatOptions.builder().model(modelOverride).build());
        }
        return spec
                .user(prompt)
                .call()
                .entity(new ParameterizedTypeReference<List<SynthesizedModuleGoal>>() {});
    }

    /** Numbers the lines so the model can point back to them by index in {@code supporting}. */
    private static String numbered(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append('[').append(i).append("] ").append(lines.get(i)).append('\n');
        }
        return sb.toString();
    }
}
