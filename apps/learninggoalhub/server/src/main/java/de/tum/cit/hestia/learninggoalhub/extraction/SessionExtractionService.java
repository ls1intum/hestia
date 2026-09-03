package de.tum.cit.hestia.learninggoalhub.extraction;

import de.tum.cit.hestia.learninggoalhub.document.LanguageDetectionService;
import de.tum.cit.hestia.learninggoalhub.document.PageDescriptionService;
import de.tum.cit.hestia.learninggoalhub.llm.LenientJson;
import java.util.ArrayList;
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

    static final String PROMPT_VERSION = "direct-v17";

    /**
     * How many broad skills one session may yield.
     *
     * <p>At seven this was not a ceiling but a target: measured on a real thirteen-lecture course,
     * ten sessions returned exactly seven and none returned fewer than five. A lecture does not
     * teach seven separate capabilities — the model was filling the allowance with narrow facets of
     * the same competency, and the course-level tree then had to compress 86 near-duplicate outcomes
     * into six, which is where its run-to-run instability came from. Asking each session for the two
     * or three outcomes an instructor would actually put on an objectives slide moves that merging
     * to where the material is still visible. Nothing is lost by it: a facet that stops being its
     * own skill becomes knowledge under the skill it serves.
     */
    static final int MAX_SKILLS_PER_SESSION = 4;

    /**
     * Turns a unit's size into the number of skills it may yield.
     *
     * <p>Until this existed the allowance was flat, so a one-page problem sheet and a fifty-page
     * lecture were each asked for the same two or three outcomes. Measured on a real corpus, exercise
     * sheets were 14-17% of a course's text and produced 36-47% of its skills; across one 19-document
     * course the density ranged from 1,332 to 7,743 characters per skill purely by how the material
     * happened to be split into files. The tree's size was therefore decided by the uploader's
     * packaging rather than by how much the course teaches.
     *
     * <p>Scaling by size makes it decided by content instead: repackaging the same lectures into one
     * combined PDF, or splitting one exercise sheet into twelve, changes the total character count
     * not at all and so changes the allowance not at all.
     *
     * <p>The floor of one matters as much as the ceiling. A unit below the target still teaches
     * something, and rounding it to zero would silently drop short material — a genuine risk with
     * exercise sheets, which carry a course's highest Bloom levels (100% and 90% at APPLY or above on
     * the two corpora measured, against 73-75% for lectures).
     *
     * @param chars       the unit's own length; units are already split at the granularity budget.
     * @param targetChars characters per skill; zero or less disables scaling and keeps the ceiling.
     */
    static int skillBudget(int chars, int targetChars) {
        if (targetChars <= 0) {
            return MAX_SKILLS_PER_SESSION;
        }
        long scaled = Math.round((double) chars / targetChars);
        return (int) Math.max(1, Math.min(MAX_SKILLS_PER_SESSION, scaled));
    }

    /**
     * How the count is asked for at each allowance.
     *
     * <p>"Two or three, never more than four" reads as an instruction to produce three whatever the
     * material holds, which is what the model did on ten of thirteen lectures. At a budget of one or
     * two that phrasing would be actively misleading, so the request is stated at the size it was
     * computed for.
     */
    static String allowancePhrase(int budget) {
        return switch (budget) {
            case 1 -> "return exactly ONE";
            case 2 -> "return ONE OR TWO";
            default -> "return TWO OR THREE";
        };
    }

    private static final String REQUIRED_FIELDS_RETRY = """

            Your previous response violated a required field or outcome-wording rule. Regenerate the
            COMPLETE response. Every skill and knowledge item must contain non-blank, distinct text
            and shortLabel values plus kind EXPLICIT or IMPLICIT. Follow the action-noun wording
            invariant exactly. Every outcome must cite either one valid 1-5-line source range from
            the numbered session text or one offered figure, never both. Do not omit valid outcomes.
            Return only the structured JSON result.
            """;

    static final String PROMPT_TEMPLATE = """
            You analyse the complete educational material of one session (a lecture, chapter or
            exercise) to identify its learning outcomes.

            Write every generated text and shortLabel value in %s. Keep the JSON property names
            text, shortLabel, kind, sourceStartLine, sourceEndLine and knowledge exactly as written,
            and keep kind values exactly EXPLICIT or IMPLICIT. The source line indices refer to the
            numbered non-blank lines shown below; never translate the source text.

            Extract the session's BROAD instructor-level learning outcomes — the two or three objectives
            an instructor would put on a "learning objectives" slide for this session, not a line-by-line
            inventory of every fact, step or example. SKILLS stay FEW and broad: %s, and
            never more than %d. That allowance is set from how much material this unit holds, so it is
            already the right number for what you are reading — reaching the maximum is a signal that
            you have not merged enough: go back and fold the narrower candidates into the broader
            capability they serve. However much material a unit covers, more of it means MORE KNOWLEDGE
            under each skill, not more skills.

            If you are unsure whether something is a skill or knowledge, make it knowledge. Merge related
            facets, steps, methods and examples into the larger competency they support as that skill's
            knowledge children; do not delete those facets — nothing is lost by demoting them, because
            knowledge is where the detail of this session is supposed to live.
            For contrast: "Apply Bayes' theorem" is a skill, while "Explain Bayes' theorem" is a
            knowledge item underpinning it, and so are the individual steps of applying it.

            MERGING MUST NOT REDUCE DETAIL. When two candidate skills become one, the specifics of
            BOTH survive as separate knowledge items beneath it. Merging changes which tier a point
            sits in; it never removes the point. Reporting fewer skills than the material suggests
            while ALSO reporting little knowledge means you have summarised the session instead of
            inventorying what it teaches, and that is the one clearly wrong answer here.

            KNOWLEDGE covers what a student must know to reach the skill above it: every fact,
            definition, method, step or distinction the session teaches that the skill genuinely rests
            on, each as its own item. Include what is independently assessable; leave out incidental
            examples, asides and repetition.

            Knowledge is where this session's substance lives, so there is far MORE of it than there
            are skills: expect roughly five to ten knowledge items under each skill. Then check your
            coverage before answering — read the numbered lines from first to last and confirm that
            every substantive passage (each definition, theorem, rule, method, distinction or worked
            technique) is cited by at least one outcome, in almost every case a knowledge item. A
            passage that no outcome cites is material you have silently dropped.

            Every knowledge item is itself a learning outcome and MUST
            use the expanded action-noun form naming what the student does with it — "Explaining",
            "Describing", "Identifying", "Distinguishing", "Naming", "Recalling". Never state a bare fact.
              WRONG: "The optimal variable ordering problem is NP-complete"
              RIGHT: "Explaining why finding the optimal variable ordering is NP-complete"
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
              - text: an expanded action-noun outcome following the wording invariant.
              - shortLabel: a compact 2-6 word label naming the action and its topic, reusing the
                verb of the text above, such as "Analyse the bias-variance tradeoff". Phrase it in the
                natural word order of the output language (German puts the infinitive last:
                "Bias-Varianz-Abwägung analysieren") and do not end it with a period.
              - kind: EXPLICIT or IMPLICIT.
              - sourceStartLine and sourceEndLine: the inclusive zero-based index range of the numbered
                lines shown below that best supports the outcome, selected from ONE contiguous place
                in the text. Usually select 1-3 lines; never select more than 5 lines and never combine
                separate passages. A heading and the bullet points beneath it are SEPARATE passages,
                even when they sit together on one slide: pick one, never combine them. The indices
                MUST come from the numbered lines shown below.
              - knowledge: every knowledge item underpinning this skill, each with its own text,
                shortLabel, kind, sourceStartLine and sourceEndLine. The wording invariant and every
                source-line rule above apply to knowledge items exactly as they do to skills.

            Do not invent outcomes that are not supported by the text. Do not promote a demonstrated
            derivation, proof or construction into an expected student action unless the text explicitly
            requires students to perform it. Return skills in the order in which their supporting
            material first appears in the session; order each skill's knowledge the same way.

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
        return extract(sessionTitle, sessionText, expectedLanguageCode, languageName, modelOverride,
                figureDescriptions, MAX_SKILLS_PER_SESSION);
    }

    /**
     * @param skillBudget the most skills this unit may yield, from {@link #skillBudget(int, int)}.
     */
    public List<ExtractedSkill> extract(String sessionTitle, String sessionText, String expectedLanguageCode,
                                        String languageName, String modelOverride,
                                        List<PageDescriptionService.FigureDescription> figureDescriptions,
                                        int skillBudget) {
        int budget = Math.max(1, Math.min(MAX_SKILLS_PER_SESSION, skillBudget));
        String title = sessionTitle == null || sessionTitle.isBlank() ? "(untitled session)" : sessionTitle;
        NumberedLines numberedLines = NumberedLines.of(sessionText);
        String numberedSessionText = numberedLines.render();
        String prompt = PROMPT_TEMPLATE.formatted(
                languageName, allowancePhrase(budget), budget, title, numberedSessionText);
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

        List<ExtractedSkill> first;
        try {
            first = validate(call(prompt, languageName, modelOverride, temperature, false), languageName,
                    numberedLines, figureDescriptions == null ? 0 : figureDescriptions.size(), budget);
        } catch (IllegalArgumentException invalidResponse) {
            log.warn("Session extraction returned invalid fields, wording or evidence for '{}'; retrying once: {}",
                    title, invalidResponse.getMessage());
            String retryPrompt = prompt + REQUIRED_FIELDS_RETRY
                    + "\nSpecific validation failure: " + invalidResponse.getMessage();
            List<ExtractedSkill> retryResponse = call(retryPrompt, languageName, modelOverride, 0.0, false);
            try {
                first = validate(retryResponse, languageName, numberedLines,
                        figureDescriptions == null ? 0 : figureDescriptions.size(), budget);
            } catch (IllegalArgumentException invalidRetry) {
                first = salvageValidOutcomes(retryResponse, languageName, numberedLines,
                        figureDescriptions == null ? 0 : figureDescriptions.size());
                if (first.isEmpty()) {
                    throw invalidRetry;
                }
                log.warn("Session extraction retry for '{}' still contained invalid outcomes; "
                                + "keeping {} individually validated skills",
                        title, first.size());
            }
        }
        String detectedLanguage = detectGeneratedLanguage(first);
        if (expectedLanguageCode == null || expectedLanguageCode.isBlank()
                || detectedLanguage == null
                || detectedLanguage.equalsIgnoreCase(expectedLanguageCode)) {
            return first;
        }

        log.warn("Session extraction language mismatch: expected {}, detected {} for '{}' — retrying once",
                expectedLanguageCode, detectedLanguage, title);
        List<ExtractedSkill> retry;
        try {
            retry = validate(call(prompt, languageName, modelOverride, 0.0, true), languageName,
                    numberedLines, figureDescriptions == null ? 0 : figureDescriptions.size(), budget);
        } catch (IllegalArgumentException invalidResponse) {
            log.warn("Language retry for '{}' returned invalid fields or wording; keeping the valid first response: {}",
                    title, invalidResponse.getMessage());
            return first;
        }
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

    /** Rejects malformed structured output before taxonomy, embedding, or database persistence. */
    static List<ExtractedSkill> validate(List<ExtractedSkill> extracted, String languageName) {
        return validate(extracted, languageName, null, 0, MAX_SKILLS_PER_SESSION);
    }

    static List<ExtractedSkill> validate(List<ExtractedSkill> extracted, String languageName,
                                         NumberedLines numberedLines, int figureCount,
                                         int skillBudget) {
        if (extracted == null || extracted.isEmpty()) {
            return List.of();
        }
        if (extracted.size() > skillBudget) {
            throw new IllegalArgumentException("A unit of this size must not contain more than "
                    + skillBudget + " broad skills");
        }
        List<ExtractedSkill> valid = new ArrayList<>(extracted.size());
        for (ExtractedSkill skill : extracted) {
            if (skill == null) {
                throw new IllegalArgumentException("Every skill must have non-blank text");
            }
            OutcomeWording.validate(skill.text(), skill.shortLabel(), languageName, "Every skill");
            if (skill.kind() == null) {
                throw new IllegalArgumentException("Every skill must have a kind");
            }
            Integer skillFigure = offeredFigure(skill.sourceFigure(), figureCount);
            validateEvidence(skill.sourceStartLine(), skill.sourceEndLine(), skillFigure,
                    numberedLines, figureCount, "Every skill");
            List<ExtractedSkill.Knowledge> knowledgeItems = new ArrayList<>(skill.knowledge().size());
            for (ExtractedSkill.Knowledge knowledge : skill.knowledge()) {
                if (knowledge == null) {
                    throw new IllegalArgumentException("Every knowledge item must have non-blank text");
                }
                OutcomeWording.validate(knowledge.text(), knowledge.shortLabel(), languageName,
                        "Every knowledge item");
                if (knowledge.kind() == null) {
                    throw new IllegalArgumentException("Every knowledge item must have a kind");
                }
                Integer knowledgeFigure = offeredFigure(knowledge.sourceFigure(), figureCount);
                validateEvidence(knowledge.sourceStartLine(), knowledge.sourceEndLine(), knowledgeFigure,
                        numberedLines, figureCount, "Every knowledge item");
                knowledgeItems.add(new ExtractedSkill.Knowledge(
                        knowledge.text().strip(), blankToNull(knowledge.shortLabel()), knowledge.kind(),
                        knowledge.sourceStartLine(), knowledge.sourceEndLine(), knowledgeFigure));
            }
            valid.add(new ExtractedSkill(
                    skill.text().strip(), blankToNull(skill.shortLabel()), skill.kind(),
                    skill.sourceStartLine(), skill.sourceEndLine(), skillFigure, knowledgeItems));
        }
        return List.copyOf(valid);
    }

    /**
     * Whether the model pointed at the session's text but missed — as opposed to not pointing at all.
     *
     * <p>This is the line between the two failures salvage treats differently. An ascending,
     * in-bounds range that is merely too wide means the model named real material and drew the
     * bounds too far; the outcome is genuine and only its footnote is unusable. Evidence that is
     * missing, half-written, self-contradicting, or past the end of the text points nowhere at all,
     * and keeping those would let a session that ignored the citation contract through as a set of
     * silently ungrounded goals.
     */
    private static boolean pointsAtSourceImprecisely(ExtractedSkill skill, NumberedLines numberedLines) {
        return numberedLines != null
                && skill.sourceStartLine() != null
                && skill.sourceEndLine() != null
                && skill.sourceFigure() == null
                && numberedLines.isInBoundsButTooWide(skill.sourceStartLine(), skill.sourceEndLine());
    }

    private static void validateEvidence(Integer startLine, Integer endLine, Integer figure,
                                         NumberedLines numberedLines, int figureCount, String subject) {
        // The package-level validation overload is retained for focused wording tests whose fixtures
        // predate source fields. Production extraction always supplies NumberedLines here.
        if (numberedLines == null) {
            return;
        }
        boolean hasAnyLine = startLine != null || endLine != null;
        boolean hasCompleteLineRange = startLine != null && endLine != null;
        boolean hasFigure = figure != null;
        if (hasCompleteLineRange && hasFigure) {
            throw new IllegalArgumentException(subject + " must cite lines or a figure, never both");
        }
        if (hasAnyLine && !hasCompleteLineRange) {
            throw new IllegalArgumentException(subject + " must provide both sourceStartLine and sourceEndLine");
        }
        if (hasCompleteLineRange) {
            if (numberedLines.span(startLine, endLine).isEmpty()) {
                throw new IllegalArgumentException(subject + " has an invalid source range ["
                        + startLine + ".." + endLine + "]: "
                        + numberedLines.rejectionReason(startLine, endLine));
            }
            return;
        }
        if (hasFigure) {
            if (figure < 0 || figure >= figureCount) {
                throw new IllegalArgumentException(subject + " cites figure " + figure
                        + " but only " + figureCount + " figures were offered");
            }
            return;
        }
        throw new IllegalArgumentException(subject + " must cite a source line range or an offered figure");
    }

    private static Integer offeredFigure(Integer figure, int figureCount) {
        // Some structured-output models fill nullable integer fields with zero. When the request did
        // not offer any figures, that placeholder carries no evidence and must not invalidate a valid
        // line citation or leak into persistence.
        return figureCount > 0 ? figure : null;
    }

    /**
     * A complete structured response should normally validate atomically. After the correction retry,
     * however, one malformed child must not discard every other grounded outcome in the lecture. Keep
     * only skills and knowledge items that independently satisfy the same strict contract; if no skill
     * survives, the caller still fails the session.
     */
    static List<ExtractedSkill> salvageValidOutcomes(List<ExtractedSkill> extracted,
                                                             String languageName,
                                                             NumberedLines numberedLines,
                                                             int figureCount) {
        if (extracted == null || extracted.isEmpty()) {
            return List.of();
        }
        List<ExtractedSkill> valid = new ArrayList<>();
        for (ExtractedSkill skill : extracted) {
            if (valid.size() == 7) {
                break;
            }
            if (skill == null) {
                continue;
            }
            try {
                OutcomeWording.validate(skill.text(), skill.shortLabel(), languageName, "Every skill");
                if (skill.kind() == null) {
                    throw new IllegalArgumentException("Every skill must have a kind");
                }
            } catch (IllegalArgumentException invalidSkill) {
                continue;
            }
            // Evidence is judged separately from the outcome itself, because the two failures deserve
            // different answers. A skill whose WORDING is wrong is not usable and is dropped. A skill
            // that is well formed but cites its source badly is a real outcome with a bad footnote:
            // dropping it here would discard the outcome, and on a session where every skill cites
            // badly it empties the salvage and aborts the whole course.
            //
            // Measured: one real run died on "invalid source range [20..166]: spans more than 5
            // numbered lines" after both attempts, taking all 39 units of a 32-document course with
            // it. That is the whole extraction lost to a citation rule on one lecture.
            //
            // So the citation is DROPPED and the outcome kept. The range is never narrowed to fit —
            // picking five of the 146 lines the model pointed at would invent a citation nobody
            // verified, which is worse than admitting there is none. Without a range the goal
            // resolves as UNSUPPORTED, which the pipeline already models and counts, so it stays
            // visibly ungrounded rather than quietly looking sourced.
            boolean evidenceUsable = true;
            try {
                validateEvidence(skill.sourceStartLine(), skill.sourceEndLine(),
                        offeredFigure(skill.sourceFigure(), figureCount),
                        numberedLines, figureCount, "Every skill");
            } catch (IllegalArgumentException invalidEvidence) {
                if (!pointsAtSourceImprecisely(skill, numberedLines)) {
                    // Cited nothing, cited half a range, or cited lines and a figure at once. The
                    // model did not point anywhere, so there is nothing to keep it honest about.
                    continue;
                }
                evidenceUsable = false;
                log.warn("Keeping a skill whose citation could not be verified: {}",
                        invalidEvidence.getMessage());
            }

            Integer skillFigure = evidenceUsable ? offeredFigure(skill.sourceFigure(), figureCount) : null;

            List<ExtractedSkill.Knowledge> validKnowledge = new ArrayList<>();
            for (ExtractedSkill.Knowledge knowledge : skill.knowledge()) {
                if (knowledge == null) {
                    continue;
                }
                try {
                    OutcomeWording.validate(knowledge.text(), knowledge.shortLabel(), languageName,
                            "Every knowledge item");
                    if (knowledge.kind() == null) {
                        throw new IllegalArgumentException("Every knowledge item must have a kind");
                    }
                    Integer knowledgeFigure = offeredFigure(knowledge.sourceFigure(), figureCount);
                    validateEvidence(knowledge.sourceStartLine(), knowledge.sourceEndLine(),
                            knowledgeFigure, numberedLines, figureCount, "Every knowledge item");
                    validKnowledge.add(new ExtractedSkill.Knowledge(
                            knowledge.text().strip(), blankToNull(knowledge.shortLabel()), knowledge.kind(),
                            knowledge.sourceStartLine(), knowledge.sourceEndLine(), knowledgeFigure));
                } catch (IllegalArgumentException invalidKnowledge) {
                    // Retain the grounded parent and its other valid knowledge rather than the bad child.
                }
            }
            valid.add(evidenceUsable
                    ? new ExtractedSkill(
                            skill.text().strip(), blankToNull(skill.shortLabel()), skill.kind(),
                            skill.sourceStartLine(), skill.sourceEndLine(), skillFigure, validKnowledge)
                    : new ExtractedSkill(
                            skill.text().strip(), blankToNull(skill.shortLabel()), skill.kind(),
                            null, null, null, validKnowledge));
        }
        return List.copyOf(valid);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
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
