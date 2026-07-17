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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PptxExportService {

    private static final Logger log = LoggerFactory.getLogger(PptxExportService.class);
    private final LlmService llm;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final Map<String, List<String>> FIXED_INSTRUCTIONS = new HashMap<>();
    private static final Map<String, String> FIXED_INSTRUCTION_TITLES = new HashMap<>();
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

    // ── Public: export full session to PPTX (optionally using pre-built slides cache) ──────────

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

    // ── Public: generate slide JSON for a single block ──────────────────────────────────────────

    public List<String> renderAllSlidePreviews(WorkshopSessionDto session, WorkshopInputDto meta,
                                               List<Map<String, Object>> prebuiltSlides, java.io.InputStream templateStream) throws Exception {
        byte[] pptxBytes = exportToPptxInternal(session, meta, prebuiltSlides, templateStream);
        org.apache.poi.xslf.usermodel.XMLSlideShow ppt = new org.apache.poi.xslf.usermodel.XMLSlideShow(new java.io.ByteArrayInputStream(pptxBytes));
        java.awt.Dimension pgsize = ppt.getPageSize();

        List<String> base64Images = new java.util.ArrayList<>();
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

    // ── Public: generate slide JSON for a single block ──────────────────────────────────────────

    public List<Map<String, Object>> generateBlockSlides(ActivityBlockDto block, WorkshopInputDto meta, java.util.List<LearningGoalPlanDto> goals) throws Exception {
        String systemPrompt = buildSlideSystemPrompt();
        String phase = block.phase() != null ? block.phase().toUpperCase() : "";
        String label = block.phaseLabel() != null ? block.phaseLabel() : (block.phase() != null ? block.phase() : "");
        String labelUpper = label.toUpperCase();

        log.info("generateBlockSlides: phase='{}' phaseLabel='{}' labelUpper='{}'", phase, label, labelUpper);

        // Check both phase code and phaseLabel for robust matching
        boolean isIntro    = phase.contains("WELCOME") || phase.contains("SETUP") || phase.contains("INTRODUCTION") || phase.equals("INTRO") || phase.equals("ARRIVE")
                          || labelUpper.contains("WELCOME") || labelUpper.contains("SETUP") || labelUpper.contains("INTRODUCTION") || labelUpper.contains("ARRIVE");
        boolean isClosing  = phase.contains("WRAP") || phase.contains("CLOSING") || phase.contains("CONCLUSION")
                          || labelUpper.contains("WRAP") || labelUpper.contains("CLOSING") || labelUpper.contains("CONCLUSION");
        boolean isActivity = phase.contains("LEARNING") || phase.contains("CYCLE") || phase.contains("ACTIVATE") || phase.equals("CUSTOM") || phase.contains("CHECK")
                          || labelUpper.contains("LEARNING") || labelUpper.contains("CYCLE") || labelUpper.contains("ACTIVATE") || labelUpper.contains("CHECK");

        // ── 1. Intro block: placeholder first, then Agenda from learning goals ──────────────────
        if (isIntro) {
            List<Map<String, Object>> slides = new java.util.ArrayList<>();

            // Placeholder for instructor's own welcome/setup slides (comes first)
            Map<String, Object> placeholder = new java.util.LinkedHashMap<>();
            placeholder.put("subtitle", label);
            placeholder.put("title", "[Placeholder] " + label);
            placeholder.put("bullets", java.util.List.of("Insert your welcome & setup slides here"));
            placeholder.put("notes", "Instructor's own slides for welcome and setup.");
            slides.add(placeholder);

            // Agenda slide using the actual learning goals (comes after)
            Map<String, Object> agendaSlide = new java.util.LinkedHashMap<>();
            agendaSlide.put("subtitle", label);
            agendaSlide.put("title", "Agenda");
            if (goals != null && !goals.isEmpty()) {
                agendaSlide.put("bullets", goals.stream()
                        .map(g -> g.goal() != null ? g.goal() : g.originalGoal())
                        .filter(g -> g != null && !g.isBlank())
                        .collect(java.util.stream.Collectors.toList()));
            } else {
                agendaSlide.put("bullets", java.util.List.of("See session plan for today's agenda"));
            }
            agendaSlide.put("notes", "Agenda slide — learning goals for this session.");
            slides.add(agendaSlide);

            return slides;
        }

        // ── 2. Closing block: no agenda, just a debrief placeholder ──────────────────────────
        if (isClosing) {
            List<Map<String, Object>> slides = new java.util.ArrayList<>();
            Map<String, Object> placeholder = new java.util.LinkedHashMap<>();
            placeholder.put("subtitle", label);
            placeholder.put("title", "[Placeholder] " + label);
            placeholder.put("bullets", java.util.List.of("Insert your closing & summary slides here"));
            placeholder.put("notes", "Instructor's own slides for session wrap-up.");
            slides.add(placeholder);
            return slides;
        }

        // ── 3. Activity blocks: prepend a Lecturer Explains placeholder, then ask LLM for 2 activity slides ──
        List<Map<String, Object>> result = new java.util.ArrayList<>();

        if (isActivity) {
            boolean isCheck = phase.contains("CHECK") || labelUpper.contains("CHECK");
            Map<String, Object> explainPlaceholder = new java.util.LinkedHashMap<>();
            explainPlaceholder.put("subtitle", label);
            if (isCheck) {
                explainPlaceholder.put("title", "[Placeholder] Quiz / Poll Questions");
                explainPlaceholder.put("bullets", java.util.List.of("Insert your actual quiz or poll questions here"));
                explainPlaceholder.put("notes", "Instructor inserts the specific questions to check understanding.");
            } else {
                explainPlaceholder.put("title", "[Placeholder] You Explain \u2014 " + label);
                explainPlaceholder.put("bullets", java.util.List.of("Insert your lecture slides here"));
                explainPlaceholder.put("notes", "Instructor teaches the concept before the activity. Replace with your own content slides.");
            }
            result.add(explainPlaceholder);
        }

        // ── 4. Ask LLM only for the interactive activity slides ────────────────────────────────
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Block Label: ").append(label).append("\n");
        userPrompt.append("Phase: ").append(phase).append("\n");
        userPrompt.append("Duration: ").append(block.duration()).append(" minutes\n");
        if (block.objective() != null) userPrompt.append("Objective: ").append(block.objective()).append("\n");

        if (isActivity) {
            userPrompt.append("\nRULES FOR THIS BLOCK:\n");
            userPrompt.append("- Generate EXACTLY 2 slides for the PARTICIPANT ACTIVITY part:\n");
            userPrompt.append("  1. PROMPT slide: The actual question/prompt students must work on. The TITLE must start with the primary activity type, followed by the exact question text (e.g. 'Poll: What do you think...'). Bullets are minimal.\n");
            userPrompt.append("  2. SUMMARY / DEBRIEF slide: Must be student-facing. Generate dynamic key takeaways or debrief questions based on the prompt/activity.\n");
            userPrompt.append("     - Address the students directly (e.g. 'Compare your reasoning...', 'Reflect on...', or open questions like 'How did you approach...').\n");
            userPrompt.append("     - NEVER write instructions for the instructor.\n");
            userPrompt.append("- Do NOT generate a slide for activity instructions.\n");
            userPrompt.append("- Do NOT generate a slide for students silently doing the activity.\n");
        }

        // Collect methods
        java.util.Set<String> allMethods = new java.util.LinkedHashSet<>();
        if (block.methods() != null) allMethods.addAll(block.methods());
        if (block.sections() != null) {
            for (ActivitySectionDto sec : block.sections()) {
                if (sec.methods() != null) allMethods.addAll(sec.methods());
            }
        }
        if (!allMethods.isEmpty()) {
            userPrompt.append("Teaching Methods / Activities: ").append(String.join(", ", allMethods)).append("\n");
        }

        // Surface the exact activity steps
        if (block.sections() != null && !block.sections().isEmpty()) {
            boolean hasSteps = block.sections().stream()
                    .anyMatch(s -> s.steps() != null && !s.steps().isEmpty());
            if (hasSteps) {
                userPrompt.append("\nDetailed Activity Steps (use as source material — do NOT create one slide per step):\n");
                for (ActivitySectionDto sec : block.sections()) {
                    if (sec.steps() != null && !sec.steps().isEmpty()) {
                        if (sec.title() != null && !sec.title().isBlank()) {
                            userPrompt.append("  [").append(sec.title()).append("]\n");
                        }
                        for (String step : sec.steps()) {
                            userPrompt.append("    \u2022 ").append(step).append("\n");
                        }
                    }
                }
            }
        }

        if (meta != null && meta.uploadedMaterialsText() != null && !meta.uploadedMaterialsText().isBlank()) {
            String text = meta.uploadedMaterialsText();
            if (text.length() > 8000) text = text.substring(0, 8000) + "\n[...truncated]";
            userPrompt.append("\nReference Materials:\n").append(text);
        }

        userPrompt.append("\nTask: Return ONLY a JSON array of slides. Respect the EXACTLY 2 slide count rule above strictly.");

        log.info("Requesting LLM to generate slides for block: {}", label);
        String rawResponse = llm.callSecondary(systemPrompt, userPrompt.toString());
        String json = llm.extractJsonArray(rawResponse);
        List<Map<String, Object>> llmSlides = mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});

        // Attach subtitle to LLM slides
        for (Map<String, Object> slide : llmSlides) {
            slide.put("subtitle", label);
        }

        // Programmatically prepend fixed instruction slides if applicable
        if (isActivity && !allMethods.isEmpty()) {
            for (String method : allMethods) {
                String normalized = method.toLowerCase().replaceAll("[^a-z]", "");
                if (FIXED_INSTRUCTIONS.containsKey(normalized)) {
                    Map<String, Object> instSlide = new LinkedHashMap<>();
                    instSlide.put("subtitle", label);
                    instSlide.put("title", "Instructions: " + FIXED_INSTRUCTION_TITLES.get(normalized));
                    instSlide.put("bullets", FIXED_INSTRUCTIONS.get(normalized));
                    instSlide.put("notes", "Fixed instructions for " + FIXED_INSTRUCTION_TITLES.get(normalized));
                    instSlide.put("fixedInstructionFor", normalized);
                    result.add(instSlide);
                }
            }
        }

        result.addAll(llmSlides);
        return result;
    }

    // ── Private helpers ─────────────────────────────────────────────────────────────────────────

    private byte[] exportToPptxInternal(WorkshopSessionDto session, WorkshopInputDto meta,
                                         List<Map<String, Object>> prebuiltSlides, java.io.InputStream templateStream) throws Exception {
        List<Map<String, Object>> slidesData;

        if (prebuiltSlides != null && !prebuiltSlides.isEmpty()) {
            // Fast path: use cached slides, skip LLM
            log.info("Assembling PPTX from {} pre-built slides (no LLM call)", prebuiltSlides.size());
            slidesData = prebuiltSlides;
        } else {
            // Slow path: ask LLM to generate slides for the whole session
            String systemPrompt = buildSlideSystemPrompt();
            StringBuilder userPrompt = new StringBuilder();
            userPrompt.append("Session Title: ").append(session.title()).append("\n\n");
            userPrompt.append("Session Plan (Blocks):\n");
            try {
                userPrompt.append(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(session.blocks())).append("\n\n");
            } catch (Exception e) {
                log.warn("Failed to serialize session blocks", e);
            }
            if (meta != null && meta.uploadedMaterialsText() != null && !meta.uploadedMaterialsText().isBlank()) {
                String text = meta.uploadedMaterialsText();
                if (text.length() > 20000) text = text.substring(0, 20000) + "\n[...truncated]";
                userPrompt.append("Uploaded Reference Materials:\n").append(text).append("\n\n");
            }
            userPrompt.append("Task: Generate activity-focused slides based on this session plan. Focus on the interactive parts.");

            log.info("Requesting LLM to generate slides for full session...");
            String rawResponse = llm.callSecondary(systemPrompt, userPrompt.toString());
            String json = llm.extractJsonArray(rawResponse);
            slidesData = mapper.readValue(json, new TypeReference<>() {});
        }

        return buildPptx(session, meta, slidesData, templateStream);
    }

    private org.apache.poi.xslf.usermodel.XSLFSlideLayout getTitleLayout(XMLSlideShow ppt) {
        if (ppt.getSlideMasters().isEmpty()) return null;
        
        // 1. Try standard TITLE layout across all masters
        for (org.apache.poi.xslf.usermodel.XSLFSlideMaster master : ppt.getSlideMasters()) {
            org.apache.poi.xslf.usermodel.XSLFSlideLayout layout = master.getLayout(org.apache.poi.xslf.usermodel.SlideLayout.TITLE);
            if (layout != null) return layout;
        }
        
        // 2. Try matching by name (prioritize 1_start, 1_title)
        for (org.apache.poi.xslf.usermodel.XSLFSlideMaster master : ppt.getSlideMasters()) {
            for (org.apache.poi.xslf.usermodel.XSLFSlideLayout layout : master.getSlideLayouts()) {
                String name = layout.getName().toLowerCase();
                if (name.equals("1_start") || name.equals("1_title") || name.equals("1_titel")) {
                    return layout;
                }
            }
        }

        // 3. Try matching by generic name
        for (org.apache.poi.xslf.usermodel.XSLFSlideMaster master : ppt.getSlideMasters()) {
            for (org.apache.poi.xslf.usermodel.XSLFSlideLayout layout : master.getSlideLayouts()) {
                String name = layout.getName().toLowerCase();
                if (name.contains("title") || name.contains("start") || name.contains("titel")) {
                    return layout;
                }
            }
        }
        
        // 4. Fallback to first layout of first master
        return ppt.getSlideMasters().get(0).getSlideLayouts()[0];
    }

    private boolean hasBodyPlaceholder(org.apache.poi.xslf.usermodel.XSLFSlideLayout layout) {
        for (org.apache.poi.xslf.usermodel.XSLFTextShape shape : layout.getPlaceholders()) {
            if (shape.getTextType() != null) {
                String name = shape.getTextType().name();
                if (name.equals("BODY") || name.equals("CONTENT") || name.equals("OBJECT")) {
                    return true;
                }
            }
        }
        return false;
    }

    private org.apache.poi.xslf.usermodel.XSLFSlideLayout getContentLayout(XMLSlideShow ppt) {
        if (ppt.getSlideMasters().isEmpty()) return null;
        
        // 1. Try standard TITLE_AND_CONTENT layout across all masters
        for (org.apache.poi.xslf.usermodel.XSLFSlideMaster master : ppt.getSlideMasters()) {
            org.apache.poi.xslf.usermodel.XSLFSlideLayout layout = master.getLayout(org.apache.poi.xslf.usermodel.SlideLayout.TITLE_AND_CONTENT);
            if (layout != null && hasBodyPlaceholder(layout)) return layout;
        }
        
        // 2. Try matching by name (must have a body placeholder)
        for (org.apache.poi.xslf.usermodel.XSLFSlideMaster master : ppt.getSlideMasters()) {
            for (org.apache.poi.xslf.usermodel.XSLFSlideLayout layout : master.getSlideLayouts()) {
                if (hasBodyPlaceholder(layout)) {
                    String name = layout.getName().toLowerCase();
                    if (name.contains("content") || name.contains("inhalt") || name.contains("text")) {
                        return layout;
                    }
                }
            }
        }
        
        // 3. Fallback to second layout of first master, or first
        org.apache.poi.xslf.usermodel.XSLFSlideMaster master = ppt.getSlideMasters().get(0);
        if (master.getSlideLayouts().length > 1) {
            return master.getSlideLayouts()[1];
        }
        return master.getSlideLayouts()[0];
    }

    private void safeSetText(org.apache.poi.xslf.usermodel.XSLFTextShape shape, String text) {
        try {
            shape.setText(text);
        } catch (IndexOutOfBoundsException e) {
            shape.clearText();
            org.apache.poi.xslf.usermodel.XSLFTextParagraph p = shape.addNewTextParagraph();
            org.apache.poi.xslf.usermodel.XSLFTextRun r = p.addNewTextRun();
            r.setText(text);
        }
    }

    private static final java.awt.Color HESTIA_PRIMARY        = new java.awt.Color(135, 84, 29);
    private static final java.awt.Color HESTIA_PRIMARY_LIGHT  = new java.awt.Color(200, 155, 90);
    private static final java.awt.Color HESTIA_FOREGROUND     = new java.awt.Color(44, 39, 37);
    private static final java.awt.Color HESTIA_BG             = new java.awt.Color(242, 237, 228);
    private static final java.awt.Color HESTIA_SEPARATOR      = new java.awt.Color(218, 208, 193);

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

    private void safeSetTitleAndSubtitle(org.apache.poi.xslf.usermodel.XSLFTextShape shape, String title, String subtitle, java.awt.Color subtitleColor) {
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

    private org.apache.poi.xslf.usermodel.XSLFTextShape getShapeByType(XSLFSlide slide, String... types) {
        for (String type : types) {
            for (org.apache.poi.xslf.usermodel.XSLFTextShape shape : slide.getPlaceholders()) {
                if (shape.getTextType() != null && shape.getTextType().name().equals(type)) return shape;
            }
        }
        if (slide.getPlaceholders().length > 0 && types.length > 0 && (types[0].equals("TITLE") || types[0].equals("CENTER_TITLE") || types[0].equals("CENTERED_TITLE"))) {
            return slide.getPlaceholders()[0];
        }
        if (slide.getPlaceholders().length > 1 && types.length > 0 && (types[0].equals("BODY") || types[0].equals("CONTENT") || types[0].equals("SUBTITLE"))) {
            return slide.getPlaceholders()[1];
        }
        return null;
    }

    private byte[] buildPptx(WorkshopSessionDto session, WorkshopInputDto meta,
                              List<Map<String, Object>> slidesData, java.io.InputStream templateStream) throws Exception {
        try (XMLSlideShow ppt = templateStream != null ? new XMLSlideShow(templateStream) : new XMLSlideShow()) {
            boolean useTemplate = (templateStream != null);
            java.awt.Color subtitleColor = useTemplate ? getTemplateAccentColor(ppt) : HESTIA_PRIMARY;

            if (useTemplate) {
                // Remove all existing slides from the template
                for (int i = ppt.getSlides().size() - 1; i >= 0; i--) {
                    ppt.removeSlide(i);
                }
            } else {
                // Set 16:9 widescreen dimensions (960×540 pts = 13.33"×7.5" at 72dpi)
                // so the rendered PNG preview matches the aspect-video container in the UI
                ppt.setPageSize(new java.awt.Dimension(960, 540));
            }

            // ── Title / cover slide ──────────────────────────────────────────
            org.apache.poi.xslf.usermodel.XSLFSlideLayout titleLayout = getTitleLayout(ppt);
            XSLFSlide titleSlide = titleLayout != null ? ppt.createSlide(titleLayout) : ppt.createSlide();

            if (!useTemplate) titleSlide.getBackground().setFillColor(HESTIA_BG);

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

            // ── Content slides ───────────────────────────────────────────────
            for (Map<String, Object> slideData : slidesData) {
                String slideTitle    = (String) slideData.get("title");
                String slideSubtitle = (String) slideData.get("subtitle");
                String notesText     = (String) slideData.get("notes");
                @SuppressWarnings("unchecked")
                List<String> bullets = (List<String>) slideData.get("bullets");

                org.apache.poi.xslf.usermodel.XSLFSlideLayout contentLayout = getContentLayout(ppt);
                XSLFSlide slide = contentLayout != null ? ppt.createSlide(contentLayout) : ppt.createSlide();

                if (!useTemplate) slide.getBackground().setFillColor(HESTIA_BG);

                // ── Title shape ──────────────────────────────────────────────
                XSLFTextShape shapeTitle = getShapeByType(slide, "TITLE", "CENTERED_TITLE", "CENTER_TITLE");
                if (shapeTitle != null) {
                    if (useTemplate) {
                        safeSetTitleAndSubtitle(shapeTitle, slideTitle, slideSubtitle, subtitleColor);
                    } else {
                        shapeTitle.clearText();

                        // Optional: breadcrumb label (subtitle as small uppercase label above title)
                        if (slideSubtitle != null && !slideSubtitle.isBlank()) {
                            org.apache.poi.xslf.usermodel.XSLFTextParagraph breadcrumb = shapeTitle.addNewTextParagraph();
                            org.apache.poi.xslf.usermodel.XSLFTextRun br = breadcrumb.addNewTextRun();
                            br.setText(slideSubtitle.toUpperCase());
                            br.setFontColor(HESTIA_PRIMARY_LIGHT);
                            br.setFontSize(11d);
                            breadcrumb.setSpaceAfter(4d);
                        }

                        // Main title
                        org.apache.poi.xslf.usermodel.XSLFTextParagraph ctp = shapeTitle.addNewTextParagraph();
                        org.apache.poi.xslf.usermodel.XSLFTextRun ctr = ctp.addNewTextRun();
                        ctr.setText(slideTitle != null ? slideTitle : "Slide");
                        ctr.setFontColor(HESTIA_FOREGROUND);
                        ctr.setBold(true);
                        ctr.setFontSize(28d);
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
                XSLFTextShape body = getShapeByType(slide, "BODY", "CONTENT", "OBJECT");
                if (body != null) {
                    body.clearText();

                    if (bullets != null && !bullets.isEmpty()) {
                        for (String bullet : bullets) {
                            org.apache.poi.xslf.usermodel.XSLFTextParagraph bp = body.addNewTextParagraph();
                            bp.setBullet(true);
                            org.apache.poi.xslf.usermodel.XSLFTextRun br = bp.addNewTextRun();
                            br.setText(bullet);
                            if (!useTemplate) {
                                bp.setBulletFontColor(HESTIA_PRIMARY);   // amber bullet dot
                                br.setFontColor(HESTIA_FOREGROUND);      // dark warm text
                                br.setFontSize(20d);
                                bp.setSpaceAfter(10d);
                            }
                        }
                    }

                    // Notes go to the notes pane only — NOT on the slide body
                }

                // Always write notes to the PowerPoint notes pane
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

    private String buildSlideSystemPrompt() {
        return """
                You are an expert instructional designer creating presentation slides for an active-learning workshop.
                You will be provided with a single session block and its detailed steps.

                CRITICAL RULES — YOU MUST FOLLOW THESE EXACTLY:
                1. DO NOT create one slide per step or per content line. Combine related steps into one slide.
                2. NEVER copy the session plan's description or timetable details verbatim.
                3. ALL SLIDES ARE STRICTLY STUDENT-FACING. You must address the students directly (e.g., "What are your thoughts on...", "Discuss with your partner..."). NEVER address the instructor or describe what the instructor will do (e.g., NO "Ask students to...", NO "Explain the concept of...").
                4. Any instructions or tips for the instructor MUST go exclusively into the 'notes' field.
                5. DO NOT create slides for Buffer or Break blocks.

                SLIDE COUNT LIMITS (STRICT):
                - Welcome / Setup / Introduction / Closing / Summary blocks: MAXIMUM 2 slides total.
                  → Always include an Agenda slide for Intros. Combine all other content into one placeholder slide.
                - Activity / Check Understanding blocks: EXACTLY 2 slides:
                  1. PROMPT: The slide title IS the actual question or prompt text, preceded by the activity type (e.g., 'Poll: What do you think?'). Keep bullets minimal.
                  2. SUMMARY / DEBRIEF slide: Generate dynamic key takeaways or debrief questions based on the activity, written directly to the students.
                     - Address students directly (e.g. 'Reflect on...', 'Compare your reasoning...', 'What were the challenges?').
                     - NEVER address the instructor or write instructions for them.
                  → Do NOT generate a slide for activity instructions or silent work.

                FORMATTING RULES:
                - MAXIMUM 3–4 bullet points per slide.
                - MAXIMUM 15 words per bullet.
                - Use 'notes' field for instructor-facing context and timing.
                - For placeholder slides, set title to "Placeholder: [Block Name]" and a single bullet like "Insert your slides here".

                Return ONLY a valid JSON array. No prose, no markdown fences.
                Schema:
                [
                  {
                    "title": "List all possible linear functions for one feature. What parameters define each?",
                    "bullets": ["5 minutes individual"],
                    "notes": "Prompt activity: students write down their answers before group share."
                  }
                ]
                """;
    }
}
