package de.tum.cit.hestia.learninggoalhub.extraction;

import de.tum.cit.hestia.learninggoalhub.document.LanguageDetectionService;
import de.tum.cit.hestia.learninggoalhub.document.PageDescriptionService;
import de.tum.cit.hestia.learninggoalhub.goal.BloomLevel;
import de.tum.cit.hestia.learninggoalhub.goal.GoalRole;
import de.tum.cit.hestia.learninggoalhub.goal.SoloLevel;
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

    static final String PROMPT_VERSION = "direct-v18";

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

    private static final String REQUIRED_FIELDS_RETRY = """

            Your previous response violated a required field or outcome-wording rule. Regenerate the
            COMPLETE response. Every skill and knowledge item must contain non-blank, distinct text
            and shortLabel values plus kind EXPLICIT or IMPLICIT. Follow the action-noun wording
            invariant exactly. Every outcome must cite either one valid 1-10-line source range from
            the numbered session text or one offered figure. Every outcome must carry a bloom and a
            solo value, spelled exactly as one of the enum names listed. Do not omit valid outcomes.
            Return only the structured JSON result.
            """;

    static final String PROMPT_TEMPLATE = """
            You analyse one part of a university lecture or exercise to identify what a student should
            be able to do after working through this material.

            Write every generated text and shortLabel value in %s. Keep the JSON property names
            text, shortLabel, kind, bloom, solo, sourceStartLine, sourceEndLine and knowledge exactly
            as written, and keep kind values exactly EXPLICIT or IMPLICIT. The source line indices
            refer to the numbered non-blank lines shown below; never translate the source text.

            Bloom's revised taxonomy (cognitive process an outcome targets):
              - REMEMBER: recall facts and basic concepts.
              - UNDERSTAND: explain ideas or concepts.
              - APPLY: use information in new situations.
              - ANALYZE: draw connections among ideas; break material into parts.
              - EVALUATE: justify a stance or decision.
              - CREATE: produce new or original work.

            SOLO taxonomy (structure of the observed learning outcome):
              - PRESTRUCTURAL: misses the point.
              - UNISTRUCTURAL: identifies one relevant aspect.
              - MULTISTRUCTURAL: identifies several relevant aspects without integrating them.
              - RELATIONAL: integrates aspects into a coherent whole.
              - EXTENDED_ABSTRACT: generalises beyond the given context.

            Bloom is carried by the outcome's VERB, and only by the verb. Every outcome is written in
            the action-noun form that names what the student does with the material — "Understanding
            ...", "Applying ...", "Analysing ..." — so the verb you choose IS the level you report. Do
            not re-derive the level from the rest of the sentence: an outcome that names several
            facets, or ends on a clause about comparing or deriving something, is still UNDERSTAND
            when its verb is "Understanding". The topic an outcome covers is not its cognitive level,
            and a long outcome is not a higher one. SOLO is the opposite — it describes how the whole
            statement is structured, so read all of it. UNDERSTAND on Bloom with RELATIONAL on SOLO is
            a normal pairing, not a contradiction to resolve.

            SKILLS are what the student can DO with this material: outcomes at APPLY, ANALYZE, EVALUATE
            or CREATE. These are the objectives an instructor would put on a "learning objectives"
            slide, not a line-by-line inventory of every fact, step or example. Return at most %d.

            KNOWLEDGE is what the student must know to reach the skill above it: every fact,
            definition, method, step or distinction this material teaches that the skill genuinely
            rests on, each as its own item. Knowledge sits at REMEMBER or UNDERSTAND — it is what the
            student knows, never what they do. Include what is independently assessable; leave out
            incidental examples, asides and repetition. Before answering, check that everything each
            skill actually rests on appears beneath it; a passage the skill depends on that no
            knowledge item cites is material you have silently dropped.

            Every knowledge item is itself a learning outcome and MUST use the expanded action-noun
            form naming what the student does with it — "Explaining", "Describing", "Identifying",
            "Naming", "Recalling", "Stating". Never state a bare fact, and never use a verb above
            UNDERSTAND ("Distinguishing", "Comparing", "Deriving", "Evaluating", "Constructing") for a
            knowledge item: if the student genuinely performs that action, it is a skill, not knowledge.
              WRONG: "The optimal variable ordering problem is NP-complete"
              RIGHT: "Explaining why finding the optimal variable ordering is NP-complete"

            MATERIAL THAT TEACHES NO SKILL IS NORMAL. Much lecture material explains rather than asks
            the student to perform anything, and that material has no APPLY-or-above outcome in it.
            Return an empty list when that is the case. Do not manufacture a skill by escalating a verb
            to have something to report — an invented "Applying ..." over material the student is only
            expected to follow is worse than returning nothing.

            Choose each outcome's verb by what the STUDENT is expected to be able to do or know
            afterwards, not by the activity the material happens to show. Slides often derive, prove,
            demonstrate or work through something the student is only expected to understand: a worked
            derivation of an estimator, a demonstrated construction, or a proof usually means the
            student should "understand"/"explain" it, not "derive"/"construct"/"prove" it themselves.
            Reserve "apply", "compute", "construct", "derive", "design" or "evaluate" for material that
            genuinely asks the student to carry out that action. The reverse error counts too: material
            that does ask the student to perform something — an exercise, a task, a procedure they must
            execute — yields a skill, and reporting it as knowledge understates what is taught.

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
              - bloom: APPLY, ANALYZE, EVALUATE or CREATE, matching the verb of text.
              - solo: one of the SOLO enum names above.
              - sourceStartLine and sourceEndLine: the inclusive zero-based index range of the numbered
                lines shown below that best supports the outcome, selected from ONE contiguous place
                in the text. Usually select 1-3 lines; never select more than 10 lines and never combine
                separate passages. A heading and the bullet points beneath it are SEPARATE passages,
                even when they sit together on one slide: pick one, never combine them. The indices
                MUST come from the numbered lines shown below.
              - knowledge: every knowledge item underpinning this skill, each with its own text,
                shortLabel, kind, bloom (REMEMBER or UNDERSTAND), solo, sourceStartLine and
                sourceEndLine. The wording invariant and every source-line rule above apply to
                knowledge items exactly as they do to skills.

            Do not invent outcomes that are not supported by the text. Do not promote a demonstrated
            derivation, proof or construction into an expected student action unless the text explicitly
            requires students to perform it. Return skills in the order in which their supporting
            material first appears in the material below; order each skill's knowledge the same way.

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
                languageName, budget, title, numberedSessionText);
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
            noteMissingLevels(skill.bloom(), skill.solo(), "A skill");
            noteTier(skill.bloom(), GoalRole.SKILL, skill.text());
            Citation skillCitation = validateEvidence(skill.sourceStartLine(), skill.sourceEndLine(),
                    offeredFigure(skill.sourceFigure(), figureCount),
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
                noteMissingLevels(knowledge.bloom(), knowledge.solo(), "A knowledge item");
                noteTier(knowledge.bloom(), GoalRole.KNOWLEDGE, knowledge.text());
                Citation knowledgeCitation = validateEvidence(
                        knowledge.sourceStartLine(), knowledge.sourceEndLine(),
                        offeredFigure(knowledge.sourceFigure(), figureCount),
                        numberedLines, figureCount, "Every knowledge item");
                knowledgeItems.add(new ExtractedSkill.Knowledge(
                        knowledge.text().strip(), blankToNull(knowledge.shortLabel()), knowledge.kind(),
                        knowledge.bloom(), knowledge.solo(),
                        knowledgeCitation.startLine(), knowledgeCitation.endLine(),
                        knowledgeCitation.figure()));
            }
            valid.add(new ExtractedSkill(
                    skill.text().strip(), blankToNull(skill.shortLabel()), skill.kind(),
                    skill.bloom(), skill.solo(),
                    skillCitation.startLine(), skillCitation.endLine(), skillCitation.figure(),
                    knowledgeItems));
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
    private static boolean pointsAtSourceImprecisely(ExtractedSkill skill, NumberedLines numberedLines,
                                                     int figureCount) {
        return numberedLines != null
                && skill.sourceStartLine() != null
                && skill.sourceEndLine() != null
                && !offersFigure(skill.sourceFigure(), figureCount)
                && numberedLines.isInBoundsButTooWide(skill.sourceStartLine(), skill.sourceEndLine());
    }

    /**
     * Records, without rejecting, an outcome that came back without a level.
     *
     * <p>Throwing here was stricter than anything downstream asks for: both level columns are
     * nullable and the classification call this replaced already persisted goals unlevelled whenever
     * it failed. The cost of the strictness is not one outcome. A session whose skills ALL lack a
     * level empties the salvage, and an empty salvage aborts the whole course — so a model that
     * simply never learned to emit {@code solo} would take the run down, which is the same failure
     * the invented-enum fix in {@link de.tum.cit.hestia.learninggoalhub.llm.LenientJson} exists to
     * prevent. The outcome is kept unlevelled and counted instead.
     */
    private static void noteMissingLevels(BloomLevel bloom, SoloLevel solo, String subject) {
        if (bloom == null) {
            log.warn("{} came back without a bloom level", subject);
        }
        if (solo == null) {
            log.warn("{} came back without a solo level", subject);
        }
    }

    /**
     * Records, without rejecting, an outcome whose level contradicts the tier it was returned in.
     *
     * <p>The prompt asks for skills at APPLY or above and knowledge at UNDERSTAND or below, and
     * whether a model can honour that on material which teaches no performance is the open question
     * this design is meant to answer. Rejecting the violation would answer it by hiding it: the unit
     * would be re-asked, then salvaged, and the run would report only what survived. A course was
     * already lost once to a validator enforcing a rule the model could not satisfy, so the tier is
     * observed here and enforced nowhere — the counts in the log are the measurement.
     */
    private static void noteTier(BloomLevel bloom, GoalRole role, String text) {
        if (bloom == null) {
            return;
        }
        boolean doing = bloom.ordinal() >= BloomLevel.APPLY.ordinal();
        if (role == GoalRole.SKILL && !doing) {
            log.warn("Skill returned below APPLY ({}): {}", bloom, text);
        } else if (role == GoalRole.KNOWLEDGE && doing) {
            log.warn("Knowledge item returned above UNDERSTAND ({}): {}", bloom, text);
        }
    }

    /** One outcome's citation once validated: at most one of a line range or a figure survives. */
    record Citation(Integer startLine, Integer endLine, Integer figure) {

        static final Citation NONE = new Citation(null, null, null);
    }

    /**
     * Validates one outcome's citation and returns the half of it that stands.
     *
     * <p>Text first, figure as the fallback — the same precedence {@code resolveDirectSource} applies
     * when it turns a citation into a stored source. A model that cites both was asked for one and
     * gave two, and where either half points at real material the outcome is grounded; the redundant
     * half is dropped rather than the outcome. Figure descriptions make that common: what a slide
     * teaches is in the picture, so the model names the picture and the line that captions it, and on
     * a course run with figures enabled two sessions died of exactly that — every skill cited both,
     * salvage kept none, and the run aborted.
     *
     * <p>Citing nothing, or citing only things that point nowhere — half a range, a range past the
     * end of the text, a figure that was never offered — still fails. The difference is between a
     * model that over-answered the citation contract and one that ignored it.
     */
    private static Citation validateEvidence(Integer startLine, Integer endLine, Integer figure,
                                             NumberedLines numberedLines, int figureCount, String subject) {
        // The package-level validation overload is retained for focused wording tests whose fixtures
        // predate source fields. Production extraction always supplies NumberedLines here.
        if (numberedLines == null) {
            return new Citation(startLine, endLine, figure);
        }
        boolean hasAnyLine = startLine != null || endLine != null;
        boolean hasCompleteLineRange = startLine != null && endLine != null;
        if (hasCompleteLineRange && numberedLines.span(startLine, endLine).isPresent()) {
            return new Citation(startLine, endLine, null);
        }
        if (offersFigure(figure, figureCount)) {
            return new Citation(null, null, figure);
        }
        if (hasCompleteLineRange) {
            throw new IllegalArgumentException(subject + " has an invalid source range ["
                    + startLine + ".." + endLine + "]: "
                    + numberedLines.rejectionReason(startLine, endLine));
        }
        if (hasAnyLine) {
            throw new IllegalArgumentException(subject + " must provide both sourceStartLine and sourceEndLine");
        }
        if (figure != null) {
            throw new IllegalArgumentException(subject + " cites figure " + figure
                    + " but only " + figureCount + " figures were offered");
        }
        throw new IllegalArgumentException(subject + " must cite a source line range or an offered figure");
    }

    private static boolean offersFigure(Integer figure, int figureCount) {
        return figure != null && figure >= 0 && figure < figureCount;
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
                if (skill.kind() == null) {
                    throw new IllegalArgumentException("Every skill must have a kind");
                }
                noteMissingLevels(skill.bloom(), skill.solo(), "A skill");
                noteTier(skill.bloom(), GoalRole.SKILL, skill.text());
                keepIfOnlyTheLabelReadsBadly(skill.text(), skill.shortLabel(), languageName, "Every skill");
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
            Citation skillCitation;
            try {
                skillCitation = validateEvidence(skill.sourceStartLine(), skill.sourceEndLine(),
                        offeredFigure(skill.sourceFigure(), figureCount),
                        numberedLines, figureCount, "Every skill");
            } catch (IllegalArgumentException invalidEvidence) {
                if (!pointsAtSourceImprecisely(skill, numberedLines, figureCount)) {
                    // Cited nothing, or cited only things that point nowhere. The model did not name
                    // any material, so there is nothing to keep it honest about.
                    continue;
                }
                skillCitation = Citation.NONE;
                log.warn("Keeping a skill whose citation could not be verified: {}",
                        invalidEvidence.getMessage());
            }

            List<ExtractedSkill.Knowledge> validKnowledge = new ArrayList<>();
            for (ExtractedSkill.Knowledge knowledge : skill.knowledge()) {
                if (knowledge == null) {
                    continue;
                }
                try {
                    if (knowledge.kind() == null) {
                        throw new IllegalArgumentException("Every knowledge item must have a kind");
                    }
                    noteMissingLevels(knowledge.bloom(), knowledge.solo(), "A knowledge item");
                    noteTier(knowledge.bloom(), GoalRole.KNOWLEDGE, knowledge.text());
                    keepIfOnlyTheLabelReadsBadly(knowledge.text(), knowledge.shortLabel(), languageName,
                            "Every knowledge item");
                    Citation knowledgeCitation = validateEvidence(
                            knowledge.sourceStartLine(), knowledge.sourceEndLine(),
                            offeredFigure(knowledge.sourceFigure(), figureCount),
                            numberedLines, figureCount, "Every knowledge item");
                    validKnowledge.add(new ExtractedSkill.Knowledge(
                            knowledge.text().strip(), blankToNull(knowledge.shortLabel()), knowledge.kind(),
                            knowledge.bloom(), knowledge.solo(),
                            knowledgeCitation.startLine(), knowledgeCitation.endLine(),
                            knowledgeCitation.figure()));
                } catch (IllegalArgumentException invalidKnowledge) {
                    // Retain the grounded parent and its other valid knowledge rather than the bad child.
                }
            }
            valid.add(new ExtractedSkill(
                    skill.text().strip(), blankToNull(skill.shortLabel()), skill.kind(),
                    skill.bloom(), skill.solo(),
                    skillCitation.startLine(), skillCitation.endLine(), skillCitation.figure(),
                    validKnowledge));
        }
        return List.copyOf(valid);
    }

    /**
     * Passes an outcome whose only defect is a shortLabel that repeats its text.
     *
     * <p>The label is a caption for the tree, not the outcome. Rejecting one costs the outcome and,
     * on a session where every skill captions itself the same way, the whole run — which is exactly
     * how a measured run died at 70%. Anything wrong with the text itself still throws.
     */
    private static void keepIfOnlyTheLabelReadsBadly(String text, String shortLabel, String languageName,
                                                     String subject) {
        try {
            OutcomeWording.validate(text, shortLabel, languageName, subject);
        } catch (IllegalArgumentException invalidWording) {
            OutcomeWording.validateOutcomeText(text, shortLabel, languageName, subject);
            log.warn("Keeping an outcome whose shortLabel does not read as a distinct label: {}",
                    invalidWording.getMessage());
        }
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
