package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * First stage of the legacy fallback extraction: reads one configured-size chunk of a session's text
 * in isolation and returns the learning-goal CANDIDATES it conveys. Because a chunk is only a slice of
 * a session, this stage is deliberately exhaustive rather than selective — it captures every candidate
 * it can find. The {@link SessionGoalConsolidator} then sees all of a fallback session's candidates
 * together and merges them into the handful of broad outcomes the session actually teaches, so the
 * "a lecture has only a few broad goals" judgement is made there, where the whole session is visible.
 */
@Service
public class ExtractionService {

    static final String PROMPT_TEMPLATE = """
            You analyse a slice of educational material to identify its learning-goal candidates.

            A learning goal is a learning OUTCOME: a statement of what a student should be able to do
            after working through the material, phrased the way an instructor writes them (e.g.
            "Understand the basic terminology of ML, AI, DL and statistics", "Apply gradient descent
            to train a linear model").

            The text below is ONE chunk of a larger session (a lecture, chapter or exercise), so you
            see only part of it. Extract every learning-goal CANDIDATE this chunk supports — be
            thorough rather than selective; a later step consolidates the candidates from all of the
            session's chunks into its few broad outcomes, so do not try to pre-judge which are "major"
            here and do not artificially limit the count.

            Choose each candidate's verb by what the STUDENT is expected to be able to do or know
            afterwards — not by the activity the material happens to show. Slides often derive, prove,
            demonstrate or work through something that the student is only expected to UNDERSTAND, not
            to reproduce: a worked derivation of an estimator, a demonstrated construction, or a proof
            usually means the student should "understand"/"explain" it, not "derive"/"construct"/
            "prove" it themselves. Pick the verb accordingly and do NOT escalate — reserve "apply",
            "compute", "construct", "derive", "design" or "evaluate" for material that genuinely asks
            the student to carry out that action, not merely to follow it. When in doubt, prefer the
            lower level (understand/know).

            Classify each candidate as:
              - EXPLICIT: stated directly as a goal or outcome in the text (e.g. "students can ...",
                "by the end of this lecture you will ...").
              - IMPLICIT: an outcome clearly taught by the content but not phrased as a goal.

            Return the list of candidates, each with:
              - text: the learning goal as a single concise sentence, starting with a verb.
              - kind: EXPLICIT or IMPLICIT.
              - sourceSnippet: a short verbatim quote from the document (1-3 sentences) that supports
                the goal.

            Do not invent goals that are not supported by the text.

            Document text (one chunk of a larger session):
            ---
            %s
            ---
            """;

    private final ChatClient chatClient;

    public ExtractionService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public List<ExtractedGoal> extract(String chunkText) {
        return extract(chunkText, null);
    }

    /**
     * @param chunkText     one chunk of a session's text.
     * @param modelOverride optional SAIA model id; falls back to the configured default when blank.
     * @return the learning-goal candidates found in the chunk.
     */
    public List<ExtractedGoal> extract(String chunkText, String modelOverride) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        if (modelOverride != null && !modelOverride.isBlank()) {
            spec = spec.options(ChatOptions.builder().model(modelOverride).build());
        }
        List<ExtractedGoal> goals = spec
                .user(PROMPT_TEMPLATE.formatted(chunkText))
                .call()
                .entity(new ParameterizedTypeReference<List<ExtractedGoal>>() {});
        return goals == null ? List.of() : goals;
    }
}
