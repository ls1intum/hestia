package de.tum.cit.hestia.learninggoalhub.relationships;

import java.util.Arrays;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

/**
 * Judges prerequisite dependencies for a batch of candidate goal pairs in a single LLM call. The
 * candidate pairs are pre-selected by {@link PrerequisiteLinker} from embedding similarity, so the
 * model only ever sees a handful of plausible pairs at a time instead of every goal in a level at
 * once — this scales to courses with hundreds of session-level goals and lets the model focus on one
 * pair at a time.
 */
@Service
public class PrerequisiteService {

    static final String PROMPT_TEMPLATE = """
            You decide, for each numbered pair of learning goals, whether one goal is a prerequisite
            of the other.

            Goal A is a prerequisite of goal B when a learner must achieve A before they can
            meaningfully tackle B. Be conservative — only assert a dependency that is clearly grounded
            in the goal texts themselves, not in general background knowledge.

            For each pair return:
              - `index`: the pair's number, copied from the list below.
              - `direction`: one of
                  - A_BEFORE_B: goal A must be achieved before goal B.
                  - B_BEFORE_A: goal B must be achieved before goal A.
                  - NONE: neither goal is a prerequisite of the other.
              - `confidence`: number in [0.0, 1.0] reflecting how clearly the dependency is implied
                (use 0.0 for NONE).

            Return one object for every pair, keeping its number in `index`, as JSON:
            `{ "judgments": [ { "index": 1, "direction": "NONE", "confidence": 0.0 } ] }`.

            Candidate pairs:
            %s
            """;

    private final ChatClient chatClient;

    public PrerequisiteService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public List<PrerequisitePairJudgment> judge(List<GoalPair> pairs) {
        return judge(pairs, null);
    }

    /**
     * Judges one batch of candidate pairs. Returns a list aligned to {@code pairs}: entry {@code i}
     * is the verdict for {@code pairs.get(i)}, or null when the model returned no usable verdict for
     * that pair. Pairs are numbered in the prompt and mapped back by the index the model echoes, so a
     * reordered, partial or padded answer still lands on the right pair.
     *
     * @param pairs         the candidate pairs to judge, in order; an empty list yields an empty result.
     * @param modelOverride optional SAIA model id; falls back to the configured default when blank.
     */
    public List<PrerequisitePairJudgment> judge(List<GoalPair> pairs, String modelOverride) {
        if (pairs == null || pairs.isEmpty()) {
            return List.of();
        }
        StringBuilder numbered = new StringBuilder();
        for (int i = 0; i < pairs.size(); i++) {
            GoalPair pair = pairs.get(i);
            numbered.append("Pair ").append(i + 1).append(":\n")
                    .append("  A) ").append(pair.a()).append('\n')
                    .append("  B) ").append(pair.b()).append('\n');
        }

        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        if (modelOverride != null && !modelOverride.isBlank()) {
            spec = spec.options(ChatOptions.builder().model(modelOverride).build());
        }
        PrerequisitePairJudgments response = spec
                .user(PROMPT_TEMPLATE.formatted(numbered.toString()))
                .call()
                .entity(PrerequisitePairJudgments.class);

        PrerequisitePairJudgment[] aligned = new PrerequisitePairJudgment[pairs.size()];
        if (response != null && response.judgments() != null) {
            for (PrerequisitePairJudgment j : response.judgments()) {
                int i = j.index() - 1;
                if (i >= 0 && i < aligned.length && j.direction() != null) {
                    aligned[i] = j;
                }
            }
        }
        return Arrays.asList(aligned);
    }
}
