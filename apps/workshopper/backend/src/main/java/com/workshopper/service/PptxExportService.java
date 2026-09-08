package com.workshopper.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workshopper.dto.ActivityBlockDto;
import com.workshopper.dto.ActivitySectionDto;
import com.workshopper.dto.LearningGoalPlanDto;
import com.workshopper.dto.PdfExportRequestDto;
import com.workshopper.dto.WorkshopInputDto;
import com.workshopper.dto.WorkshopSessionDto;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Converts a Workshopper timetable into a PPTX slide deck.
 *
 * <h2>Generation path</h2>
 * Slides are generated per slide-group (one scoped LLM call per group), then assembled
 * in the fixed 7-part order:
 * <ol>
 *   <li>Title slide — pure Java, from session.title()</li>
 *   <li>Welcome slides — LLM, from ARRIVE block</li>
 *   <li>Agenda slide — pure Java, from meta.learningGoals()</li>
 *   <li>Activate Prior Knowledge — LLM, from ACTIVATE block (activity conditional)</li>
 *   <li>Main Lecture — LLM, 3 slides per LEARNING_CYCLE block × N learning goals</li>
 *   <li>Check Understanding — LLM, one poll slide per learning goal</li>
 *   <li>Summary + Thank You — LLM, from SUMMARY block</li>
 * </ol>
 *
 * <p>The public entry point called by the frontend is {@link #generateBlockSlides}, which
 * dispatches to the appropriate per-group method based on {@code block.phase()}.
 * The frontend already parallelises these calls (concurrencyLimit=3 in SlideWorkstation.tsx)
 * so no additional orchestration is needed here.
 *
 * <h2>Slide JSON schema (extended)</h2>
 * <pre>
 * {
 *   "group":   "welcome" | "agenda" | "activate_prior_knowledge"
 *              | "main_lecture" | "check_understanding" | "summary",
 *   "lgIndex": 1,          // present on main_lecture and check_understanding slides
 *   "layout":  "default" | "activity_tiled" | "live_poll"
 *              | "lecture_placeholder" | "debrief" | "concept_map",
 *   "title":   "...",
 *   "subtitle":"...",
 *   "bullets": ["..."],    // for default / concept_map
 *   "activityPrompt":      "...",  // activity_tiled only
 *   "activityInstructions":["..."],// activity_tiled only
 *   "activityOutputExpectation":"...", // activity_tiled only
 *   "debriefQuestion":     "...",  // debrief only
 *   "pollQuestion":        "...",  // live_poll only
 *   "pollOptions":         ["..."],// live_poll only
 *   "notes":   "..."
 * }
 * </pre>
 */
@Service
public class PptxExportService {

    private static final Logger log = LoggerFactory.getLogger(PptxExportService.class);
    private final LlmService llm;
    private final ObjectMapper mapper = new ObjectMapper();

    // ── Hestia brand colors ────────────────────────────────────────────────────
    private static final java.awt.Color HESTIA_PRIMARY        = new java.awt.Color(135, 84, 29);
    private static final java.awt.Color HESTIA_PRIMARY_LIGHT  = new java.awt.Color(200, 155, 90);
    private static final java.awt.Color HESTIA_FOREGROUND     = new java.awt.Color(44, 39, 37);
    private static final java.awt.Color HESTIA_BG             = new java.awt.Color(242, 237, 228);
    private static final java.awt.Color HESTIA_SEPARATOR      = new java.awt.Color(218, 208, 193);

    // ── Phase accent colors (from reference HTML: --hestia-phase-*) ───────────
    /** Setup / logistics slides: Title, Welcome, Agenda, Check Understanding */
    private static final java.awt.Color PHASE_SETUP   = new java.awt.Color(37, 99, 235);    // #2563EB
    /** Lecture content slides: Lecture placeholders, Summary */
    private static final java.awt.Color PHASE_LECTURE = new java.awt.Color(109, 40, 217);   // #6D28D9
    /** Practice / activity slides: Activity, Debrief */
    private static final java.awt.Color PHASE_PRACTICE = new java.awt.Color(5, 150, 105);   // #059669

    // ── Fixed per-activity instructions (carried over unchanged) ──────────────
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
            "Stay in character and respond naturally to the scenario",
            "Step out of character afterwards to debrief the experience"
        ));
        addFixedInstruction("handsonpractice", "Hands-on Practice", List.of(
            "Attempt the task independently using provided materials",
            "Ask questions immediately if you hit a blocking issue",
            "Compare your solution with peers or the reference solution"
        ));
        addFixedInstruction("quizpolls", "Quiz / Polls", List.of(
            "Read the question and all options carefully",
            "Answer honestly based on your current understanding",
            "Discuss the correct answer when revealed by the instructor"
        ));
        addFixedInstruction("qasession", "Q&A Session", List.of(
            "Formulate your question clearly and specifically",
            "Raise your hand or use the digital Q&A tool to submit it",
            "Listen to others' questions to avoid duplicates"
        ));
        addFixedInstruction("peerreview", "Peer Review", List.of(
            "Review your partner's work thoroughly and objectively",
            "Provide specific, actionable, and constructive feedback",
            "Discuss the feedback together to clarify misunderstandings"
        ));
        addFixedInstruction("brainstorming", "Brainstorming", List.of(
            "Share every idea that comes to mind, no matter how unusual",
            "Focus on quantity first, without filtering or judging",
            "Categorize and evaluate the ideas only after brainstorming ends"
        ));
        addFixedInstruction("thinkpairshare", "Think-Pair-Share", List.of(
            "THINK: Reflect silently on the prompt and note your thoughts",
            "PAIR: Discuss your reflections with a partner and compare views",
            "SHARE: Present your pair's conclusions to the entire group"
        ));
    }

    private static void addFixedInstruction(String key, String title, List<String> bullets) {
        FIXED_INSTRUCTIONS.put(key, bullets);
        FIXED_INSTRUCTION_TITLES.put(key, title);
    }

    public PptxExportService(LlmService llm) {
        this.llm = llm;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /** Export full session to PPTX (uses pre-built slides from cache). */
    public byte[] exportToPptx(PdfExportRequestDto request, java.io.InputStream templateStream) throws Exception {
        return exportToPptxInternal(request.session(), request.meta(), null, templateStream);
    }

    /**
     * Assemble PPTX from pre-built slides (no LLM call).
     * Called when the frontend already has all slide data cached.
     */
    public byte[] assembleFromSlides(WorkshopSessionDto session, WorkshopInputDto meta,
                                     List<Map<String, Object>> prebuiltSlides, java.io.InputStream templateStream) throws Exception {
        return exportToPptxInternal(session, meta, prebuiltSlides, templateStream);
    }

    /** Render all slide previews as base64 PNGs. */
    public List<String> renderAllSlidePreviews(WorkshopSessionDto session, WorkshopInputDto meta,
                                               List<Map<String, Object>> prebuiltSlides, java.io.InputStream templateStream) throws Exception {
        byte[] pptxBytes = exportToPptxInternal(session, meta, prebuiltSlides, templateStream);
        org.apache.poi.xslf.usermodel.XMLSlideShow ppt = new org.apache.poi.xslf.usermodel.XMLSlideShow(new java.io.ByteArrayInputStream(pptxBytes));
        java.awt.Dimension pgsize = ppt.getPageSize();

        List<String> base64Images = new ArrayList<>();
        for (org.apache.poi.xslf.usermodel.XSLFSlide slide : ppt.getSlides()) {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(pgsize.width, pgsize.height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D graphics = img.createGraphics();
            graphics.setPaint(java.awt.Color.white);
            graphics.fill(new java.awt.geom.Rectangle2D.Float(0, 0, pgsize.width, pgsize.height));
            slide.draw(graphics);

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            byte[] imageBytes = out.toByteArray();
            base64Images.add("data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(imageBytes));
        }
        return base64Images;
    }

    // =========================================================================
    // Per-group slide generation (public — called by WorkshopController)
    // =========================================================================

    /**
     * Primary entry point for per-block slide generation.
     *
     * <p>Dispatches to the appropriate group method based on {@code block.phase()}.
     * Uses exact phase-enum string matching — no substring fuzzy matching.
     *
     * <p>Phase values (from timetable hydration):
     * <ul>
     *   <li>ARRIVE → Welcome group</li>
     *   <li>ACTIVATE → Activate Prior Knowledge group</li>
     *   <li>LEARNING_CYCLE → Main Lecture group (3 slides)</li>
     *   <li>EVALUATE → Check Understanding group (one slide per LG)</li>
     *   <li>SUMMARY → Summary group</li>
     *   <li>BREAK / BUFFER → no slides</li>
     * </ul>
     */
    public List<Map<String, Object>> generateBlockSlides(ActivityBlockDto block,
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

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Block label: ").append(block.phaseLabel() != null ? block.phaseLabel() : "Welcome").append("\n");
        if (block.objective() != null && !block.objective().isBlank())
            userPrompt.append("Block objective: ").append(block.objective()).append("\n");
        appendSectionSteps(userPrompt, block);
        appendMaterials(userPrompt, meta, 6000);
        userPrompt.append("\nSession learning goals (for framing, do NOT list them here — they go on the Agenda slide):\n");
        appendGoals(userPrompt, goals);
        userPrompt.append("\nTask: Generate the Welcome slide JSON object as specified.");

        log.info("LLM call: Welcome slide for '{}'", block.phaseLabel());
        Map<String, Object> welcomeSlide;
        try {
            String raw = llm.callSecondary(sysPrompt, userPrompt.toString());
            String json = llm.extractJsonObject(raw);
            welcomeSlide = normalizeSlideMap(mapper.readValue(json, new TypeReference<>() {}));

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
                      "notes": "a PLAIN STRING — expected answers/misconceptions"
                    }
                    """.formatted(actName);
            } else {
                String layout = switch (method) {
                    case "thinkpairshare", "brainstorming", "designsprint", "prototypechallenge", "workedproblem" -> "activity_grid3";
                    case "groupdiscussion", "debate", "peerreview", "roleplay", "conceptmapping", "casestudy" -> "activity_tiled";
                    case "qasession" -> "activity_q&a";
                    case "handsonpractice" -> "activity_sidebar";
                    case "quizpolls", "quiz", "poll" -> "live_poll";
                    default -> "activity_tiled";
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
                      "notes": "a PLAIN STRING — expected answers/misconceptions"
                    }
                    """.formatted(layout, actName, instJson);
            }
            sysPrompt = sysPrompt.formatted(schemaSnippet);


            StringBuilder userPrompt = new StringBuilder();
            userPrompt.append("Block label: ").append(label).append("\n");
            userPrompt.append("Duration: ").append(block.duration()).append(" minutes\n");
            if (block.objective() != null) userPrompt.append("Objective: ").append(block.objective()).append("\n");
            userPrompt.append("Teaching methods: ").append(String.join(", ", allMethods)).append("\n");
            appendSectionSteps(userPrompt, block);
            appendGoalsList(userPrompt, goals, meta);
            appendMaterials(userPrompt, meta, 6000);
            userPrompt.append("\nTask: Generate ONE activation activity slide JSON object as specified.");

            log.info("LLM call: Activate slide for '{}'", label);
            Map<String, Object> actSlide;
            try {
                String raw = llm.callSecondary(sysPrompt, userPrompt.toString());
                String json = llm.extractJsonObject(raw);
                actSlide = normalizeSlideMap(mapper.readValue(json, new TypeReference<>() {}));

            } catch (Exception e) {
                log.warn("LLM failed for Activate slide, using fallback: {}", e.getMessage());
                actSlide = new LinkedHashMap<>();
                actSlide.put("layout", "activity_tiled");
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
            if (!actSlide.containsKey("layout")) actSlide.put("layout", "activity_tiled");
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
                  "notes": a PLAIN STRING — suggested debrief facilitation technique.
                    CRITICAL: "notes" MUST be a flat string, NOT a JSON object or nested structure.
                
                Return ONLY a valid JSON array of 2 objects. No prose.
                """.formatted(buildActivitySlidePrompt(block));

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Block label: ").append(label).append("\n");
        userPrompt.append("Phase: LEARNING_CYCLE\n");
        userPrompt.append("Duration: ").append(block.duration()).append(" minutes\n");
        if (lgIndex > 0) userPrompt.append("Learning Goal Index: LG").append(lgIndex).append("\n");
        if (!lgText.isBlank()) userPrompt.append("Learning Goal: ").append(lgText).append("\n");
        if (block.objective() != null) userPrompt.append("Block objective: ").append(block.objective()).append("\n");
        Set<String> allMethods = collectMethods(block);
        if (!allMethods.isEmpty())
            userPrompt.append("Teaching methods: ").append(String.join(", ", allMethods)).append("\n");
        appendSectionSteps(userPrompt, block);
        appendGoalsList(userPrompt, goals, meta);
        appendMaterials(userPrompt, meta, 7000);
        userPrompt.append("\nTask: Return ONLY a JSON array of exactly 2 slide objects (Activity then Debrief) as specified above.");

        log.info("LLM call: Learning cycle slides for '{}' (lgIndex={})", label, lgIndex);
        List<Map<String, Object>> llmSlides;
        try {
            String raw = llm.callSecondary(sysPrompt, userPrompt.toString());
            String json = llm.extractJsonArray(raw);
            llmSlides = normalizeSlides(mapper.readValue(json, new TypeReference<>() {}));

        } catch (Exception e) {
            log.warn("LLM failed for learning cycle slides, using fallback: {}", e.getMessage());
            llmSlides = buildLearningCycleFallback(label);
        }

        // Tag and sanitize LLM output
        String[] expectedLayouts = {"activity_tiled", "debrief"};
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
        act.put("layout", "activity_tiled");
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
                          "notes": a PLAIN STRING — suggested debrief facilitation technique.
                        
                        Return ONLY a valid JSON array of 2 objects. No prose.
                        """.formatted(buildActivitySlidePrompt(cleanMethod));

                StringBuilder userPrompt = new StringBuilder();
                userPrompt.append("Block label: ").append(label).append("\n");
                userPrompt.append("Duration: ").append(block.duration()).append(" minutes\n");
                if (lgIndex > 0) userPrompt.append("Learning Goal Index: LG").append(lgIndex).append("\n");
                if (!lgText.isBlank()) userPrompt.append("Learning Goal: ").append(lgText).append("\n");
                if (block.objective() != null) userPrompt.append("Objective: ").append(block.objective()).append("\n");
                userPrompt.append("Teaching method: ").append(method).append("\n");
                appendSectionSteps(userPrompt, block);
                appendGoalsList(userPrompt, goals, meta);
                appendMaterials(userPrompt, meta, 6000);
                userPrompt.append("\nTask: Return ONLY a JSON array of exactly 2 slide objects (Activity then Debrief) as specified above.");

                log.info("LLM call: Check Understanding slides for '{}' method='{}' (lgIndex={})", label, method, lgIndex);
                List<Map<String, Object>> llmSlides;
                try {
                    String raw = llm.callSecondary(sysPrompt, userPrompt.toString());
                    String json = llm.extractJsonArray(raw);
                    llmSlides = normalizeSlides(mapper.readValue(json, new TypeReference<>() {}));
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
                  "notes": "Facilitation steps + time allocation"
                }
                
                Schema for the One-Minute Paper slide:
                {
                  "title": "One Minute Paper",
                  "layout": "activity_q&a",
                  "activityPrompt": "What is the most important concept you learned today?\\n2. What is your biggest remaining question?",
                  "notes": "Facilitation steps + time allocation"
                }
                
                Return ONLY a valid JSON array. No prose.
                """;

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Block label: ").append(label).append("\n");
        userPrompt.append("Duration: ").append(block.duration()).append(" minutes\n");
        if (block.objective() != null) userPrompt.append("Objective: ").append(block.objective()).append("\n");
        appendSectionSteps(userPrompt, block);
        appendGoalsList(userPrompt, goals, meta);
        appendMaterials(userPrompt, meta, 6000);
        userPrompt.append("\nTask: Return a JSON array of 1–2 summary slides (NOT the Thank-You slide) as specified.");

        log.info("LLM call: Summary slides for '{}'", label);
        List<Map<String, Object>> llmSlides;
        try {
            String raw = llm.callSecondary(sysPrompt, userPrompt.toString());
            String json = llm.extractJsonArray(raw);
            llmSlides = normalizeSlides(mapper.readValue(json, new TypeReference<>() {}));

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
                  "notes": a PLAIN STRING — answer/reasoning, common wrong answers.
                    CRITICAL: "notes" MUST be a flat string, NOT a JSON object or nested structure.
                """.formatted(actName);
        }

        String layout = switch (method) {
            case "thinkpairshare", "brainstorming", "designsprint", "prototypechallenge", "workedproblem" -> "activity_grid3";
            case "groupdiscussion", "debate", "peerreview", "roleplay", "conceptmapping", "casestudy" -> "activity_tiled";
            case "qasession" -> "activity_q&a";
            case "handsonpractice" -> "activity_sidebar";
            case "quizpolls", "quiz", "poll" -> "live_poll";
            default -> "activity_tiled";
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
                  "notes": a PLAIN STRING — answer/reasoning, debrief technique, common wrong answers, AND full detailed step-by-step instructions for the instructor.
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
                "activityOutputExpectation", "debriefQuestion", "pollQuestion", "layout", "group"}) {
            Object v = slide.get(field);
            if (v != null && !(v instanceof String)) {
                slide.put(field, coerceToString(v));
            }
        }
        // activityInstructions and pollOptions must be List<String>
        for (String field : new String[]{"activityInstructions", "pollOptions", "bullets"}) {
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

    // =========================================================================
    // PPTX assembly — exportToPptxInternal + buildPptx
    // =========================================================================

    private byte[] exportToPptxInternal(WorkshopSessionDto session, WorkshopInputDto meta,
                                         List<Map<String, Object>> prebuiltSlides, java.io.InputStream templateStream) throws Exception {
        List<Map<String, Object>> slidesData;

        if (prebuiltSlides != null && !prebuiltSlides.isEmpty()) {
            log.info("Assembling PPTX from {} pre-built slides (no LLM call)", prebuiltSlides.size());
            slidesData = prebuiltSlides;
        } else {
            // No prebuilt slides and no whole-session LLM path (timed out in practice).
            // Return an empty deck with only the title slide — the frontend should always
            // pre-generate slides via the block-slides endpoint before calling export.
            log.warn("exportToPptxInternal called without pre-built slides and without a viable whole-session path — returning title-only deck");
            slidesData = List.of();
        }

        return buildPptx(session, meta, slidesData, templateStream);
    }

    // =========================================================================
    // Layout helpers
    // =========================================================================

    private org.apache.poi.xslf.usermodel.XSLFSlideLayout getTitleLayout(XMLSlideShow ppt) {
        if (ppt.getSlideMasters().isEmpty()) return null;

        for (org.apache.poi.xslf.usermodel.XSLFSlideMaster master : ppt.getSlideMasters()) {
            org.apache.poi.xslf.usermodel.XSLFSlideLayout layout = master.getLayout(org.apache.poi.xslf.usermodel.SlideLayout.TITLE);
            if (layout != null) return layout;
        }
        for (org.apache.poi.xslf.usermodel.XSLFSlideMaster master : ppt.getSlideMasters()) {
            for (org.apache.poi.xslf.usermodel.XSLFSlideLayout layout : master.getSlideLayouts()) {
                String name = layout.getName().toLowerCase();
                if (name.equals("1_start") || name.equals("1_title") || name.equals("1_titel")) return layout;
            }
        }
        for (org.apache.poi.xslf.usermodel.XSLFSlideMaster master : ppt.getSlideMasters()) {
            for (org.apache.poi.xslf.usermodel.XSLFSlideLayout layout : master.getSlideLayouts()) {
                String name = layout.getName().toLowerCase();
                if (name.contains("title") || name.contains("start") || name.contains("titel")) return layout;
            }
        }
        return ppt.getSlideMasters().get(0).getSlideLayouts()[0];
    }

    private boolean hasBodyPlaceholder(org.apache.poi.xslf.usermodel.XSLFSlideLayout layout) {
        for (XSLFTextShape shape : layout.getPlaceholders()) {
            if (shape.getTextType() != null) {
                String name = shape.getTextType().name();
                if (name.equals("BODY") || name.equals("CONTENT") || name.equals("OBJECT")) return true;
            }
        }
        return false;
    }

    private org.apache.poi.xslf.usermodel.XSLFSlideLayout getContentLayout(XMLSlideShow ppt) {
        if (ppt.getSlideMasters().isEmpty()) return null;

        for (org.apache.poi.xslf.usermodel.XSLFSlideMaster master : ppt.getSlideMasters()) {
            org.apache.poi.xslf.usermodel.XSLFSlideLayout layout = master.getLayout(org.apache.poi.xslf.usermodel.SlideLayout.TITLE_AND_CONTENT);
            if (layout != null && hasBodyPlaceholder(layout)) return layout;
        }
        for (org.apache.poi.xslf.usermodel.XSLFSlideMaster master : ppt.getSlideMasters()) {
            for (org.apache.poi.xslf.usermodel.XSLFSlideLayout layout : master.getSlideLayouts()) {
                if (hasBodyPlaceholder(layout)) {
                    String name = layout.getName().toLowerCase();
                    if (name.contains("content") || name.contains("inhalt") || name.contains("text")) return layout;
                }
            }
        }
        org.apache.poi.xslf.usermodel.XSLFSlideMaster master = ppt.getSlideMasters().get(0);
        if (master.getSlideLayouts().length > 1) return master.getSlideLayouts()[1];
        return master.getSlideLayouts()[0];
    }

    private void safeSetText(XSLFTextShape shape, String text) {
        try {
            shape.setText(text);
        } catch (IndexOutOfBoundsException e) {
            shape.clearText();
            org.apache.poi.xslf.usermodel.XSLFTextParagraph p = shape.addNewTextParagraph();
            org.apache.poi.xslf.usermodel.XSLFTextRun r = p.addNewTextRun();
            r.setText(text);
        }
    }

    private java.awt.Color getTemplateAccentColor(XMLSlideShow ppt) {
        try {
            if (!ppt.getSlideMasters().isEmpty()) {
                org.apache.poi.xslf.usermodel.XSLFTheme theme = ppt.getSlideMasters().get(0).getTheme();
                if (theme != null) {
                    org.openxmlformats.schemas.drawingml.x2006.main.CTColorScheme colorScheme = theme.getXmlObject().getThemeElements().getClrScheme();
                    if (colorScheme != null && colorScheme.getAccent1() != null) {
                        org.openxmlformats.schemas.drawingml.x2006.main.CTColor accent = colorScheme.getAccent1();
                        if (accent.getSrgbClr() != null) {
                            byte[] val = accent.getSrgbClr().getVal();
                            if (val != null && val.length == 3) {
                                return new java.awt.Color(val[0] & 0xFF, val[1] & 0xFF, val[2] & 0xFF);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract theme color from template", e);
        }
        return HESTIA_PRIMARY;
    }

    private void safeSetTitleAndSubtitle(XSLFTextShape shape, String title, String subtitle, java.awt.Color subtitleColor) {
        if (shape == null) return;
        
        java.util.List<org.apache.poi.xslf.usermodel.XSLFTextParagraph> paragraphs = shape.getTextParagraphs();
        
        // If the template has at least 2 paragraphs, it likely follows the [PHASE] 
 // [TPS: TOPIC] format
        if (paragraphs.size() >= 2) {
            // Paragraph 0 is the subtitle (e.g. [PHASE])
            org.apache.poi.xslf.usermodel.XSLFTextParagraph p0 = paragraphs.get(0);
            if (!p0.getTextRuns().isEmpty()) {
                org.apache.poi.xslf.usermodel.XSLFTextRun r0 = p0.getTextRuns().get(0);
                r0.setText(subtitle != null ? subtitle : "");
                // Clear any other runs in this paragraph
                for (int i = p0.getTextRuns().size() - 1; i > 0; i--) p0.getTextRuns().get(i).setText("");
            } else {
                org.apache.poi.xslf.usermodel.XSLFTextRun r = p0.addNewTextRun();
                r.setText(subtitle != null ? subtitle : "");
            }
            
            // Paragraph 1 is the main title (e.g. [TPS: TOPIC])
            org.apache.poi.xslf.usermodel.XSLFTextParagraph p1 = paragraphs.get(1);
            if (!p1.getTextRuns().isEmpty()) {
                org.apache.poi.xslf.usermodel.XSLFTextRun r1 = p1.getTextRuns().get(0);
                r1.setText(title != null ? title : "Slide");
                // Clear any other runs
                for (int i = p1.getTextRuns().size() - 1; i > 0; i--) p1.getTextRuns().get(i).setText("");
            } else {
                org.apache.poi.xslf.usermodel.XSLFTextRun r = p1.addNewTextRun();
                r.setText(title != null ? title : "Slide");
            }
            
            // Remove any extra dummy paragraphs from XML
            for (int i = paragraphs.size() - 1; i > 1; i--) {
                shape.getTextBody().getXmlObject().removeP(i);
                // paragraphs.remove(i); // Throws UnsupportedOperationException in POI 5.2.5+
            }
        } else {
            // Fallback if template doesn't match expected structure: grab the first run's formatting if any
            Double defaultFontSize = 28d;
            String defaultFontFamily = null;
            boolean defaultBold = true;
            java.awt.Color defaultColor = java.awt.Color.BLACK;
            
            if (!paragraphs.isEmpty() && !paragraphs.get(0).getTextRuns().isEmpty()) {
                org.apache.poi.xslf.usermodel.XSLFTextRun templateRun = paragraphs.get(0).getTextRuns().get(0);
                if (templateRun.getFontSize() != null) defaultFontSize = templateRun.getFontSize();
                if (templateRun.getFontFamily() != null) defaultFontFamily = templateRun.getFontFamily();
                defaultBold = templateRun.isBold();
                // Try to get color, fallback to param if not possible
                // Removed font color reading to avoid PaintStyle cast issues
            }
            
            shape.clearText();
            
            // Paragraph 1: Subtitle
            if (subtitle != null && !subtitle.isBlank()) {
                org.apache.poi.xslf.usermodel.XSLFTextParagraph sp = shape.addNewTextParagraph();
                org.apache.poi.xslf.usermodel.XSLFTextRun sr = sp.addNewTextRun();
                sr.setText(subtitle);
                if (defaultFontFamily != null) sr.setFontFamily(defaultFontFamily);
                sr.setFontSize(defaultFontSize != null ? Math.max(10d, defaultFontSize - 12d) : 16d);
                sr.setFontColor(subtitleColor != null ? subtitleColor : defaultColor);
                sr.setBold(true);
            }
            
            // Paragraph 2: Title
            org.apache.poi.xslf.usermodel.XSLFTextParagraph tp = shape.addNewTextParagraph();
            org.apache.poi.xslf.usermodel.XSLFTextRun tr = tp.addNewTextRun();
            tr.setText(title != null ? title : "Slide");
            if (defaultFontFamily != null) tr.setFontFamily(defaultFontFamily);
            tr.setFontSize(defaultFontSize);
            tr.setBold(defaultBold);
            tr.setFontColor(defaultColor);
        }
    }

    private XSLFTextShape getShapeByType(XSLFSlide slide, String... types) {
        for (String type : types) {
            for (XSLFTextShape shape : slide.getPlaceholders()) {
                if (shape.getTextType() != null && shape.getTextType().name().equals(type)) return shape;
            }
        }
        if (slide.getPlaceholders().length > 0 && types.length > 0
                && (types[0].equals("TITLE") || types[0].equals("CENTER_TITLE") || types[0].equals("CENTERED_TITLE"))) {
            return slide.getPlaceholders()[0];
        }
        if (slide.getPlaceholders().length > 1 && types.length > 0
                && (types[0].equals("BODY") || types[0].equals("CONTENT") || types[0].equals("SUBTITLE"))) {
            return slide.getPlaceholders()[1];
        }
        return null;
    }

    /**
     * Map a slide's {@code group} field to its phase-accent stripe color.
     *
     * <p>From the reference HTML visual language:
     * <ul>
     *   <li>setup (blue) — Title, Welcome, Agenda, Check Understanding</li>
     *   <li>lecture (purple) — Lecture placeholders, Summary / Thank-You</li>
     *   <li>practice (green) — Activity slides, Debrief slides</li>
     * </ul>
     */
    private java.awt.Color resolvePhaseAccentColor(Map<String, Object> slideData) {
        String group = (String) slideData.getOrDefault("group", "");
        String layout = (String) slideData.getOrDefault("layout", "default");

        // Layout overrides for specific non-group-typed slides
        if ("lecture_placeholder".equals(layout)) return PHASE_LECTURE;
        if ("activity_tiled".equals(layout))      return PHASE_PRACTICE;
        if ("debrief".equals(layout))             return PHASE_PRACTICE;

        return switch (group) {
            case "welcome", "agenda", "check_understanding" -> PHASE_SETUP;
            case "summary"                                  -> PHASE_LECTURE;
            case "activate_prior_knowledge"                 -> PHASE_PRACTICE;
            case "main_lecture"                             -> PHASE_LECTURE; // lecture placeholder; activity/debrief caught above
            default                                         -> HESTIA_PRIMARY;
        };
    }

    // =========================================================================
    // PPTX rendering
    // =========================================================================

    
    private int getTemplateSlideIndex(String layout) {
        if (layout == null) return 10; // default fallback (generic content slide)
        return switch (layout) {
            case "title" -> 0;
            case "agenda" -> 1;
            case "welcome" -> 2;
            case "activity_grid3" -> 3;
            case "activity_tiled" -> 4;
            case "activity_sidebar" -> 5;
            case "activity_q&a", "activity_qanda" -> 6;
            case "live_poll" -> 7;
            case "debrief" -> 8;
            case "lecture_placeholder" -> 9;
            case "summary", "concept_map" -> 10;
            case "break" -> 11;
            default -> 10; // default fallback (generic content slide)
        };
    }

    private void replaceTextInSlide(org.apache.poi.xslf.usermodel.XSLFSlide slide, String search, String replacement) {
        if (search == null) return;
        if (replacement == null) replacement = "";
        for (org.apache.poi.xslf.usermodel.XSLFShape shape : slide.getShapes()) {
            if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape ts) {
                if (ts.getText().contains(search)) {
                    for (org.apache.poi.xslf.usermodel.XSLFTextParagraph p : ts.getTextParagraphs()) {
                        if (p.getText().contains(search)) {
                            String fullText = p.getText();
                            String regex = search.endsWith("]") 
                                ? java.util.regex.Pattern.quote(search) 
                                : java.util.regex.Pattern.quote(search) + "[^\\]]*\\]";
                            String newText = fullText.replaceAll(regex, java.util.regex.Matcher.quoteReplacement(replacement));
                            if (newText.equals(fullText)) {
                                // Fallback: no closing bracket found – do a simple substring replace
                                newText = fullText.replace(search, replacement);
                            }
                            if (!p.getTextRuns().isEmpty()) {
                                org.apache.poi.sl.usermodel.PaintStyle targetColor = null;
                                String targetFontFamily = null;
                                Double targetFontSize = null;
                                boolean isBold = false;

                                for (org.apache.poi.xslf.usermodel.XSLFTextRun r : p.getTextRuns()) {
                                    if (r.getRawText().contains(search)) {
                                        targetColor = r.getFontColor();
                                        targetFontFamily = r.getFontFamily();
                                        targetFontSize = r.getFontSize();
                                        isBold = r.isBold();
                                        break;
                                    }
                                }
                                if (targetColor == null && p.getTextRuns().size() > 1) {
                                    org.apache.poi.xslf.usermodel.XSLFTextRun lastRun = p.getTextRuns().get(p.getTextRuns().size() - 1);
                                    targetColor = lastRun.getFontColor();
                                    targetFontFamily = lastRun.getFontFamily();
                                    targetFontSize = lastRun.getFontSize();
                                    isBold = lastRun.isBold();
                                }

                                org.apache.poi.xslf.usermodel.XSLFTextRun firstRun = p.getTextRuns().get(0);
                                firstRun.setText(newText);
                                if (targetColor != null) firstRun.setFontColor(targetColor);
                                if (targetFontFamily != null) firstRun.setFontFamily(targetFontFamily);
                                if (targetFontSize != null) firstRun.setFontSize(targetFontSize);
                                firstRun.setBold(isBold);

                                for (int i = p.getTextRuns().size() - 1; i > 0; i--) {
                                    p.getTextRuns().get(i).setText("");
                                }
                            }
                        }
                    }
                    // Enable normAutoFit so replaced text shrinks to fit the shape
                    enableAutoFit(ts);
                }
            }
        }
    }

    /**
     * Enable "shrink text on overflow" (normAutoFit) on a text shape so that
     * long replacement strings never overflow the slide boundary.
     */
    private void enableAutoFit(org.apache.poi.xslf.usermodel.XSLFTextShape shape) {
        try {
            shape.setTextAutofit(org.apache.poi.sl.usermodel.TextShape.TextAutofit.NORMAL);
        } catch (Exception e) {
            log.debug("Could not enable auto-fit on shape: {}", e.getMessage());
        }
    }

    private void removeShapeContainingText(org.apache.poi.xslf.usermodel.XSLFSlide slide, String searchString) {
        if (searchString == null) return;
        List<org.apache.poi.xslf.usermodel.XSLFShape> toRemove = new ArrayList<>();
        for (org.apache.poi.xslf.usermodel.XSLFShape shape : slide.getShapes()) {
            if (shapeContainsText(shape, searchString, false)) {
                toRemove.add(shape);
            }
        }
        for (org.apache.poi.xslf.usermodel.XSLFShape s : toRemove) {
            slide.removeShape(s);
        }
    }

    private void removeExactShapeText(org.apache.poi.xslf.usermodel.XSLFSlide slide, String exactMatch) {
        if (exactMatch == null) return;
        List<org.apache.poi.xslf.usermodel.XSLFShape> toRemove = new ArrayList<>();
        for (org.apache.poi.xslf.usermodel.XSLFShape shape : slide.getShapes()) {
            if (shapeContainsText(shape, exactMatch, true)) {
                toRemove.add(shape);
            }
        }
        for (org.apache.poi.xslf.usermodel.XSLFShape s : toRemove) {
            slide.removeShape(s);
        }
    }

    private boolean shapeContainsText(org.apache.poi.xslf.usermodel.XSLFShape shape, String searchString, boolean exact) {
        if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape ts) {
            String text = ts.getText();
            if (exact) {
                return text.trim().equals(searchString);
            } else {
                return text.contains(searchString);
            }
        } else if (shape instanceof org.apache.poi.xslf.usermodel.XSLFGroupShape group) {
            for (org.apache.poi.xslf.usermodel.XSLFShape child : group.getShapes()) {
                if (shapeContainsText(child, searchString, exact)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void replaceExactShapeText(org.apache.poi.xslf.usermodel.XSLFSlide slide, String exactMatch, String replacement) {
        if (exactMatch == null) return;
        for (org.apache.poi.xslf.usermodel.XSLFShape shape : slide.getShapes()) {
            if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape ts) {
                if (ts.getText().trim().equals(exactMatch)) {
                    ts.clearText();
                    if (replacement != null && !replacement.isEmpty()) {
                        ts.setText(replacement);
                    }
                }
            }
        }
    }

    private void removeRowShapes(org.apache.poi.xslf.usermodel.XSLFSlide slide, String searchString) {
        if (searchString == null) return;
        org.apache.poi.xslf.usermodel.XSLFShape targetShape = null;
        for (org.apache.poi.xslf.usermodel.XSLFShape shape : slide.getShapes()) {
            if (shapeContainsText(shape, searchString, false)) {
                targetShape = shape;
                break;
            }
        }
        if (targetShape == null) return;

        java.awt.geom.Rectangle2D targetAnchor = targetShape.getAnchor();
        double targetMinY = targetAnchor.getY();
        double targetMaxY = targetMinY + targetAnchor.getHeight();

        List<org.apache.poi.xslf.usermodel.XSLFShape> toRemove = new ArrayList<>();
        for (org.apache.poi.xslf.usermodel.XSLFShape shape : slide.getShapes()) {
            java.awt.geom.Rectangle2D anchor = shape.getAnchor();
            if (anchor == null) continue;
            double minY = anchor.getY();
            double maxY = minY + anchor.getHeight();

            // Check for vertical overlap. We assume slide height is ~540pt.
            // A background shape covering the whole slide will have height > 500pt.
            // The row shapes have height around 60-80pt.
            if (minY <= targetMaxY && maxY >= targetMinY) {
                if (anchor.getHeight() < 400) {
                    toRemove.add(shape);
                }
            }
        }
        for (org.apache.poi.xslf.usermodel.XSLFShape s : toRemove) {
            slide.removeShape(s);
        }
    }

    private void replaceBodyText(org.apache.poi.xslf.usermodel.XSLFSlide slide, List<String> bullets, String... searchStrings) {
        if (bullets == null || bullets.isEmpty()) return;
        for (org.apache.poi.xslf.usermodel.XSLFShape shape : slide.getShapes()) {
            if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape) {
                org.apache.poi.xslf.usermodel.XSLFTextShape ts = (org.apache.poi.xslf.usermodel.XSLFTextShape) shape;
                boolean match = false;
                for (String search : searchStrings) {
                    if (ts.getText().contains(search)) {
                        match = true;
                        break;
                    }
                }
                if (match) {
                    // Capture style
                    String defaultBulletChar = "•";
                    String defaultBulletFont = null;
                    Double defaultFontSize = 20d;
                    String defaultFontFamily = null;
                    java.awt.Color defaultFontColor = null;
                    boolean defaultBold = false;
                    boolean defaultItalic = false;
                    org.apache.poi.sl.usermodel.TextParagraph.TextAlign defaultAlign = null;
                    Double defaultLineSpacing = null;
                    Double defaultSpaceBefore = null;
                    Double defaultSpaceAfter = null;
                    Double defaultLeftMargin = null;
                    Double defaultIndent = null;
                    
                    if (!ts.getTextParagraphs().isEmpty()) {
                        org.apache.poi.xslf.usermodel.XSLFTextParagraph firstPara = ts.getTextParagraphs().get(0);
                        if (firstPara.isBullet() && firstPara.getBulletCharacter() != null) {
                            defaultBulletChar = firstPara.getBulletCharacter();
                        }
                        if (firstPara.getBulletFont() != null) defaultBulletFont = firstPara.getBulletFont();
                        
                        defaultAlign = firstPara.getTextAlign();
                        defaultLineSpacing = firstPara.getLineSpacing();
                        defaultSpaceBefore = firstPara.getSpaceBefore();
                        defaultSpaceAfter = firstPara.getSpaceAfter();
                        defaultLeftMargin = firstPara.getLeftMargin();
                        defaultIndent = firstPara.getIndent();
                        
                        if (!firstPara.getTextRuns().isEmpty()) {
                            org.apache.poi.xslf.usermodel.XSLFTextRun firstRun = firstPara.getTextRuns().get(0);
                            if (firstRun.getFontSize() != null) defaultFontSize = firstRun.getFontSize();
                            if (firstRun.getFontFamily() != null) defaultFontFamily = firstRun.getFontFamily();
                            // Eagerly resolve PaintStyle → java.awt.Color BEFORE clearText() disconnects the XML nodes.
                            // Holding a live PaintStyle/XSLFColor reference after clearText() causes XmlValueDisconnectedException.
                            org.apache.poi.sl.usermodel.PaintStyle rawColor = firstRun.getFontColor();
                            if (rawColor instanceof org.apache.poi.sl.usermodel.PaintStyle.SolidPaint) {
                                java.awt.Color c = ((org.apache.poi.sl.usermodel.PaintStyle.SolidPaint) rawColor)
                                        .getSolidColor().getColor();
                                if (c != null) defaultFontColor = c;
                            }
                            defaultBold = firstRun.isBold();
                            defaultItalic = firstRun.isItalic();
                        }
                    }

                    ts.clearText();
                    
                    for (String bullet : bullets) {
                        org.apache.poi.xslf.usermodel.XSLFTextParagraph bp = ts.addNewTextParagraph();
                        bp.setBullet(true);
                        bp.setBulletCharacter(defaultBulletChar);
                        if (defaultBulletFont != null) bp.setBulletFont(defaultBulletFont);
                        if (defaultAlign != null) bp.setTextAlign(defaultAlign);
                        if (defaultLineSpacing != null) bp.setLineSpacing(defaultLineSpacing);
                        if (defaultSpaceBefore != null) bp.setSpaceBefore(defaultSpaceBefore);
                        if (defaultSpaceAfter != null) bp.setSpaceAfter(defaultSpaceAfter);
                        if (defaultLeftMargin != null) bp.setLeftMargin(defaultLeftMargin);
                        if (defaultIndent != null) bp.setIndent(defaultIndent);
                        
                        org.apache.poi.xslf.usermodel.XSLFTextRun br = bp.addNewTextRun();
                        br.setText(bullet);
                        if (defaultFontFamily != null) br.setFontFamily(defaultFontFamily);
                        br.setFontSize(defaultFontSize);
                        if (defaultFontColor != null) br.setFontColor(defaultFontColor);
                        br.setBold(defaultBold);
                        br.setItalic(defaultItalic);
                    }
                    break;
                }
            }
        }
    }

    private byte[] buildPptx(WorkshopSessionDto session, WorkshopInputDto meta,
                              List<Map<String, Object>> slidesData, java.io.InputStream templateStream) throws Exception {
        try (org.apache.poi.xslf.usermodel.XMLSlideShow ppt = templateStream != null
                ? new org.apache.poi.xslf.usermodel.XMLSlideShow(templateStream)
                : new org.apache.poi.xslf.usermodel.XMLSlideShow()) {

            boolean useTemplate = (templateStream != null);
            int originalSlideCount = ppt.getSlides().size();
            boolean isCloningMode = useTemplate && originalSlideCount >= 11;

            if (!useTemplate) {
                ppt.setPageSize(new java.awt.Dimension(960, 540));
            }

            // ── Title slide ─────────────────────────────────────────────────────
            if (isCloningMode) {
                org.apache.poi.xslf.usermodel.XSLFSlide titleSlide = ppt.createSlide();
                titleSlide.importContent(ppt.getSlides().get(0)); // template slide 0 = title
                replaceTextInSlide(titleSlide, "[Session Title]",
                        session.title() != null ? session.title() : "Workshop Session");
                replaceTextInSlide(titleSlide, "[PHASE]",
                        meta != null && meta.sessionType() != null ? meta.sessionType() : "");
                clearTemplateTag(titleSlide);
            } else {
                ppt.createSlide();
            }

            // ── Content slides ───────────────────────────────────────────────────
            for (Map<String, Object> slideData : slidesData) {
                String slideTitle    = (String) slideData.get("title");
                String slideSubtitle = (String) slideData.get("subtitle");
                String layout        = (String) slideData.getOrDefault("layout", "default");

                if (!isCloningMode) continue; // non-template path not supported

                int tplIdx = getTemplateSlideIndex(layout);
                if (tplIdx >= originalSlideCount) tplIdx = 2; // fallback to generic content slide

                org.apache.poi.xslf.usermodel.XSLFSlide slide = ppt.createSlide();
                slide.importContent(ppt.getSlides().get(tplIdx));

                // ── Remove "TEMPLATE · <layout>" tag labels baked into template ──────
                clearTemplateTag(slide);

                // ── Title / topic ────────────────────────────────────────────────
                // The title from the LLM may look like "Think-Pair-Share: Some Topic".
                // Template placeholders already contain the activity type label, so we
                // extract only the topic part when a colon separator is present.
                String topicOnly = extractTopicOnly(slideTitle, layout);

                // ── Activity Name (from mapped activity method) ──────────────────
                String activityName = (String) slideData.get("activityName");
                if (activityName != null) {
                    replaceTextInSlide(slide, "[ACTIVITY NAME]", activityName.toUpperCase());
                } else {
                    replaceTextInSlide(slide, "[ACTIVITY NAME]", "");
                }

                // ── Phase / Topic (top-left on every slide) ────────────────────────
                String group = (String) slideData.get("group");
                String rawTopic = (String) slideData.get("topic");
                String topicVal = rawTopic != null ? rawTopic.replaceAll("(?i)\\s*-\\s*LG\\s*\\d+", "") : null;
                boolean isActivity = layout != null && (layout.startsWith("activity_") || layout.equals("live_poll"));

                String phaseOrTopic = slideSubtitle; // Default fallback
                if ("welcome".equals(group)) {
                    phaseOrTopic = "WELCOME";
                } else if ("agenda".equals(group)) {
                    phaseOrTopic = "AGENDA";
                } else if ("activate_prior_knowledge".equals(group)) {
                    phaseOrTopic = isActivity ? "ACTIVATE" : "LECTURE";
                } else if ("main_lecture".equals(group)) {
                    if ("debrief".equals(layout)) {
                        phaseOrTopic = topicVal != null ? topicVal : "DEBRIEF";
                    } else if (isActivity) {
                        phaseOrTopic = topicVal != null ? topicVal : "ACTIVITY";
                    } else {
                        phaseOrTopic = "LECTURE";
                    }
                } else if ("check_understanding".equals(group)) {
                    phaseOrTopic = "CHECK UNDERSTANDING";
                } else if ("summary".equals(group)) {
                    phaseOrTopic = "SUMMARY";
                }
                if (phaseOrTopic != null) {
                    replaceTextInSlide(slide, "[PHASE/TOPIC]", phaseOrTopic.toUpperCase());
                    replaceTextInSlide(slide, "[PHASE]", phaseOrTopic.toUpperCase());
                }

                replaceTextInSlide(slide, "[TPS: TOPIC]",   topicOnly);
                replaceTextInSlide(slide, "[TOPIC]",        topicOnly);
                replaceTextInSlide(slide, "[Slide Title]",  slideTitle != null ? slideTitle : "");
                replaceTextInSlide(slide, "[Lecture Topic]", slideTitle != null ? slideTitle : "");
                replaceTextInSlide(slide, "[Session Title]", session.title() != null ? session.title() : "Workshop Session");

                // ── Layout-specific body content ─────────────────────────────────
                populateSlideBody(slide, slideData, layout);

                // ── Speaker notes ─────────────────────────────────────────────────
                String notes = (String) slideData.get("notes");
                if (notes != null && !notes.isBlank()) {
                    setSlideNotes(slide, notes);
                }
            }

            // ── Remove the original template slides ───────────────────────────────
            if (isCloningMode) {
                for (int i = originalSlideCount - 1; i >= 0; i--) {
                    ppt.removeSlide(i);
                }
            }

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            ppt.write(out);
            return out.toByteArray();
        }
    }

    /**
     * For activity slides whose LLM-generated title contains "ActivityType: Topic",
     * extract only the topic portion so it can replace "[TOPIC]" in the template
     * (which already renders the activity type label visually).
     * For non-activity slides the full title is returned unchanged.
     */
    private String extractTopicOnly(String title, String layout) {
        if (title == null) return "";
        if (layout != null && (layout.startsWith("activity_") || "live_poll".equals(layout))) {
            int colonIdx = title.indexOf(':');
            if (colonIdx > 0 && colonIdx < title.length() - 1) {
                return title.substring(colonIdx + 1).trim();
            }
        }
        return title;
    }

    /**
     * Populate the body shapes of a cloned template slide with the correct data fields
     * for each layout type. Each layout in the template has distinct placeholder text that
     * maps to specific JSON fields — this method handles them separately instead of
     * merging everything into a single bullet list.
     */
    @SuppressWarnings("unchecked")
    private void populateSlideBody(org.apache.poi.xslf.usermodel.XSLFSlide slide,
                                   Map<String, Object> slideData, String layout) {
        if (layout == null) layout = "default";

        switch (layout) {

            // ── Think-Pair-Share (activity_grid3) ────────────────────────────────
            // Template has: one prompt box + 3 phase tiles (THINK/PAIR/SHARE)
            // Data fields:  activityPrompt only (instructions are fixed in template tiles)
            case "activity_grid3" -> {
                String prompt = (String) slideData.get("activityPrompt");
                if (prompt != null) {
                    replaceTextInSlide(slide, "[Write the question or task", prompt);
                    replaceTextInSlide(slide, "[Write the question", prompt);
                }
                List<String> instructions = (List<String>) slideData.get("activityInstructions");
                if (instructions != null) {
                    if (instructions.size() > 0) replaceTextInSlide(slide, "[THINK (2 min)]", instructions.get(0));
                    if (instructions.size() > 1) replaceTextInSlide(slide, "[PAIR (2 min)]", instructions.get(1));
                    if (instructions.size() > 2) replaceTextInSlide(slide, "[SHARE (1 min)]", instructions.get(2));
                }
            }

            // ── Group Discussion / Brainstorming (activity_tiled) ────────────────
            // Template has: one prompt box + two side tiles (LOGISTICS + DELIVERABLE)
            // Data fields:  activityPrompt, activityInstructions, activityOutputExpectation
            case "activity_tiled" -> {
                String prompt = (String) slideData.get("activityPrompt");
                if (prompt != null) {
                    replaceTextInSlide(slide, "[Write the discussion prompt", prompt);
                    replaceTextInSlide(slide, "[Write the discussion", prompt);
                }
                List<String> instructions = (List<String>) slideData.get("activityInstructions");
                if (instructions != null && !instructions.isEmpty()) {
                    replaceBodyText(slide, instructions, "[Form groups of 4]", "[Assign a note");
                }
                String expectation = (String) slideData.get("activityOutputExpectation");
                if (expectation != null) {
                    replaceTextInSlide(slide, "[Describe what the group should be ready to share", expectation);
                    replaceTextInSlide(slide, "[Describe what the group", expectation);
                }
            }

            // ── Case Study / Role Play / Hands-on (activity_sidebar) ─────────────
            // Template has: a numbered instructions column + a time/materials sidebar
            // Data fields:  activityInstructions, activityPrompt (used as context)
            case "activity_sidebar" -> {
                List<String> instructions = (List<String>) slideData.get("activityInstructions");
                if (instructions != null && !instructions.isEmpty()) {
                    replaceBodyText(slide, instructions, "[Step 1 — set up your environment", "[Step 1");
                }
                String prompt = (String) slideData.get("activityPrompt");
                if (prompt != null) {
                    replaceTextInSlide(slide, "[Write the question", prompt);
                }
            }

            // ── Q&A / One-Minute Paper (activity_q&a) ────────────────────────────
            case "activity_q&a", "activity_qanda" -> {
                String title = (String) slideData.get("title");
                if (title != null) {
                    replaceTextInSlide(slide, "[Floor is Open]", title);
                }
                String prompt = (String) slideData.get("activityPrompt");
                if (prompt != null) {
                    replaceTextInSlide(slide, "[Short supporting note", prompt);
                }
            }

            // ── Quiz / Poll (live_poll) ───────────────────────────────────────────
            // Template has: one question text shape + 4 individual option tile shapes
            // each labelled [Option A], [Option B], [Option C], [Option D].
            // We replace them one-by-one so each tile gets exactly one option string.
            case "live_poll" -> {
                String question = (String) slideData.get("pollQuestion");
                if (question != null) {
                    replaceTextInSlide(slide, "[Poll Question Goes Here?]", question);
                    replaceTextInSlide(slide, "[Write the question", question);
                }
                List<String> options = (List<String>) slideData.get("pollOptions");
                if (options != null) {
                    String[] tiles = {"[Option A]", "[Option B]", "[Option C]", "[Option D]"};
                    for (int i = 0; i < tiles.length; i++) {
                        if (i < options.size()) {
                            replaceTextInSlide(slide, tiles[i], options.get(i));
                        } else {
                            // Completely remove the entire row of shapes for this option
                            removeRowShapes(slide, tiles[i]);
                        }
                    }
                }
            }

            // ── Debrief / Reflect / Thank-You (debrief) ──────────────────────────
            // Template has: a top question box, a Suggested Answer box, and two smaller tiles
            // Data fields: suggestedAnswer, commonMisconceptions, keyTakeaway
            case "debrief" -> {
                // Clear the reflection prompt placeholders
                replaceTextInSlide(slide, "[Write the reflection prompt", "");
                replaceTextInSlide(slide, "[Write the question", "");
                
                String suggestedAnswer = (String) slideData.get("suggestedAnswer");
                if (suggestedAnswer != null) {
                    replaceTextInSlide(slide, "[Summarize the correct answer", suggestedAnswer);
                }
                
                Object misconceptionsObj = slideData.get("commonMisconceptions");
                if (misconceptionsObj instanceof List<?> mList && !mList.isEmpty()) {
                    replaceTextInSlide(slide, "[Misconception participants often have]", mList.get(0).toString());
                    if (mList.size() > 1) {
                        replaceTextInSlide(slide, "[A second common mistake or gap]", mList.get(1).toString());
                    } else {
                        replaceTextInSlide(slide, "[A second common mistake or gap]", "");
                    }
                } else {
                    replaceTextInSlide(slide, "[Misconception participants often have]", "");
                    replaceTextInSlide(slide, "[A second common mistake or gap]", "");
                }
                replaceTextInSlide(slide, "[Why this misconception happens]", "");
                
                String keyTakeaway = (String) slideData.get("keyTakeaway");
                if (keyTakeaway != null) {
                    replaceTextInSlide(slide, "[State the one main insight", keyTakeaway);
                } else {
                    replaceTextInSlide(slide, "[State the one main insight", "");
                }
            }

            // ── Lecture placeholder ───────────────────────────────────────────────
            // Template has: instructional text telling the instructor to insert slides
            // We leave it as-is (no dynamic content to inject)
            case "lecture_placeholder" -> { /* intentionally left blank */ }

            // ── Agenda (bullets) ─────────────────────────────────────────────────
            case "agenda" -> {
                List<String> bullets = (List<String>) slideData.get("bullets");
                if (bullets != null) {
                    String[] placeholders = {
                        "[Learning Goal 1]", 
                        "[Learning Goal 2]", 
                        "[Learning Goal 3]", 
                        "[Learning Goal ...]"
                    };
                    for (int i = 0; i < placeholders.length; i++) {
                        if (i < bullets.size()) {
                            replaceTextInSlide(slide, placeholders[i], bullets.get(i));
                        } else {
                            replaceTextInSlide(slide, placeholders[i], "");
                        }
                    }
                    // Handle edge cases where placeholders might just be "[Learning Goal]"
                    replaceTextInSlide(slide, "[Learning Goal]", "");
                }
                
                if (Boolean.TRUE.equals(slideData.get("isClosingAgenda"))) {
                    replaceTextInSlide(slide, "What We'll Cover Today", "What We've Covered Today");
                }
            }

            // ── Welcome ──────────────────────────────────────────────────────────
            case "welcome" -> {
                List<String> bullets = (List<String>) slideData.get("bullets");
                if (bullets != null && !bullets.isEmpty()) {
                    replaceBodyText(slide, bullets, "[Welcome message");
                }
            }

            // ── Break ────────────────────────────────────────────────────────────
            case "break" -> {
                List<String> bullets = (List<String>) slideData.get("bullets");
                if (bullets != null && !bullets.isEmpty()) {
                    replaceTextInSlide(slide, "[X] minutes — see you soon!", bullets.get(0));
                    replaceTextInSlide(slide, "[X] minutes", bullets.get(0));
                }
            }

            // ── Default / standard content slides ────────────────────────────────
            default -> {
                List<String> bullets = (List<String>) slideData.get("bullets");
                if (bullets != null && !bullets.isEmpty()) {
                    replaceBodyText(slide, bullets,
                        "[First key point", "[Learning Goal", "[Summarize the",
                        "[Write the question", "Instructor content");
                }
            }
        }
    }

    /**
     * Clears the "TEMPLATE · &lt;layout&gt;" tag text that is baked into the bottom-right
     * corner of every template slide for design reference. On exported slides this
     * label should not be visible.
     */
    private void clearTemplateTag(org.apache.poi.xslf.usermodel.XSLFSlide slide) {
        for (org.apache.poi.xslf.usermodel.XSLFShape shape : slide.getShapes()) {
            if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape ts) {
                String text = ts.getText();
                if (text != null && text.contains("TEMPLATE")) {
                    try {
                        ts.clearText();
                    } catch (Exception e) {
                        log.debug("Could not clear TEMPLATE tag: {}", e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Sets the speaker notes on a slide, creating the notes slide if needed.
     */
    private void setSlideNotes(org.apache.poi.xslf.usermodel.XSLFSlide slide, String notes) {
        try {
            org.apache.poi.xslf.usermodel.XSLFNotes notesSlide = slide.getSlideShow().getNotesSlide(slide);
            if (notesSlide == null) return;
            for (XSLFTextShape shape : notesSlide.getPlaceholders()) {
                if (shape.getTextType() == org.apache.poi.sl.usermodel.Placeholder.BODY) {
                    shape.setText(notes);
                    return;
                }
            }
            // Fallback: use first text shape if no BODY placeholder found
            if (notesSlide.getPlaceholders().length > 1) {
                notesSlide.getPlaceholders()[1].setText(notes);
            }
        } catch (Exception e) {
            log.debug("Could not set slide notes: {}", e.getMessage());
        }
    }


    /**
     * Draws an 8pt phase-accent left-border stripe on the slide (non-template path only).
     * Matches the {@code border-left: 8px solid} style in the reference HTML.
     */
    private void drawPhaseAccentStripe(XSLFSlide slide, java.awt.Color color) {
        try {
            java.awt.Dimension pgSize = slide.getSlideShow().getPageSize();
            org.apache.poi.xslf.usermodel.XSLFAutoShape stripe = slide.createAutoShape();
            stripe.setShapeType(org.apache.poi.sl.usermodel.ShapeType.RECT);
            stripe.setAnchor(new java.awt.geom.Rectangle2D.Double(0, 0, 8, pgSize.getHeight()));
            stripe.setFillColor(color);
            stripe.setLineColor(color);
            stripe.setLineWidth(0);
        } catch (Exception e) {
            log.debug("Could not draw phase accent stripe: {}", e.getMessage());
        }
    }
}
