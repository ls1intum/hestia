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
            "1. Form groups and briefly introduce your perspectives",
            "2. Listen actively and build upon your peers' points",
            "3. Summarize your group's consensus to share with the class"
        ));
        addFixedInstruction("casestudy", "Case Study", List.of(
            "1. Read the provided scenario and identify the core problem",
            "2. Analyze the decisions and discuss alternative approaches",
            "3. Connect the case outcomes to today's learning objectives"
        ));
        addFixedInstruction("roleplay", "Role Play", List.of(
            "1. Review your assigned character's goals and background",
            "2. Stay in character and respond naturally to the scenario",
            "3. Step out of character afterwards to debrief the experience"
        ));
        addFixedInstruction("handsonpractice", "Hands-on Practice", List.of(
            "1. Attempt the task independently using provided materials",
            "2. Ask questions immediately if you hit a blocking issue",
            "3. Compare your solution with peers or the reference solution"
        ));
        addFixedInstruction("quizpolls", "Quiz / Polls", List.of(
            "1. Read the question and all options carefully",
            "2. Answer honestly based on your current understanding",
            "3. Discuss the correct answer when revealed by the instructor"
        ));
        addFixedInstruction("qasession", "Q&A Session", List.of(
            "1. Formulate your question clearly and specifically",
            "2. Raise your hand or use the digital Q&A tool to submit it",
            "3. Listen to others' questions to avoid duplicates"
        ));
        addFixedInstruction("peerreview", "Peer Review", List.of(
            "1. Review your partner's work thoroughly and objectively",
            "2. Provide specific, actionable, and constructive feedback",
            "3. Discuss the feedback together to clarify misunderstandings"
        ));
        addFixedInstruction("brainstorming", "Brainstorming", List.of(
            "1. Share every idea that comes to mind, no matter how unusual",
            "2. Focus on quantity first, without filtering or judging",
            "3. Categorize and evaluate the ideas only after brainstorming ends"
        ));
        addFixedInstruction("thinkpairshare", "Think-Pair-Share", List.of(
            "1. THINK: Reflect silently on the prompt and note your thoughts",
            "2. PAIR: Discuss your reflections with a partner and compare views",
            "3. SHARE: Present your pair's conclusions to the entire group"
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

        return switch (phase) {
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
        breakSlide.put("layout", "standard");
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

        // ── Lecture placeholder (always present for ACTIVATE) ────────────────
        Map<String, Object> placeholder = new LinkedHashMap<>();
        placeholder.put("group", "activate_prior_knowledge");
        placeholder.put("layout", "lecture_placeholder");
        placeholder.put("subtitle", label);
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
            String schemaSnippet;
            if ("quizpolls".equals(method)) {
                schemaSnippet = """
                    {
                      "layout": "live_poll",
                      "title": "Activate: [Topic]",
                      "pollQuestion": "the open activation question",
                      "pollOptions": ["A) ...", "B) ...", "C) ..."],
                      "notes": "a PLAIN STRING — expected answers/misconceptions"
                    }
                    """;
            } else {
                String layout = "activity_tiled";
                if (Set.of("roleplay", "casestudy", "handsonpractice", "qasession", "brainstorming").contains(method)) {
                    layout = "activity_sidebar";
                } else if ("thinkpairshare".equals(method)) {
                    layout = "activity_grid3";
                }
                List<String> instructions = FIXED_INSTRUCTIONS.getOrDefault(method, List.of("1. Review the prompt", "2. Formulate your thoughts", "3. Prepare to share"));
                String instJson = "[\\\"" + String.join("\\\", \\\"", instructions) + "\\\"]";
                schemaSnippet = """
                    {
                      "layout": "%s",
                      "title": "Activate: [Topic]",
                      "activityPrompt": "the open activation question",
                      "activityInstructions": %s,
                      "activityOutputExpectation": "what students should be prepared to share",
                      "notes": "a PLAIN STRING — expected answers/misconceptions"
                    }
                    """.formatted(layout, instJson);
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
            actSlide.put("subtitle", label);
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
        int lgIndex = block.goalTag() != null ? parseLgIndex(block.goalTag()) : 0;
        String lgText = resolveGoalText(lgIndex, goals, meta);

        // ── Slide 1: Lecture placeholder (no LLM) ───────────────────────────
        Map<String, Object> lecturePlaceholder = new LinkedHashMap<>();
        lecturePlaceholder.put("group", "main_lecture");
        lecturePlaceholder.put("layout", "lecture_placeholder");
        if (lgIndex > 0) lecturePlaceholder.put("lgIndex", lgIndex);
        lecturePlaceholder.put("subtitle", label);
        lecturePlaceholder.put("title", "[Placeholder] Lecture: " + label);
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
                
                SLIDE 2 — Per-cycle Debrief/Summary slide:
                  "layout": "debrief"
                  "title": "Reflect: [Topic]"  (use "Reflect:" or "Debrief:" prefix)
                  "debriefQuestion": single open-ended reflective question tied to THIS learning goal
                    (NOT about the activity mechanics — about the concept/skill itself)
                  Density: single-focus, low-density. NO bullet lists. One question + optional one-line scaffold.
                  "notes": a PLAIN STRING — expected reasoning, common misconceptions, suggested debrief technique.
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
        String[] expectedGroups = {"main_lecture", "main_lecture"};
        for (int i = 0; i < Math.min(llmSlides.size(), 2); i++) {
            Map<String, Object> slide = llmSlides.get(i);
            slide.put("group", expectedGroups[i]);
            slide.put("subtitle", label);
            if (lgIndex > 0) slide.put("lgIndex", lgIndex);
            if (!slide.containsKey("layout")) slide.put("layout", expectedLayouts[i]);
            slides.add(slide);
        }
        // Ensure we always have the debrief slide even if LLM returned only 1
        while (slides.size() < 3) {
            Map<String, Object> debriefFallback = new LinkedHashMap<>();
            debriefFallback.put("group", "main_lecture");
            debriefFallback.put("layout", "debrief");
            debriefFallback.put("subtitle", label);
            if (lgIndex > 0) debriefFallback.put("lgIndex", lgIndex);
            debriefFallback.put("title", "Reflect: " + label);
            debriefFallback.put("debriefQuestion", "What is the most important insight you gained from this activity?");
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
        debrief.put("title", "Reflect: " + label);
        debrief.put("debriefQuestion", "What is the most important insight you gained from this activity?");
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
        // Build the full list of LG strings — source of truth is meta.learningGoals()
        List<String> lgStrings = buildFullGoalStrings(goals, meta);
        if (lgStrings.isEmpty()) {
            log.warn("No learning goals found for Check Understanding block; generating one generic slide");
            lgStrings = List.of("General understanding of today's session content");
        }

        String sysPrompt = """
                You are an expert instructional designer writing Check Understanding poll slides.
                
                CONTENT TIERS (strictly enforced):
                1. Visible slide — student-facing. ONE clear multiple-choice question per slide.
                2. Speaker notes — correct answer + the lgIndex it maps to + facilitation cue (e.g. "If split: peer discuss").
                3. Invisible — omit entirely.
                
                You will receive a numbered list of session learning goals.
                Return a JSON ARRAY with EXACTLY ONE slide object per learning goal, in the same order.
                Do NOT include "(LG1)", "LG", or any learning goal tags in the visible 'title' or 'pollQuestion'.
                
                Each slide must follow this schema:
                {
                  "title": "Understanding Check",
                  "layout": "live_poll",
                  "pollQuestion": "...",
                  "pollOptions": ["A) ...", "B) ...", "C) ...", "D) ..."],
                  "notes": "PLAIN STRING — Correct: [X]. LG: [lgIndex]. Facilitation cue (e.g. 'If split: peer discuss')."
                }
                CRITICAL: the "notes" field MUST be a flat string, NOT a JSON object or nested structure.
                
                Question quality rules:
                - One concept-level question per LG (test understanding, not recall of a fact)
                - 4 options: one clearly correct, three plausible distractors
                - Options are mutually exclusive
                
                Return ONLY a valid JSON array. No prose.
                """;

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Session learning goals (generate exactly one question per goal, in order):\n");
        for (int i = 0; i < lgStrings.size(); i++) {
            userPrompt.append("  LG").append(i + 1).append(": ").append(lgStrings.get(i)).append("\n");
        }
        appendMaterials(userPrompt, meta, 6000);
        userPrompt.append("\nTask: Return a JSON array of exactly ").append(lgStrings.size())
                  .append(" poll slide objects (one per LG, in LG order) as specified.");

        log.info("LLM call: Check Understanding slides ({} LGs)", lgStrings.size());
        List<Map<String, Object>> llmSlides;
        try {
            String raw = llm.callSecondary(sysPrompt, userPrompt.toString());
            String json = llm.extractJsonArray(raw);
            llmSlides = normalizeSlides(mapper.readValue(json, new TypeReference<>() {}));

        } catch (Exception e) {
            log.warn("LLM failed for Check Understanding slides, using fallback: {}", e.getMessage());
            llmSlides = new ArrayList<>();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        String label = block.phaseLabel() != null ? block.phaseLabel() : "Understanding Check";
        for (int i = 0; i < lgStrings.size(); i++) {
            Map<String, Object> slide;
            if (i < llmSlides.size()) {
                slide = llmSlides.get(i);
            } else {
                // Fallback for missing slides
                slide = new LinkedHashMap<>();
                slide.put("layout", "live_poll");
                slide.put("title", "Understanding Check");
                slide.put("pollQuestion", "Which statement best describes: " + lgStrings.get(i) + "?");
                slide.put("pollOptions", List.of("A) Statement A", "B) Statement B", "C) Statement C", "D) Statement D"));
                slide.put("notes", "Correct: A. LG" + (i + 1) + ". Discuss with neighbor if split.");
            }
            slide.put("group", "check_understanding");
            slide.put("subtitle", label);
            slide.put("lgIndex", i + 1);
            if (!slide.containsKey("layout")) slide.put("layout", "live_poll");
            result.add(slide);
        }
        return result;
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
                
                Schema per slide:
                {
                  "title": "Summary & Wrap-Up",
                  "layout": "concept_map",   // or "default" for a bullets-based slide
                  "bullets": ["Key takeaway 1", "Key takeaway 2", ...],
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
            slide.put("subtitle", label);
            if (!slide.containsKey("layout")) slide.put("layout", "concept_map");
            slides.add(slide);
        }

        // ── Explicit closing "Thank you" slide (always added, no LLM) ────────
        Map<String, Object> thankYou = new LinkedHashMap<>();
        thankYou.put("group", "summary");
        thankYou.put("layout", "debrief");
        thankYou.put("subtitle", label);
        thankYou.put("title", "Thank You");
        thankYou.put("debriefQuestion", "Any final questions before we close?");
        thankYou.put("notes", "Wrap up remaining questions. Share contact details if desired. End on time.");
        slides.add(thankYou);

        return slides;
    }

    private List<Map<String, Object>> buildSummaryFallback(String label) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("title", "Summary & Wrap-Up");
        s.put("layout", "concept_map");
        s.put("bullets", List.of(
            "One-Minute Paper:",
            "1. What is the most important concept you learned today?",
            "2. What is your biggest remaining question?"
        ));
        s.put("notes", "Give 1 min for silent writing. Invite 2–3 to share. Collect papers if desired.");
        return List.of(s);
    }

    // =========================================================================
    // Private helpers — prompt assembly
    // =========================================================================

    private String getPrimaryMethod(ActivityBlockDto block) {
        if (block.methods() == null || block.methods().isEmpty()) return "";
        return block.methods().get(0).toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private String buildActivitySlidePrompt(ActivityBlockDto block) {
        String method = getPrimaryMethod(block);
        if ("quizpolls".equals(method)) {
            return """
                SLIDE 1 — Activity slide:
                  "layout": "live_poll"
                  "title": "Quiz / Polls: [Topic]"
                  "pollQuestion": "the student-facing question"
                  "pollOptions": ["A) ...", "B) ...", "C) ...", "D) ..."]
                  "notes": a PLAIN STRING — answer/reasoning, common wrong answers.
                    CRITICAL: "notes" MUST be a flat string, NOT a JSON object or nested structure.
                """;
        }

        String layout = "activity_tiled";
        if (Set.of("roleplay", "casestudy", "handsonpractice", "qasession", "brainstorming").contains(method)) {
            layout = "activity_sidebar";
        } else if ("thinkpairshare".equals(method)) {
            layout = "activity_grid3";
        }

        List<String> instructions = FIXED_INSTRUCTIONS.getOrDefault(method, List.of(
            "1. Review the provided prompt or scenario",
            "2. Discuss and formulate your response",
            "3. Prepare to share your conclusions"
        ));
        
        // Build JSON array string of instructions
        String instJson = "[\\\"" + String.join("\\\", \\\"", instructions) + "\\\"]";

        return """
                SLIDE 1 — Activity slide:
                  "layout": "%s"
                  "title": "[Specific Activity Name]: [Topic]" (e.g. use "Quiz", "Q&A", or "Think Pair Share" instead of "Activity")
                  "activityInstructions": %s
                  "activityPrompt": the student-facing question/task/scenario (do NOT include "LG" or learning goal tags)
                  "activityOutputExpectation": what students will present/submit (if applicable, else omit)
                  "notes": a PLAIN STRING — answer/reasoning, debrief technique, common wrong answers.
                    CRITICAL: "notes" MUST be a flat string, NOT a JSON object or nested structure.
                """.formatted(layout, instJson);
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
        try {
            shape.setText(title != null ? title : "Slide");
            if (subtitle != null && !subtitle.isBlank()) {
                org.apache.poi.xslf.usermodel.XSLFTextParagraph p = shape.addNewTextParagraph();
                org.apache.poi.xslf.usermodel.XSLFTextRun r = p.addNewTextRun();
                r.setText(subtitle);
                r.setFontColor(subtitleColor);
                r.setFontSize(16d);
                p.setSpaceBefore(0d);
            }
        } catch (IndexOutOfBoundsException e) {
            shape.clearText();
            org.apache.poi.xslf.usermodel.XSLFTextParagraph p1 = shape.addNewTextParagraph();
            org.apache.poi.xslf.usermodel.XSLFTextRun r1 = p1.addNewTextRun();
            r1.setText(title != null ? title : "Slide");
            if (subtitle != null && !subtitle.isBlank()) {
                org.apache.poi.xslf.usermodel.XSLFTextParagraph p2 = shape.addNewTextParagraph();
                org.apache.poi.xslf.usermodel.XSLFTextRun r2 = p2.addNewTextRun();
                r2.setText(subtitle);
                r2.setFontColor(subtitleColor);
                r2.setFontSize(16d);
                p2.setSpaceBefore(0d);
            }
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

    private byte[] buildPptx(WorkshopSessionDto session, WorkshopInputDto meta,
                              List<Map<String, Object>> slidesData, java.io.InputStream templateStream) throws Exception {
        try (XMLSlideShow ppt = templateStream != null ? new XMLSlideShow(templateStream) : new XMLSlideShow()) {
            boolean useTemplate = (templateStream != null);
            java.awt.Color subtitleColor = useTemplate ? getTemplateAccentColor(ppt) : HESTIA_PRIMARY;

            if (useTemplate) {
                for (int i = ppt.getSlides().size() - 1; i >= 0; i--) ppt.removeSlide(i);
            } else {
                ppt.setPageSize(new java.awt.Dimension(960, 540));
            }

            // ── 1. Title slide ───────────────────────────────────────────────
            org.apache.poi.xslf.usermodel.XSLFSlideLayout titleLayout = getTitleLayout(ppt);
            XSLFSlide titleSlide = titleLayout != null ? ppt.createSlide(titleLayout) : ppt.createSlide();

            if (!useTemplate) {
                titleSlide.getBackground().setFillColor(HESTIA_BG);
                drawPhaseAccentStripe(titleSlide, PHASE_SETUP);
            }

            XSLFTextShape titleShape = getShapeByType(titleSlide, "TITLE", "CENTERED_TITLE", "CENTER_TITLE");
            if (titleShape != null) {
                if (useTemplate) {
                    safeSetText(titleShape, session.title() != null ? session.title() : "Workshop Session");
                } else {
                    titleShape.clearText();
                    org.apache.poi.xslf.usermodel.XSLFTextParagraph tp = titleShape.addNewTextParagraph();
                    tp.setTextAlign(org.apache.poi.sl.usermodel.TextParagraph.TextAlign.CENTER);
                    org.apache.poi.xslf.usermodel.XSLFTextRun tr = tp.addNewTextRun();
                    tr.setText(session.title() != null ? session.title() : "Workshop Session");
                    tr.setFontColor(HESTIA_FOREGROUND);
                    tr.setBold(true);
                    tr.setFontSize(36d);
                }
            }

            XSLFTextShape subtitleShape = getShapeByType(titleSlide, "SUBTITLE", "BODY", "CONTENT");
            if (subtitleShape != null) {
                String subtitleText = meta != null && meta.sessionType() != null ? meta.sessionType() : "Lecture Slides";
                if (useTemplate) {
                    safeSetText(subtitleShape, subtitleText);
                } else {
                    subtitleShape.clearText();
                    org.apache.poi.xslf.usermodel.XSLFTextParagraph stp = subtitleShape.addNewTextParagraph();
                    stp.setTextAlign(org.apache.poi.sl.usermodel.TextParagraph.TextAlign.CENTER);
                    org.apache.poi.xslf.usermodel.XSLFTextRun str = stp.addNewTextRun();
                    str.setText(subtitleText);
                    str.setFontColor(HESTIA_PRIMARY);
                    str.setFontSize(20d);
                }
            }

            // ── 2. Content slides ────────────────────────────────────────────
            for (Map<String, Object> slideData : slidesData) {
                String slideTitle    = (String) slideData.get("title");
                String slideSubtitle = (String) slideData.get("subtitle");
                String notesText     = (String) slideData.get("notes");
                String layout        = (String) slideData.get("layout");

                @SuppressWarnings("unchecked")
                List<String> bullets = (List<String>) slideData.get("bullets");
                if (bullets == null) bullets = new ArrayList<>();
                else bullets = new ArrayList<>(bullets);

                // ── Resolve layout-specific fields into bullet list ───────────
                if (layout != null && layout.startsWith("activity_")) {
                    String prompt = (String) slideData.get("activityPrompt");
                    if (prompt != null) bullets.add("Prompt: " + prompt);
                    @SuppressWarnings("unchecked")
                    List<String> instructions = (List<String>) slideData.get("activityInstructions");
                    if (instructions != null) bullets.addAll(instructions);
                    String expectation = (String) slideData.get("activityOutputExpectation");
                    if (expectation != null) bullets.add("Expectation: " + expectation);

                } else if ("live_poll".equals(layout)) {
                    String pollQuestion = (String) slideData.get("pollQuestion");
                    if (pollQuestion != null) bullets.add(pollQuestion);
                    @SuppressWarnings("unchecked")
                    List<String> options = (List<String>) slideData.get("pollOptions");
                    if (options != null) bullets.addAll(options);

                } else if ("concept_map".equals(layout)) {
                    bullets.add("[Visual Concept Map Placeholder]");

                } else if ("lecture_placeholder".equals(layout)) {
                    // bullets already contain the placeholder text from Java

                } else if ("debrief".equals(layout)) {
                    // Single centred reflective question — use debriefQuestion field if present
                    String dq = (String) slideData.get("debriefQuestion");
                    if (dq != null && !dq.isBlank()) {
                        bullets.clear();
                        bullets.add(dq);
                    }
                }

                org.apache.poi.xslf.usermodel.XSLFSlideLayout contentLayout = getContentLayout(ppt);
                XSLFSlide slide = contentLayout != null ? ppt.createSlide(contentLayout) : ppt.createSlide();

                if (!useTemplate) {
                    slide.getBackground().setFillColor(java.awt.Color.WHITE);
                    drawPhaseAccentStripe(slide, resolvePhaseAccentColor(slideData));
                }

                // ── Title shape ──────────────────────────────────────────────
                XSLFTextShape shapeTitle = getShapeByType(slide, "TITLE", "CENTERED_TITLE", "CENTER_TITLE");
                XSLFTextShape body       = getShapeByType(slide, "BODY", "CONTENT", "OBJECT");

                if (shapeTitle != null) {
                    if (useTemplate) {
                        safeSetTitleAndSubtitle(shapeTitle, slideTitle, slideSubtitle, subtitleColor);
                    } else {
                        shapeTitle.clearText();

                        if (slideSubtitle != null && !slideSubtitle.isBlank()) {
                            org.apache.poi.xslf.usermodel.XSLFTextParagraph breadcrumb = shapeTitle.addNewTextParagraph();
                            org.apache.poi.xslf.usermodel.XSLFTextRun br = breadcrumb.addNewTextRun();
                            br.setText(slideSubtitle.toUpperCase());
                            br.setFontColor(HESTIA_PRIMARY_LIGHT);
                            br.setFontSize(11d);
                            breadcrumb.setSpaceAfter(4d);
                        }

                        org.apache.poi.xslf.usermodel.XSLFTextParagraph ctp = shapeTitle.addNewTextParagraph();
                        org.apache.poi.xslf.usermodel.XSLFTextRun ctr = ctp.addNewTextRun();
                        ctr.setText(slideTitle != null ? slideTitle : "Slide");
                        ctr.setFontColor(HESTIA_FOREGROUND);
                        ctr.setBold(false);
                        ctr.setFontSize(20d);
                        ctp.setSpaceAfter(6d);

                        // Separator line
                        java.awt.geom.Rectangle2D titleAnchor = shapeTitle.getAnchor();
                        org.apache.poi.xslf.usermodel.XSLFConnectorShape line = slide.createConnector();
                        line.setAnchor(new java.awt.geom.Rectangle2D.Double(
                                titleAnchor.getX(),
                                titleAnchor.getY() + titleAnchor.getHeight() - 8,
                                titleAnchor.getWidth(), 0));
                        line.setLineColor(HESTIA_SEPARATOR);
                        line.setLineWidth(1.0);
                    }
                }

                // ── Body / bullets ───────────────────────────────────────────
                if (body != null) {
                    body.clearText();
                    if (bullets != null && !bullets.isEmpty()) {
                        boolean isDebrief = "debrief".equals(layout);
                        for (String bullet : bullets) {
                            org.apache.poi.xslf.usermodel.XSLFTextParagraph bp = body.addNewTextParagraph();
                            if (!isDebrief) bp.setBullet(true);
                            org.apache.poi.xslf.usermodel.XSLFTextRun br = bp.addNewTextRun();
                            br.setText(bullet);
                            if (!useTemplate) {
                                if (!isDebrief) bp.setBulletFontColor(HESTIA_PRIMARY);
                                br.setFontColor(HESTIA_FOREGROUND);
                                br.setFontSize(isDebrief ? 24d : 18d);
                                if (isDebrief) br.setItalic(true);
                                bp.setSpaceAfter(10d);
                                if (isDebrief) bp.setTextAlign(org.apache.poi.sl.usermodel.TextParagraph.TextAlign.CENTER);
                            }
                        }
                    }
                }

                // ── Speaker notes ────────────────────────────────────────────
                if (notesText != null && !notesText.isBlank()) {
                    try {
                        org.apache.poi.xslf.usermodel.XSLFNotes notesSlide = ppt.getNotesSlide(slide);
                        if (notesSlide != null) {
                            for (XSLFTextShape shape : notesSlide.getPlaceholders()) {
                                if (shape.getTextType() == org.apache.poi.sl.usermodel.Placeholder.BODY) {
                                    shape.setText(notesText);
                                    break;
                                }
                            }
                        }
                    } catch (Exception ex) {
                        // Ignore if notes master is missing
                    }
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ppt.write(out);
            return out.toByteArray();
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
