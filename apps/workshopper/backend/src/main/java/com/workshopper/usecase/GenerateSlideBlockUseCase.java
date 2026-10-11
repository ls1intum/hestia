package com.workshopper.usecase;

import com.workshopper.dto.ActivityBlockDto;
import com.workshopper.dto.ActivitySectionDto;
import com.workshopper.dto.LearningGoalPlanDto;
import com.workshopper.dto.WorkshopInputDto;
import com.workshopper.service.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class GenerateSlideBlockUseCase {
    private static final Logger log = LoggerFactory.getLogger(GenerateSlideBlockUseCase.class);
    private final LlmService llm;

    private static final Map<String, List<String>> FIXED_INSTRUCTIONS = new java.util.HashMap<>();
    private static final Map<String, String> FIXED_INSTRUCTION_TITLES = new java.util.HashMap<>();
    static {
        addFixedInstruction("groupdiscussion", "Group Discussion", List.of(
            "Form groups and briefly introduce your perspectives",
            "Listen actively and build upon your peers' points",
            "Summarize your group's consensus to share with the class"
        ));

        addFixedInstruction("roleplay", "Role Play", List.of(
            "Review your assigned character's goals and background",
            "Engage in the scenario staying true to your role",
            "Debrief the interaction and the challenges faced"
        ));

        addFixedInstruction("jigsaw", "Jigsaw Method", List.of(
            "Meet with your expert group to master your assigned topic",
            "Return to your home group as the designated expert",
            "Teach your topic to the home group and learn theirs"
        ));

        addFixedInstruction("peerreview", "Peer Review", List.of(
            "Exchange work with your assigned partner",
            "Evaluate their work using the provided rubric/criteria",
            "Provide constructive, actionable feedback"
        ));

        addFixedInstruction("casestudy", "Case Study", List.of(
            "Read the case individually and note key details",
            "Discuss the central problem and alternative solutions in groups",
            "Present your group's recommended action plan"
        ));

        addFixedInstruction("conceptmapping", "Concept Mapping", List.of(
            "Identify the core concepts related to the topic",
            "Draw lines connecting related concepts",
            "Label the connections to explain the relationships"
        ));
        
        addFixedInstruction("thinkpairshare", "Think-Pair-Share", List.of(
            "THINK: Consider the question silently",
            "PAIR: Discuss your thoughts with a partner",
            "SHARE: Present your joint conclusion to the class"
        ));
    }

    private static void addFixedInstruction(String key, String title, List<String> bullets) {
        FIXED_INSTRUCTIONS.put(key, bullets);
        FIXED_INSTRUCTION_TITLES.put(key, title);
    }


    public GenerateSlideBlockUseCase(LlmService llm) {
        this.llm = llm;
    }

    public List<Map<String, Object>> execute(ActivityBlockDto block,
                                                          WorkshopInputDto meta,
                                                          List<LearningGoalPlanDto> goals) throws Exception {
        String phase = block.phase() != null ? block.phase().toUpperCase().trim() : "";
        log.info("generateBlockSlides: phase='{}' phaseLabel='{}'", phase, block.phaseLabel());

        String normalizedPhase = phase == null ? "" : phase.toUpperCase();
        return switch (normalizedPhase) {
            case "ARRIVE"         -> generateWelcomeAndAgendaSlides(block, meta, goals);
            case "ACTIVATE"       -> generateActivateSlides(block, meta, goals);
            case "LEARNING_CYCLE" -> generateLearningCycleSlides(block, meta, goals);
            case "EVALUATE"       -> generateCheckUnderstandingSlides(block, meta, goals);
            case "SUMMARY"        -> generateSummarySlides(block, meta, goals);
            case "BREAK"          -> generateBreakSlides(block);
            // BUFFER and any unknown phase: no slides
            default -> {
                log.debug("No slides for phase '{}', skipping", phase);
                yield List.of();
            }
        };
    }

    private List<Map<String, Object>> generateBreakSlides(ActivityBlockDto block) {
        Map<String, Object> breakSlide = new java.util.LinkedHashMap<>();
        breakSlide.put("layout", "break");
        breakSlide.put("title", block.phaseLabel() != null ? block.phaseLabel() : "Break");
        breakSlide.put("subtitle", "Rest and Recharge");
        breakSlide.put("bullets", java.util.List.of("We will resume in " + block.duration() + " minutes."));
        breakSlide.put("group", "break");
        return java.util.List.of(breakSlide);
    }

    // ── Group 2 + 3: Welcome (LLM) + Agenda (pure Java) ─────────────────────

    /**
     * ARRIVE phase → Welcome slide (LLM-generated from block content) + Agenda slide (pure Java).
     *
     * <p>The Welcome slide reflects the actual welcome/arrival step from the timetable
     * (the block's objective, sub-steps, tone) rather than a hardcoded placeholder.
     * The Agenda slide is copied verbatim from {@code meta.learningGoals()}.
     */
    private List<Map<String, Object>> generateWelcomeAndAgendaSlides(ActivityBlockDto block,
                                                                       WorkshopInputDto meta,
                                                                       List<LearningGoalPlanDto> goals) throws Exception {
        List<Map<String, Object>> slides = new ArrayList<>();

        // ── Welcome slide (LLM-generated) ───────────────────────────────────
        String sysPrompt = """
                You are an expert instructional designer writing a single Welcome slide for an active-learning session.
                
                CONTENT TIERS (strictly enforced):
                1. Visible slide — student-facing. Address students warmly. No instructor instructions.
                2. Speaker notes — instructor-facing facilitation cues for this slide only.
                3. Invisible — session-level logistics stay in the timetable. Never create slides or notes for these.
                
                DENSITY PROFILE: Low-density. 1–3 welcoming sentences or 2–3 short bullet points.
                No bullet lists if a short paragraph suffices. Tone: warm, inviting, sets the session theme.
                
                Return ONLY a valid JSON object (not an array) matching this schema:
                {
                  "title": "Welcome to [session topic]",
                  "bullets": ["Short framing sentence or warm-up cue"],
                  "notes": "Instructor facilitation note for this slide (optional)"
                }
                """;

        Map<String, Object> welcomeSlide;
        try {
            StringBuilder sectionSteps = new StringBuilder();
            appendSectionSteps(sectionSteps, block);
            
            StringBuilder materials = new StringBuilder();
            appendMaterials(materials, meta, 6000);
            
            StringBuilder goalsStr = new StringBuilder();
            appendGoals(goalsStr, goals);

            Map<String, Object> model = Map.of(
                "blockLabel", block.phaseLabel() != null ? block.phaseLabel() : "Welcome",
                "blockObjective", block.objective() != null ? block.objective() : "",
                "sectionSteps", sectionSteps.toString(),
                "materials", materials.toString(),
                "goals", goalsStr.toString()
            );

            log.info("LLM call: Welcome slide for '{}'", block.phaseLabel());
            welcomeSlide = normalizeSlideMap(llm.generatePptxSlide("welcome", model));

        } catch (Exception e) {
            log.warn("LLM failed for Welcome slide, using fallback: {}", e.getMessage());
            welcomeSlide = new LinkedHashMap<>();
            welcomeSlide.put("title", "Welcome");
            welcomeSlide.put("bullets", List.of("Today's session is about to begin — welcome!"));
            welcomeSlide.put("notes", "");
        }
        welcomeSlide.put("group", "welcome");
        welcomeSlide.put("layout", "welcome");
        welcomeSlide.put("subtitle", block.phaseLabel() != null ? block.phaseLabel() : "Welcome");
        slides.add(welcomeSlide);

        // ── Agenda slide (pure Java — verbatim from learning goals) ─────────
        slides.add(buildAgendaSlide(block.phaseLabel(), goals, meta));

        return slides;
    }

    /** Pure Java agenda slide — no LLM. Sourced from meta.learningGoals() / goals list. */
    private Map<String, Object> buildAgendaSlide(String blockLabel, List<LearningGoalPlanDto> goals, WorkshopInputDto meta) {
        Map<String, Object> slide = new LinkedHashMap<>();
        slide.put("group", "agenda");
        slide.put("layout", "agenda");
        slide.put("subtitle", blockLabel != null ? blockLabel : "Agenda");
        slide.put("title", "Agenda");

        // Prefer enriched LearningGoalPlanDto list; fall back to raw meta strings
        List<String> bullets;
        if (goals != null && !goals.isEmpty()) {
            bullets = goals.stream()
                    .map(g -> g.goal() != null && !g.goal().isBlank() ? g.goal() : g.originalGoal())
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.toList());
        } else if (meta != null && meta.learningGoals() != null && !meta.learningGoals().isEmpty()) {
            bullets = new ArrayList<>(meta.learningGoals());
        } else {
            bullets = List.of("See session plan for today's agenda");
        }
        slide.put("bullets", bullets);
        slide.put("notes", "Agenda slide — verbatim session learning goals.");
        return slide;
    }

    // ── Group 4: Activate Prior Knowledge ────────────────────────────────────

    /**
     * ACTIVATE phase → optional lecture placeholder + optional activity slide.
     *
     * <p>An activity slide is only generated when the block has at least one associated
     * method/activity (same signal as {@code allMethods} logic elsewhere). When there is
     * no activity, only the lecture placeholder is returned (and may be omitted entirely
     * if also empty).
     */
    private List<Map<String, Object>> generateActivateSlides(ActivityBlockDto block,
                                                              WorkshopInputDto meta,
                                                              List<LearningGoalPlanDto> goals) throws Exception {
        List<Map<String, Object>> slides = new ArrayList<>();
        String label = block.phaseLabel() != null ? block.phaseLabel() : "Activate Prior Knowledge";
        String phaseName = "ACTIVATE";

        // ── Lecture placeholder (always present for ACTIVATE) ────────────────
        Map<String, Object> placeholder = new LinkedHashMap<>();
        placeholder.put("group", "activate_prior_knowledge");
        placeholder.put("layout", "lecture_placeholder");
        placeholder.put("subtitle", phaseName);
        placeholder.put("title", "[Placeholder] " + label);
        placeholder.put("bullets", List.of("Insert any framing/context lecture content here"));
        placeholder.put("notes", "Instructor's optional lecture/context slides before the activation activity.");
        slides.add(placeholder);

        // ── Activity slide — only if the block has an associated method ──────
        Set<String> allMethods = collectMethods(block);
        if (!allMethods.isEmpty()) {
            String sysPrompt = """
                    You are an expert instructional designer writing ONE Activate Prior Knowledge activity slide.
                    
                    CONTENT TIERS (strictly enforced):
                    1. Visible slide — student-facing. One open question/probe that activates prior knowledge.
                       No correct-answer framing. No instructor instructions.
                    2. Speaker notes — expected answers/misconceptions + explicit reminder NOT to confirm/correct yet.
                    3. Invisible — omit session-level logistics entirely.
                    
                    DENSITY PROFILE: Single-focus, low-density. ONE open prompt. No bullet lists.
                    
                    Your output MUST be a flat JSON object matching this schema (do NOT nest inside another object):
%s
                    
                    Return ONLY a valid JSON object (not an array).
                    """;
            
            String method = getPrimaryMethod(block);
            String actName = getActivityName(method);
            String schemaSnippet;
            if ("quizpolls".equals(method) || "quiz".equals(method) || "poll".equals(method)) {
                schemaSnippet = """
                    {
                      "layout": "live_poll",
                      "title": "%s: [Topic or previous topics]",
                      "pollQuestion": "the open activation question",
                      "pollOptions": ["A) ...", "B) ...", "C) ..."],
                      "notes": "a PLAIN STRING — concise bullet points of expected answers/misconceptions relevant only to this slide"
                    }
                    """.formatted(actName);
            } else {
                String layout = switch (method) {
                    case "thinkpairshare", "brainstorming", "designsprint", "prototypechallenge", "workedproblem" -> "activity_grid3";
                    case "groupdiscussion", "debate", "peerreview", "roleplay", "conceptmapping", "casestudy" -> "activity_sidebar";
                    case "qasession" -> "activity_q&a";
                    case "handsonpractice" -> "activity_sidebar";
                    case "quizpolls", "quiz", "poll" -> "live_poll";
                    default -> "activity_sidebar";
                };
                List<String> instructions = FIXED_INSTRUCTIONS.getOrDefault(method, List.of("Review the prompt", "Formulate your thoughts", "Prepare to share"));
                String instJson = "[\\\"" + String.join("\\\", \\\"", instructions) + "\\\"]";
                schemaSnippet = """
                    {
                      "layout": "%s",
                      "title": "%s: [Topic or previous topics]",
                      "activityPrompt": "the open activation question",
                      "activityInstructions": %s,
                      "activityOutputExpectation": "what students should be prepared to share",
                      "notes": "a PLAIN STRING — concise bullet points of expected answers/misconceptions relevant only to this slide"
                    }
                    """.formatted(layout, actName, instJson);
            }
            sysPrompt = sysPrompt.formatted(schemaSnippet);


            Map<String, Object> actSlide;
            try {
                StringBuilder sectionSteps = new StringBuilder();
                appendSectionSteps(sectionSteps, block);
                
                StringBuilder goalsList = new StringBuilder();
                appendGoalsList(goalsList, goals, meta);
                
                StringBuilder materials = new StringBuilder();
                appendMaterials(materials, meta, 6000);

                Map<String, Object> model = Map.of(
                    "blockLabel", label,
                    "duration", block.duration(),
                    "objective", block.objective() != null ? block.objective() : "",
                    "methods", String.join(", ", allMethods),
                    "sectionSteps", sectionSteps.toString(),
                    "goalsList", goalsList.toString(),
                    "materials", materials.toString(),
                    "schemaSnippet", schemaSnippet
                );

                log.info("LLM call: Activate slide for '{}'", label);
                actSlide = normalizeSlideMap(llm.generatePptxSlide("activate", model));

            } catch (Exception e) {
                log.warn("LLM failed for Activate slide, using fallback: {}", e.getMessage());
                actSlide = new LinkedHashMap<>();
                actSlide.put("layout", "activity_sidebar");
                actSlide.put("title", label);
                actSlide.put("activityPrompt", "What do you already know about today's topic?");
                actSlide.put("activityInstructions", List.of("THINK (1m): Reflect silently.", "PAIR (2m): Discuss with a partner.", "SHARE (1m): Present to the group."));
                actSlide.put("activityOutputExpectation", "Be prepared to share your prior understanding.");
                actSlide.put("notes", "");
            }
            actSlide.put("group", "activate_prior_knowledge");
            actSlide.put("topic", label);
            actSlide.put("subtitle", "ACTIVATE");
            actSlide.put("activityName", actName);
            if (!actSlide.containsKey("layout")) actSlide.put("layout", "activity_sidebar");
            slides.add(actSlide);
        }

        return slides;
    }

    // ── Group 5: Main Lecture (3 slides per LEARNING_CYCLE block) ────────────

    /**
     * LEARNING_CYCLE phase → exactly 3 slides per call:
     * <ol>
     *   <li>Lecture placeholder (no LLM body content) — instructor inserts own lecture here.</li>
     *   <li>Activity slide (LLM-generated, {@code activity_tiled} layout).</li>
     *   <li>Per-cycle Summary/Debrief slide (LLM-generated, {@code debrief} layout).</li>
     * </ol>
     * All three slides carry {@code lgIndex} from {@code block.lgIndex()}.
     */
    private List<Map<String, Object>> generateLearningCycleSlides(ActivityBlockDto block,
                                                                    WorkshopInputDto meta,
                                                                    List<LearningGoalPlanDto> goals) throws Exception {
        List<Map<String, Object>> slides = new ArrayList<>();
        String label = block.phaseLabel() != null ? block.phaseLabel() : "Learning Cycle";
        String phaseName = "LEARNING CYCLE";
        int lgIndex = block.goalTag() != null ? parseLgIndex(block.goalTag()) : 0;
        String lgText = resolveGoalText(lgIndex, goals, meta);

        // ── Slide 1: Lecture placeholder (no LLM) ───────────────────────────
        Map<String, Object> lecturePlaceholder = new LinkedHashMap<>();
        lecturePlaceholder.put("group", "main_lecture");
        lecturePlaceholder.put("layout", "lecture_placeholder");
        if (lgIndex > 0) lecturePlaceholder.put("lgIndex", lgIndex);
        lecturePlaceholder.put("subtitle", label);
        lecturePlaceholder.put("title", label);
        lecturePlaceholder.put("bullets", List.of("Insert instructor's lecture content for: " + label));
        lecturePlaceholder.put("notes", "Instructor's own lecture slides for this learning goal. Replace with actual content.");
        slides.add(lecturePlaceholder);

        // ── Slides 2 + 3: Activity and Debrief (one LLM call) ───────────────
        String sysPrompt = """
                You are an expert instructional designer writing exactly TWO slides for one learning cycle.
                
                CONTENT TIERS (strictly enforced):
                1. Visible slide — student-facing. Never contains instructor instructions, answer keys, or facilitation logistics.
                2. Speaker notes — instructor-facing (answer keys, facilitation cues, common misconceptions for THAT slide).
                3. Invisible — omit session-level logistics entirely.
                
                You must return a JSON ARRAY of exactly 2 slide objects in this order:
                
%s
                
                SLIDE 2 — Per-cycle Debrief slide:
                  "layout": "debrief"
                  "title": "Debrief: [Topic]"
                  "suggestedAnswer": short, student-facing correct answer. IMPORTANT: If the activity was a poll, explicitly state the correct option letter (e.g., 'Correct Answer: A') followed by a brief explanation.
                  "commonMisconceptions": ["Misconception 1", "Misconception 2"] (array of EXACTLY 2 short common mistakes/gaps)
                  "keyTakeaway": "One main insight participants should leave with."
                  "notes": a PLAIN STRING — concise bullet points of suggested debrief facilitation technique (short, relevant only to this slide).
                    CRITICAL: "notes" MUST be a flat string, NOT a JSON object or nested structure.
                
                Return ONLY a valid JSON array of 2 objects. No prose.
                """.formatted(buildActivitySlidePrompt(block));

        Set<String> allMethods = collectMethods(block);
        log.info("LLM call: Learning cycle slides for '{}' (lgIndex={})", label, lgIndex);
        List<Map<String, Object>> llmSlides;
        try {
            StringBuilder sectionSteps = new StringBuilder();
            appendSectionSteps(sectionSteps, block);
            
            StringBuilder goalsList = new StringBuilder();
            appendGoalsList(goalsList, goals, meta);
            
            StringBuilder materials = new StringBuilder();
            appendMaterials(materials, meta, 7000);

            Map<String, Object> model = Map.of(
                "blockLabel", label,
                "lgIndex", lgIndex,
                "lgText", lgText,
                "blockObjective", block.objective() != null ? block.objective() : "",
                "methods", String.join(", ", allMethods),
                "sectionSteps", sectionSteps.toString(),
                "goalsList", goalsList.toString(),
                "materials", materials.toString(),
                "schemaSnippet", buildActivitySlidePrompt(block)
            );

            llmSlides = normalizeSlides(llm.generatePptxSlides("learning-cycle", model));

        } catch (Exception e) {
            log.warn("LLM failed for learning cycle slides, using fallback: {}", e.getMessage());
            llmSlides = buildLearningCycleFallback(label);
        }

        // Tag and sanitize LLM output
        String[] expectedLayouts = {"activity_sidebar", "debrief"};
        String method = getPrimaryMethod(block);
        String actName = getActivityName(method);
        for (int i = 0; i < Math.min(llmSlides.size(), 2); i++) {
            Map<String, Object> slide = llmSlides.get(i);
            slide.put("group", "main_lecture");
            slide.put("topic", label);
            slide.put("subtitle", "LECTURE");
            if (!slide.containsKey("layout")) slide.put("layout", expectedLayouts[i]);
            slide.put("activityName", "debrief".equals(slide.get("layout")) ? "🔄 DEBRIEF" : actName);
            if (lgIndex > 0) slide.put("lgIndex", lgIndex);
            slides.add(slide);
        }
        // Ensure we always have the debrief slide even if LLM returned only 1
        while (slides.size() < 3) {
            Map<String, Object> debriefFallback = new LinkedHashMap<>();
            debriefFallback.put("group", "main_lecture");
            debriefFallback.put("layout", "debrief");
            debriefFallback.put("subtitle", label);
            if (lgIndex > 0) debriefFallback.put("lgIndex", lgIndex);
            debriefFallback.put("title", "Debrief: " + label);
            debriefFallback.put("suggestedAnswer", "The correct answer involves applying the main concept discussed prior to this activity.");
            debriefFallback.put("commonMisconceptions", List.of("Students often confuse X with Y.", "Students might forget to apply Z."));
            debriefFallback.put("keyTakeaway", "Always remember to double-check the initial conditions.");
            debriefFallback.put("notes", "Invite 2–3 students to share. Correct misconceptions gently.");
            slides.add(debriefFallback);
        }

        return slides;
    }

    private List<Map<String, Object>> buildLearningCycleFallback(String label) {
        List<Map<String, Object>> fallback = new ArrayList<>();
        Map<String, Object> act = new LinkedHashMap<>();
        act.put("layout", "activity_sidebar");
        act.put("title", "Activity: " + label);
        act.put("activityPrompt", "Apply what you have just learned to the following problem.");
        act.put("activityInstructions", List.of("THINK (2m): Work independently.", "PAIR (3m): Compare with a partner.", "SHARE (1m): Present your conclusion."));
        act.put("activityOutputExpectation", "Be prepared to explain your reasoning.");
        act.put("notes", "See block notes for the correct answer and facilitation tips.");
        fallback.add(act);

        Map<String, Object> debrief = new LinkedHashMap<>();
        debrief.put("layout", "debrief");
        debrief.put("title", "Debrief: " + label);
        debrief.put("suggestedAnswer", "The correct answer involves applying the main concept discussed prior to this activity.");
        debrief.put("commonMisconceptions", List.of("Students often confuse X with Y.", "Students might forget to apply Z."));
        debrief.put("keyTakeaway", "Always remember to double-check the initial conditions.");
        debrief.put("notes", "Invite 2–3 students to share. Correct misconceptions gently.");
        fallback.add(debrief);

        return fallback;
    }

    // ── Group 6: Check Understanding (one slide per LG) ──────────────────────

    /**
     * EVALUATE phase → one poll question per session learning goal.
     *
     * <p>Covers ALL session learning goals sourced from {@code meta.learningGoals()} —
     * not just the LG associated with this particular timetable block.
     * Each slide carries an {@code lgIndex} field mapping it to its learning goal.
     *
     * <p>Each individual slide uses a simple single-question card layout rather than
     * the QR+chart two-panel style (which was designed for one whole-session poll).
     * With multiple slides in a row the simpler card reads better per the judgment
     * call called for in the spec.
     */
    private List<Map<String, Object>> generateCheckUnderstandingSlides(ActivityBlockDto block,
                                                                         WorkshopInputDto meta,
                                                                         List<LearningGoalPlanDto> goals) throws Exception {
        List<Map<String, Object>> slides = new ArrayList<>();
        String label = block.phaseLabel() != null ? block.phaseLabel() : "Understanding Check";
        String phaseName = "CHECK UNDERSTANDING";
        
        int lgIndex = block.goalTag() != null ? parseLgIndex(block.goalTag()) : 0;
        String lgText = resolveGoalText(lgIndex, goals, meta);

        Set<String> allMethods = collectMethods(block);
        if (!allMethods.isEmpty()) {
            for (String method : allMethods) {
                String cleanMethod = method.toLowerCase().replaceAll("[^a-z0-9]", "");
                String sysPrompt = """
                        You are an expert instructional designer writing exactly TWO slides for a Check Understanding activity.
                        
                        CONTENT TIERS (strictly enforced):
                        1. Visible slide — student-facing. One check understanding activity.
                        2. Speaker notes — expected answers/misconceptions.
                        3. Invisible — omit session-level logistics entirely.
                        
                        You must return a JSON ARRAY of exactly 2 slide objects in this order:
                        
%s
                        
                        SLIDE 2 — Debrief slide:
                          "layout": "debrief"
                          "title": "Debrief: [Topic]"
                          "suggestedAnswer": "short, student-facing correct answer. If a poll, state correct option."
                          "commonMisconceptions": ["Misconception 1", "Misconception 2"]
                          "keyTakeaway": "One main insight participants should leave with."
                          "notes": a PLAIN STRING — concise bullet points of suggested debrief facilitation technique (short, relevant only to this slide).
                        
                        Return ONLY a valid JSON array of 2 objects. No prose.
                        """.formatted(buildActivitySlidePrompt(cleanMethod));

                log.info("LLM call: Check Understanding slides for '{}' method='{}' (lgIndex={})", label, method, lgIndex);
                List<Map<String, Object>> llmSlides;
                try {
                    StringBuilder sectionSteps = new StringBuilder();
                    appendSectionSteps(sectionSteps, block);
                    
                    StringBuilder goalsList = new StringBuilder();
                    appendGoalsList(goalsList, goals, meta);
                    
                    StringBuilder materials = new StringBuilder();
                    appendMaterials(materials, meta, 6000);

                    Map<String, Object> model = Map.of(
                        "blockLabel", label,
                        "lgIndex", lgIndex,
                        "lgText", lgText,
                        "blockObjective", block.objective() != null ? block.objective() : "",
                        "methods", method,
                        "sectionSteps", sectionSteps.toString(),
                        "goalsList", goalsList.toString(),
                        "materials", materials.toString(),
                        "schemaSnippet", buildActivitySlidePrompt(cleanMethod)
                    );
                    llmSlides = normalizeSlides(llm.generatePptxSlides("evaluate", model));
                } catch (Exception e) {
                    log.warn("LLM failed for Check Understanding slide, using fallback: {}", e.getMessage());
                    llmSlides = buildLearningCycleFallback(label);
                }

                String actName = getActivityName(cleanMethod);
                for (int i = 0; i < Math.min(llmSlides.size(), 2); i++) {
                    Map<String, Object> slide = llmSlides.get(i);
                    slide.put("group", "check_understanding");
                    slide.put("topic", label);
                    slide.put("subtitle", "CHECK UNDERSTANDING");
                    if (!slide.containsKey("layout")) slide.put("layout", i == 0 ? "live_poll" : "debrief");
                    slide.put("activityName", "debrief".equals(slide.get("layout")) ? "🔄 DEBRIEF" : actName);
                    if (lgIndex > 0) slide.put("lgIndex", lgIndex);
                    slides.add(slide);
                }
                while (slides.size() < (allMethods.size() * 2) && llmSlides.size() < 2) {
                    Map<String, Object> debriefFallback = new LinkedHashMap<>();
                    debriefFallback.put("group", "check_understanding");
                    debriefFallback.put("layout", "debrief");
                    debriefFallback.put("subtitle", phaseName);
                    if (lgIndex > 0) debriefFallback.put("lgIndex", lgIndex);
                    debriefFallback.put("title", "Debrief: " + label);
                    debriefFallback.put("suggestedAnswer", "The correct answer involves applying the main concept.");
                    debriefFallback.put("commonMisconceptions", List.of("Students often confuse X with Y."));
                    debriefFallback.put("keyTakeaway", "Always remember to double-check the initial conditions.");
                    debriefFallback.put("notes", "Invite 2–3 students to share. Correct misconceptions gently.");
                    slides.add(debriefFallback);
                    break; // Just add one debrief to pad it out
                }
            }
        } else {
            // Fallback if no methods are present: generate one poll slide and one debrief
            Map<String, Object> fallbackAct = new LinkedHashMap<>();
            fallbackAct.put("layout", "live_poll");
            fallbackAct.put("group", "check_understanding");
            fallbackAct.put("subtitle", phaseName);
            fallbackAct.put("activityName", "✅ QUIZ");
            if (lgIndex > 0) fallbackAct.put("lgIndex", lgIndex);
            fallbackAct.put("title", label);
            fallbackAct.put("pollQuestion", "What is the most important concept you learned today?");
            fallbackAct.put("pollOptions", List.of("A) Concept A", "B) Concept B", "C) Concept C", "D) Concept D"));
            fallbackAct.put("notes", "Discuss with neighbor if split.");
            slides.add(fallbackAct);

            Map<String, Object> fallbackDebrief = new LinkedHashMap<>();
            fallbackDebrief.put("layout", "debrief");
            fallbackDebrief.put("group", "check_understanding");
            fallbackDebrief.put("subtitle", phaseName);
            if (lgIndex > 0) fallbackDebrief.put("lgIndex", lgIndex);
            fallbackDebrief.put("title", "Debrief: " + label);
            fallbackDebrief.put("suggestedAnswer", "Concept A is the most critical.");
            fallbackDebrief.put("commonMisconceptions", List.of("Misconception 1", "Misconception 2"));
            fallbackDebrief.put("keyTakeaway", "Always apply Concept A.");
            fallbackDebrief.put("notes", "Clarify any remaining doubts.");
            slides.add(fallbackDebrief);
        }

        return slides;
    }

    // ── Group 7: Summary + Thank You ─────────────────────────────────────────

    /**
     * SUMMARY phase → structured multi-part summary content + explicit closing "Thank you" slide.
     *
     * <p>The summary slides are structured multi-part (one-minute paper style, ending in
     * synthesis). The "Thank you" slide is appended as the final slide in the deck.
     */
    private List<Map<String, Object>> generateSummarySlides(ActivityBlockDto block,
                                                              WorkshopInputDto meta,
                                                              List<LearningGoalPlanDto> goals) throws Exception {
        List<Map<String, Object>> slides = new ArrayList<>();
        String label = block.phaseLabel() != null ? block.phaseLabel() : "Summary & Wrap-up";
        String phaseName = "SUMMARY";

        String sysPrompt = """
                You are an expert instructional designer writing the Summary & Wrap-up slides for a session.
                
                CONTENT TIERS (strictly enforced):
                1. Visible slide — student-facing. Synthesis/reflection prompts. Never instructor logistics.
                2. Speaker notes — time allocation, facilitation steps.
                3. Invisible — omit entirely.
                
                DENSITY PROFILE: Structured multi-part. Numbered or spatially distinct sub-points.
                The final slide in your array MUST be a "One-Minute Paper" reflection slide ending with:
                  1. What is the most important concept you learned today?
                  2. What is your biggest remaining question?
                
                Return a JSON ARRAY of 1–2 summary slide objects (NOT the Thank-You slide — that is added separately).
                
                Schema for the Summary slide (if needed):
                {
                  "title": "Summary & Wrap-Up",
                  "layout": "summary",
                  "bullets": ["Key takeaway 1", "Key takeaway 2", ...],
                  "notes": "Concise bullet points for facilitation steps + time allocation (short, relevant only to this slide)"
                }
                
                Schema for the One-Minute Paper slide:
                {
                  "title": "One Minute Paper",
                  "layout": "activity_q&a",
                  "activityPrompt": "What is the most important concept you learned today?\\n2. What is your biggest remaining question?",
                  "notes": "Concise bullet points for facilitation steps + time allocation (short, relevant only to this slide)"
                }
                
                Return ONLY a valid JSON array. No prose.
                """;

        log.info("LLM call: Summary slides for '{}'", label);
        List<Map<String, Object>> llmSlides;
        try {
            StringBuilder sectionSteps = new StringBuilder();
            appendSectionSteps(sectionSteps, block);
            
            StringBuilder goalsList = new StringBuilder();
            appendGoalsList(goalsList, goals, meta);
            
            StringBuilder materials = new StringBuilder();
            appendMaterials(materials, meta, 6000);

            Map<String, Object> model = Map.of(
                "blockLabel", label,
                "blockObjective", block.objective() != null ? block.objective() : "",
                "goals", goalsList.toString(),
                "sectionSteps", sectionSteps.toString(),
                "materials", materials.toString()
            );

            llmSlides = normalizeSlides(llm.generatePptxSlides("wrapup", model));

        } catch (Exception e) {
            log.warn("LLM failed for Summary slides, using fallback: {}", e.getMessage());
            llmSlides = buildSummaryFallback(label);
        }

        for (Map<String, Object> slide : llmSlides) {
            slide.put("group", "summary");
            slide.put("topic", label);
            slide.put("subtitle", phaseName);
            if (!slide.containsKey("layout")) slide.put("layout", "summary");
            if ("activity_q&a".equals(slide.get("layout"))) {
                slide.put("activityName", "🙋 ONE-MINUTE PAPER");
            }
            slides.add(slide);
        }

        // ── Explicit closing Agenda slide (re-review goals at the end) ────────
        Map<String, Object> closingAgenda = buildAgendaSlide("Review Learning Goals", goals, meta);
        closingAgenda.put("group", "summary");
        closingAgenda.put("isClosingAgenda", true);
        closingAgenda.put("notes", "Review the agenda / learning goals one last time to ensure all points were covered.");
        slides.add(closingAgenda);

        return slides;
    }

    private List<Map<String, Object>> buildSummaryFallback(String label) {
        Map<String, Object> omp = new LinkedHashMap<>();
        omp.put("title", "One Minute Paper");
        omp.put("layout", "activity_q&a");
        omp.put("activityPrompt", "What is the most important concept you learned today?\n2. What is your biggest remaining question?");
        omp.put("notes", "Give 1 min for silent writing. Invite 2–3 to share. Collect papers if desired.");
        return List.of(omp);
    }

    // =========================================================================
    // Private helpers — prompt assembly
    // =========================================================================

    private String getPrimaryMethod(ActivityBlockDto block) {
        if (block.methods() == null || block.methods().isEmpty()) return "";
        return block.methods().get(0).toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private String getActivityName(String method) {
        if (method == null) return "ACTIVITY";
        return switch (method) {
            case "thinkpairshare" -> "🧠 THINK-PAIR-SHARE";
            case "brainstorming" -> "🧠 BRAINSTORMING";
            case "designsprint", "prototypechallenge" -> "🎨 DESIGN SPRINT";
            case "groupdiscussion" -> "💬 GROUP DISCUSSION";
            case "debate" -> "🗣️ DEBATE";
            case "peerreview" -> "👥 PEER REVIEW";
            case "roleplay" -> "🎭 ROLE PLAY";
            case "qasession" -> "🙋 Q&A SESSION";
            case "handsonpractice" -> "🛠️ HANDS-ON PRACTICE";
            case "casestudy" -> "💼 CASE STUDY";
            case "conceptmapping" -> "🗺 CONCEPT MAPPING";
            case "workedproblem" -> "⚙️ WORKED PROBLEM";
            case "quizpolls", "quiz", "poll" -> "✅ QUIZ";
            default -> method.toUpperCase();
        };
    }

    private String buildActivitySlidePrompt(ActivityBlockDto block) {
        return buildActivitySlidePrompt(getPrimaryMethod(block));
    }

    private String buildActivitySlidePrompt(String method) {
        String actName = getActivityName(method);
        
        if ("quizpolls".equals(method) || "quiz".equals(method) || "poll".equals(method)) {
            return """
                SLIDE 1 — Activity slide:
                  "layout": "live_poll"
                  "title": "%s: [Learning Goal or Topic]"
                  "pollQuestion": "the student-facing question"
                  "pollOptions": ["A) ...", "B) ...", "C) ...", "D) ..."]
                  "notes": a PLAIN STRING — concise bullet points of the answer/reasoning and common wrong answers (short, relevant only to this slide).
                    CRITICAL: "notes" MUST be a flat string, NOT a JSON object or nested structure.
                """.formatted(actName);
        }

        String layout = switch (method) {
            case "thinkpairshare", "brainstorming", "designsprint", "prototypechallenge", "workedproblem" -> "activity_grid3";
            case "groupdiscussion", "debate", "peerreview", "roleplay", "conceptmapping", "casestudy" -> "activity_sidebar";
            case "qasession" -> "activity_q&a";
            case "handsonpractice" -> "activity_sidebar";
            case "quizpolls", "quiz", "poll" -> "live_poll";
            default -> "activity_sidebar";
        };

        String instJson;
        if (FIXED_INSTRUCTIONS.containsKey(method)) {
            instJson = "[\\\"" + String.join("\\\", \\\"", FIXED_INSTRUCTIONS.get(method)) + "\\\"]";
        } else if ("activity_grid3".equals(layout)) {
            instJson = "[\\\"[short action 1]\\\", \\\"[short action 2]\\\", \\\"[short action 3]\\\"] (CRITICAL: DO NOT use numbers. Output EXACTLY 3 short actions corresponding to the 3 phases of the activity. Put all details in 'notes')";
        } else {
            instJson = "[\\\"[short action phrase e.g. map nodes]\\\", \\\"[short action e.g. read prompt and deliver]\\\"] (CRITICAL: DO NOT use numbers like '1.' or 'Step 1'. DO NOT use generic filler like 'Read the scenario'. Output ONLY 1-2 extremely concise, specific actions. Put all details in the 'notes' field.)";
        }

        return """
                SLIDE 1 — Activity slide:
                  "layout": "%s"
                  "title": "%s: [Learning Goal or Topic]"
                  "activityPrompt": "the main question/task (MUST be a short 1-2 sentence summary, do NOT include instructions here)",
                  "activityInstructions": %s
                  "activityOutputExpectation": "what students will present/submit (if applicable, else omit)"
                  "notes": a PLAIN STRING — concise bullet points of answer/reasoning and brief step-by-step instructions for the instructor (relevant only to this slide, keep it short).
                    CRITICAL: "notes" MUST be a flat string, NOT a JSON object or nested structure.
                """.formatted(layout, actName, instJson);
    }

    /**
     * Coerce a value that should be a plain string into a String.
     *
     * <p>The LLM occasionally wraps string fields in an object (e.g. {@code {"text":"…"}})
     * or returns a list instead of a scalar. This normalizer ensures the frontend always
     * receives a real string, never an object or array that JSON-serialises to
     * {@code [object Object]}.
     */
    private String coerceToString(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return s;
        if (value instanceof java.util.List<?> list) {
            // Join list items into a newline-separated string
            return list.stream()
                    .map(item -> item == null ? "" : coerceToString(item))
                    .filter(s -> !s.isBlank())
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
        if (value instanceof Map<?, ?> map) {
            // Priority 1: single-string shortcut keys the LLM commonly uses
            for (String key : new String[]{"text", "content", "value", "notes", "summary"}) {
                Object v = map.get(key);
                if (v instanceof String s && !s.isBlank()) return s;
            }
            // Priority 2: format all entries as human-readable "Label: value" lines
            // (handles structured notes like {answerKey:..., debriefTechnique:..., commonWrongAnswers:[...]})
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String label = toReadableLabel(String.valueOf(entry.getKey()));
                String entryValue = coerceToString(entry.getValue());
                if (entryValue == null || entryValue.isBlank()) continue;
                if (sb.length() > 0) sb.append("\n");
                sb.append(label).append(": ").append(entryValue);
            }
            return sb.toString();
        }
        return value.toString();
    }

    /**
     * Convert a camelCase or snake_case key into a Title Case label.
     * e.g. "answerKey" → "Answer Key", "common_wrong_answers" → "Common Wrong Answers"
     */
    private String toReadableLabel(String key) {
        // Split on camelCase boundaries and underscores/hyphens
        String spaced = key
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replaceAll("[_\\-]+", " ")
                .trim();
        if (spaced.isEmpty()) return key;
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /**
     * Normalise all known scalar string fields in a slide map so the frontend
     * always receives plain strings, never objects or arrays in those fields.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeSlideMap(Map<String, Object> slide) {
        // Fields that must always be plain strings
        for (String field : new String[]{"title", "subtitle", "notes", "activityPrompt",
                "activityOutputExpectation", "pollQuestion", "layout", "group", "suggestedAnswer", "keyTakeaway"}) {
            Object v = slide.get(field);
            if (v != null && !(v instanceof String)) {
                slide.put(field, coerceToString(v));
            }
        }
        // activityInstructions and pollOptions must be List<String>
        for (String field : new String[]{"activityInstructions", "pollOptions", "bullets", "commonMisconceptions"}) {
            Object v = slide.get(field);
            if (v instanceof java.util.List<?> list) {
                slide.put(field, list.stream()
                        .map(item -> item == null ? "" : (item instanceof String s ? s : coerceToString(item)))
                        .collect(java.util.stream.Collectors.toList()));
            } else if (v != null && !(v instanceof java.util.List<?>)) {
                // Scalar where a list was expected — wrap it
                slide.put(field, java.util.List.of(coerceToString(v)));
            }
        }
        return slide;
    }

    /** Apply {@link #normalizeSlideMap} to every slide in a list (mutates in place). */
    private List<Map<String, Object>> normalizeSlides(List<Map<String, Object>> slides) {
        slides.forEach(this::normalizeSlideMap);
        return slides;
    }

    private void appendSectionSteps(StringBuilder sb, ActivityBlockDto block) {
        if (block.sections() == null || block.sections().isEmpty()) return;
        boolean hasSteps = block.sections().stream().anyMatch(s -> s.steps() != null && !s.steps().isEmpty());
        if (!hasSteps) return;
        sb.append("\nDetailed Activity Steps (source material — do NOT create one slide per step):\n");
        for (ActivitySectionDto sec : block.sections()) {
            if (sec.steps() == null || sec.steps().isEmpty()) continue;
            if (sec.title() != null && !sec.title().isBlank())
                sb.append("  [").append(sec.title()).append("]\n");
            for (String step : sec.steps())
                sb.append("    • ").append(step).append("\n");
        }
    }

    private void appendGoals(StringBuilder sb, List<LearningGoalPlanDto> goals) {
        if (goals == null || goals.isEmpty()) return;
        for (int i = 0; i < goals.size(); i++) {
            String g = goals.get(i).goal() != null ? goals.get(i).goal() : goals.get(i).originalGoal();
            if (g != null && !g.isBlank()) sb.append("  LG").append(i + 1).append(": ").append(g).append("\n");
        }
    }

    private void appendGoalsList(StringBuilder sb, List<LearningGoalPlanDto> goals, WorkshopInputDto meta) {
        sb.append("\nSession Learning Goals:\n");
        if (goals != null && !goals.isEmpty()) {
            appendGoals(sb, goals);
        } else if (meta != null && meta.learningGoals() != null) {
            for (int i = 0; i < meta.learningGoals().size(); i++)
                sb.append("  LG").append(i + 1).append(": ").append(meta.learningGoals().get(i)).append("\n");
        }
    }

    private void appendMaterials(StringBuilder sb, WorkshopInputDto meta, int maxLen) {
        if (meta == null || meta.uploadedMaterialsText() == null || meta.uploadedMaterialsText().isBlank()) return;
        String text = meta.uploadedMaterialsText();
        if (text.length() > maxLen) text = text.substring(0, maxLen) + "\n[...truncated]";
        sb.append("\nReference Materials:\n").append(text);
    }

    private Set<String> collectMethods(ActivityBlockDto block) {
        Set<String> methods = new LinkedHashSet<>();
        if (block.methods() != null) methods.addAll(block.methods());
        if (block.sections() != null) {
            for (ActivitySectionDto sec : block.sections()) {
                if (sec.methods() != null) methods.addAll(sec.methods());
            }
        }
        return methods;
    }

    private List<String> buildFullGoalStrings(List<LearningGoalPlanDto> goals, WorkshopInputDto meta) {
        if (goals != null && !goals.isEmpty()) {
            return goals.stream()
                    .map(g -> g.goal() != null && !g.goal().isBlank() ? g.goal() : g.originalGoal())
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.toList());
        }
        if (meta != null && meta.learningGoals() != null) {
            return meta.learningGoals().stream()
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    private String resolveGoalText(int lgIndex, List<LearningGoalPlanDto> goals, WorkshopInputDto meta) {
        if (lgIndex > 0 && goals != null && lgIndex <= goals.size()) {
            LearningGoalPlanDto g = goals.get(lgIndex - 1);
            return g.goal() != null ? g.goal() : (g.originalGoal() != null ? g.originalGoal() : "");
        }
        if (lgIndex > 0 && meta != null && meta.learningGoals() != null && lgIndex <= meta.learningGoals().size()) {
            return meta.learningGoals().get(lgIndex - 1);
        }
        return "";
    }

    /**
     * Parse an lgIndex from a goalTag string that may be "g1", "g2", "LG1", "1", etc.
     * Returns 0 if unparseable.
     */
    private int parseLgIndex(String goalTag) {
        if (goalTag == null || goalTag.isBlank()) return 0;
        String cleaned = goalTag.replaceAll("(?i)^[a-z]*", "").trim();
        try {
            return Integer.parseInt(cleaned);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
