package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Extracts the broad skills and their knowledge items from one complete session in a single
 * structured LLM call.
 */
@Service
public class SessionExtractionService {

    static final String PROMPT_VERSION = "direct-v7";

    static final String PROMPT_TEMPLATE = """
            You analyse the complete educational material of one session (a lecture, chapter or
            exercise) to identify its learning outcomes.

            Write every generated text and shortLabel value in %s. Keep the JSON property names
            text, shortLabel, kind, sourceStartLine, sourceEndLine and knowledge exactly as written,
            and keep kind values exactly EXPLICIT or IMPLICIT. The source line indices refer to the
            numbered non-blank lines shown below; never translate the source text.

            Extract the session's BROAD instructor-level learning outcomes — the handful of objectives
            an instructor would put on a "learning objectives" slide for this session, not a line-by-line
            inventory of every fact, step or example. SKILLS stay FEW and broad: HARD CAP — never return
            more than seven skills. Report as many as the material genuinely needs, but lean firmly
            towards FEW broad skills rather than many narrow ones. If you are unsure whether something
            is a skill or knowledge, make it knowledge. Merge related facets, steps, methods and examples
            into the larger competency they support as that skill's knowledge children; do not delete
            those facets. For contrast: "Apply Bayes' theorem" is a skill, while "Explain Bayes' theorem"
            is a knowledge item underpinning it.

            KNOWLEDGE covers what a student must know to reach the skill above it: every fact,
            definition, method, step or distinction the session teaches that the skill genuinely rests
            on, each as its own item. Include what is independently assessable; leave out incidental
            examples, asides and repetition. Every knowledge item is itself a learning outcome and MUST
            start with a verb naming what the student does with it — "Explain", "Describe", "Identify",
            "Distinguish", "Name", "Recall". Never state a bare fact.
              WRONG: "The optimal variable ordering problem is NP-complete"
              RIGHT: "Explain why finding the optimal variable ordering is NP-complete"
            Skills are what the student can DO; knowledge is what the student must know to do it.

            Choose each outcome's verb by what the STUDENT is expected to be able to do or know
            afterwards — not by the activity the material happens to show. Slides often derive, prove,
            demonstrate or work through something that the student is only expected to UNDERSTAND, not
            to reproduce: a worked derivation of an estimator, a demonstrated construction, or a proof
            usually means the student should "understand"/"explain" it, not "derive"/"construct"/
            "prove" it themselves. Pick the verb accordingly and do NOT escalate — reserve "apply",
            "compute", "construct", "derive", "design" or "evaluate" for material that genuinely asks
            the student to carry out that action, not merely to follow it. When in doubt, prefer the
            lower level (understand/know).

            Classify each skill and knowledge item as:
              - EXPLICIT: stated directly as a goal or outcome in the text (e.g. "students can ...",
                "by the end of this lecture you will ...").
              - IMPLICIT: an outcome clearly taught by the content but not phrased as a goal.

            Return the list of skills, each with:
              - text: the skill as a single concise sentence, starting with a verb.
              - shortLabel: a 2-5 word noun phrase naming the topic, such as "Bias-Variance Tradeoff";
                do not start it with a verb or end it with a period.
              - kind: EXPLICIT or IMPLICIT.
              - sourceStartLine and sourceEndLine: the inclusive zero-based index range of the numbered
                lines shown below that best supports the outcome, selected from ONE contiguous place
                in the text. Usually select 1-3 lines; never select more than 5 lines and never combine
                separate passages. A heading and the bullet points beneath it are SEPARATE passages,
                even when they sit together on one slide: pick one, never combine them. The indices
                MUST come from the numbered lines shown below.
              - knowledge: every knowledge item underpinning this skill, each with its own text,
                shortLabel, kind, sourceStartLine and sourceEndLine. The verb-initial rule and every
                source-line rule above apply to knowledge items exactly as they do to skills.

            Do not invent outcomes that are not supported by the text. Do not promote a demonstrated
            derivation, proof or construction into an expected student action unless the text explicitly
            requires students to perform it.

            Session title:
            ---
            %s
            ---

            Numbered non-blank session lines:
            ---
            %s
            ---
            """;

    private final ChatClient chatClient;

    public SessionExtractionService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public List<ExtractedSkill> extract(String sessionTitle, String sessionText) {
        return extract(sessionTitle, sessionText, null);
    }

    /**
     * @param sessionTitle  the structural title of the session; may be blank.
     * @param sessionText   the complete text of the session.
     * @param modelOverride optional SAIA model id; falls back to the configured default when blank.
     * @return the broad skills and their knowledge items found in the session.
     */
    public List<ExtractedSkill> extract(String sessionTitle, String sessionText, String modelOverride) {
        return extract(sessionTitle, sessionText, "English", modelOverride);
    }

    public List<ExtractedSkill> extract(String sessionTitle, String sessionText, String languageName,
                                        String modelOverride) {
        String title = sessionTitle == null || sessionTitle.isBlank() ? "(untitled session)" : sessionTitle;
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        if (modelOverride != null && !modelOverride.isBlank()) {
            spec = spec.options(ChatOptions.builder().model(modelOverride).build());
        }
        String numberedSessionText = NumberedLines.of(sessionText).render();
        List<ExtractedSkill> skills = spec
                .user(PROMPT_TEMPLATE.formatted(languageName, title, numberedSessionText))
                .call()
                .entity(new ParameterizedTypeReference<List<ExtractedSkill>>() {});
        return skills == null ? List.of() : skills;
    }
}
