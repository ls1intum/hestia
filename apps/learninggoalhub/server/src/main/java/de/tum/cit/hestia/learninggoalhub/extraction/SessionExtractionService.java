package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Extracts the broad learning outcomes of one complete session in a single structured LLM call.
 */
@Service
public class SessionExtractionService {

    static final String PROMPT_VERSION = "direct-v3";

    static final String PROMPT_TEMPLATE = """
            You analyse the complete educational material of one session (a lecture, chapter or
            exercise) to identify its learning outcomes.

            Extract the session's BROAD instructor-level learning outcomes — the handful of objectives
            an instructor would put on a "learning objectives" slide for this session, not a line-by-line
            inventory of every fact, step or example. A session typically has about three to seven such
            outcomes. Report as many as the material genuinely needs, but lean firmly towards FEW broad
            outcomes rather than many narrow ones. Merge related facets, steps, methods and examples into
            the larger competency they support, but keep genuinely unrelated competencies separate.

            Choose each outcome's verb by what the STUDENT is expected to be able to do or know
            afterwards — not by the activity the material happens to show. Slides often derive, prove,
            demonstrate or work through something that the student is only expected to UNDERSTAND, not
            to reproduce: a worked derivation of an estimator, a demonstrated construction, or a proof
            usually means the student should "understand"/"explain" it, not "derive"/"construct"/
            "prove" it themselves. Pick the verb accordingly and do NOT escalate — reserve "apply",
            "compute", "construct", "derive", "design" or "evaluate" for material that genuinely asks
            the student to carry out that action, not merely to follow it. When in doubt, prefer the
            lower level (understand/know).

            Classify each outcome as:
              - EXPLICIT: stated directly as a goal or outcome in the text (e.g. "students can ...",
                "by the end of this lecture you will ...").
              - IMPLICIT: an outcome clearly taught by the content but not phrased as a goal.

            Return the list of outcomes, each with:
              - text: the learning outcome as a single concise sentence, starting with a verb.
              - shortLabel: a 2-5 word noun phrase naming the topic, such as "Bias-Variance Tradeoff";
                do not start it with a verb or end it with a period.
              - kind: EXPLICIT or IMPLICIT.
              - sourceSnippet: ONE contiguous verbatim quote from the document that supports the
                outcome, copied character-for-character from a single place in the text (1-3
                consecutive sentences). Never stitch together separate passages and never use "..."
                or any ellipsis to skip text — if several passages support the outcome, quote only
                the single strongest one.

            Do not invent outcomes that are not supported by the text. Do not promote a demonstrated
            derivation, proof or construction into an expected student action unless the text explicitly
            requires students to perform it.

            Session title:
            ---
            %s
            ---

            Full session text:
            ---
            %s
            ---
            """;

    private final ChatClient chatClient;

    public SessionExtractionService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public List<ExtractedGoal> extract(String sessionTitle, String sessionText) {
        return extract(sessionTitle, sessionText, null);
    }

    /**
     * @param sessionTitle  the structural title of the session; may be blank.
     * @param sessionText   the complete text of the session.
     * @param modelOverride optional SAIA model id; falls back to the configured default when blank.
     * @return the broad learning outcomes found in the session.
     */
    public List<ExtractedGoal> extract(String sessionTitle, String sessionText, String modelOverride) {
        String title = sessionTitle == null || sessionTitle.isBlank() ? "(untitled session)" : sessionTitle;
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        if (modelOverride != null && !modelOverride.isBlank()) {
            spec = spec.options(ChatOptions.builder().model(modelOverride).build());
        }
        List<ExtractedGoal> goals = spec
                .user(PROMPT_TEMPLATE.formatted(title, sessionText))
                .call()
                .entity(new ParameterizedTypeReference<List<ExtractedGoal>>() {});
        return goals == null ? List.of() : goals;
    }
}
