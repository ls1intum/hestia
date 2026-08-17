package de.tum.cit.hestia.learninggoalhub.extraction;

import de.tum.cit.hestia.learninggoalhub.document.LanguageDetectionService;
import de.tum.cit.hestia.learninggoalhub.document.PageDescriptionService;
import de.tum.cit.hestia.learninggoalhub.llm.LenientJson;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Extracts the broad skills and their knowledge items from one complete session in a single
 * structured LLM call.
 */
@Service
public class SessionExtractionService {

    static final String PROMPT_VERSION = "direct-v10";

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

    private static final String FIGURE_PROMPT_SUFFIX = """

            Figure descriptions (AI-generated from rendered slides — NOT verbatim text):
            ---
            %s---

            Source selection with figure descriptions: outcomes should cite sourceStartLine/sourceEndLine as before. ONLY when no numbered lines support an outcome taught by a figure may the outcome instead set sourceFigure to the [Fn] index and omit the line fields. Never cite a figure when numbered lines support the outcome, and never invent outcomes the material does not teach.
            """;

    private static final String FINAL_LANGUAGE_RESTATEMENT = """

            Final language requirement: every generated text and shortLabel must be in %s. Keep all
            verbatim source quotes and quoted material in the document's own language.
            """;

    private static final Logger log = LoggerFactory.getLogger(SessionExtractionService.class);
    private final ChatClient chatClient;
    private final LanguageDetectionService languageDetectionService;
    private final double temperature;

    public SessionExtractionService(ChatClient.Builder chatClientBuilder,
                                    LanguageDetectionService languageDetectionService,
                                    @Value("${hestia.extraction.temperature:0.2}") double temperature) {
        this.chatClient = chatClientBuilder.build();
        this.languageDetectionService = languageDetectionService;
        this.temperature = temperature;
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
        return extract(sessionTitle, sessionText, null, languageName, modelOverride, List.of());
    }

    public List<ExtractedSkill> extract(String sessionTitle, String sessionText, String expectedLanguageCode,
                                        String languageName, String modelOverride) {
        return extract(sessionTitle, sessionText, expectedLanguageCode, languageName, modelOverride, List.of());
    }

    public List<ExtractedSkill> extract(String sessionTitle, String sessionText, String expectedLanguageCode,
                                        String languageName, String modelOverride,
                                        List<PageDescriptionService.FigureDescription> figureDescriptions) {
        String title = sessionTitle == null || sessionTitle.isBlank() ? "(untitled session)" : sessionTitle;
        String numberedSessionText = NumberedLines.of(sessionText).render();
        String prompt = PROMPT_TEMPLATE.formatted(languageName, title, numberedSessionText);
        if (figureDescriptions != null && !figureDescriptions.isEmpty()) {
            StringBuilder figures = new StringBuilder();
            for (int i = 0; i < figureDescriptions.size(); i++) {
                PageDescriptionService.FigureDescription figure = figureDescriptions.get(i);
                figures.append("[F").append(i).append("] (page ").append(figure.page()).append(") ")
                        .append(figure.description()).append('\n');
            }
            prompt += FIGURE_PROMPT_SUFFIX.formatted(figures);
        }
        prompt += FINAL_LANGUAGE_RESTATEMENT.formatted(languageName);

        List<ExtractedSkill> first = call(prompt, languageName, modelOverride, temperature, false);
        String detectedLanguage = detectGeneratedLanguage(first);
        if (expectedLanguageCode == null || expectedLanguageCode.isBlank()
                || detectedLanguage == null
                || detectedLanguage.equalsIgnoreCase(expectedLanguageCode)) {
            return first;
        }

        log.warn("Session extraction language mismatch: expected {}, detected {} for '{}' — retrying once",
                expectedLanguageCode, detectedLanguage, title);
        List<ExtractedSkill> retry = call(prompt, languageName, modelOverride, 0.0, true);
        String retryLanguage = detectGeneratedLanguage(retry);
        if (retryLanguage != null && retryLanguage.equalsIgnoreCase(expectedLanguageCode)) {
            log.warn("Session extraction language retry matched expected language {} for '{}'",
                    expectedLanguageCode, title);
            return retry;
        }
        log.warn("Session extraction language retry did not match expected language {} for '{}': detected {}; "
                        + "keeping first result",
                expectedLanguageCode, title, retryLanguage);
        return first;
    }

    private List<ExtractedSkill> call(String prompt, String languageName, String modelOverride,
                                      double callTemperature, boolean retry) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .system(retry
                        ? LanguagePrompt.retrySystemInstruction(languageName)
                        : LanguagePrompt.systemInstruction(languageName))
                .options(options(modelOverride, callTemperature));
        List<ExtractedSkill> skills = spec
                .user(prompt)
                .call()
                .entity(LenientJson.converter(new ParameterizedTypeReference<List<ExtractedSkill>>() {}));
        return skills == null ? List.of() : skills;
    }

    private ChatOptions options(String modelOverride, double callTemperature) {
        ChatOptions.Builder builder = ChatOptions.builder().temperature(callTemperature);
        if (modelOverride != null && !modelOverride.isBlank()) {
            builder.model(modelOverride);
        }
        return builder.build();
    }

    private String detectGeneratedLanguage(List<ExtractedSkill> skills) {
        StringBuilder generated = new StringBuilder();
        if (skills != null) {
            for (ExtractedSkill skill : skills) {
                append(generated, skill == null ? null : skill.text());
                append(generated, skill == null ? null : skill.shortLabel());
                if (skill != null && skill.knowledge() != null) {
                    for (ExtractedSkill.Knowledge knowledge : skill.knowledge()) {
                        if (knowledge != null) {
                            append(generated, knowledge.text());
                            append(generated, knowledge.shortLabel());
                        }
                    }
                }
            }
        }
        return languageDetectionService.detect(generated.toString());
    }

    private static void append(StringBuilder target, String value) {
        if (value != null && !value.isBlank()) {
            if (target.length() > 0) {
                target.append('\n');
            }
            target.append(value);
        }
    }
}
