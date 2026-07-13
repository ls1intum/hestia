package com.workshopper.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workshopper.dto.ActivityBlockDto;
import com.workshopper.dto.ActivitySectionDto;
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
import java.util.List;
import java.util.Map;

@Service
public class PptxExportService {

    private static final Logger log = LoggerFactory.getLogger(PptxExportService.class);
    private final LlmService llm;
    private final ObjectMapper mapper = new ObjectMapper();

    public PptxExportService(LlmService llm) {
        this.llm = llm;
    }

    // ── Public: export full session to PPTX (optionally using pre-built slides cache) ──────────

    public byte[] exportToPptx(PdfExportRequestDto request) throws Exception {
        return exportToPptxInternal(request.session(), request.meta(), null, null);
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

    public List<Map<String, Object>> generateBlockSlides(ActivityBlockDto block, WorkshopInputDto meta) throws Exception {
        String systemPrompt = buildSlideSystemPrompt();

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Generate slides for a SINGLE session block only.\n\n");
        userPrompt.append("Block Label: ").append(block.phaseLabel()).append("\n");
        userPrompt.append("Phase: ").append(block.phase()).append("\n");
        userPrompt.append("Duration: ").append(block.duration()).append(" minutes\n");
        if (block.objective() != null) userPrompt.append("Objective: ").append(block.objective()).append("\n");
        if (block.description() != null) userPrompt.append("Description: ").append(block.description()).append("\n");

        // Collect all methods: block-level + section-level (deduplicated)
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

        // ── CRITICAL: explicitly surface the exact activity steps from the timetable ──
        // These strings (e.g. "5 min - Poll: What is the P vs NP problem?") are what
        // the instructor will actually run. Slides MUST match them exactly.
        if (block.sections() != null && !block.sections().isEmpty()) {
            boolean hasSteps = block.sections().stream()
                    .anyMatch(s -> s.steps() != null && !s.steps().isEmpty());
            if (hasSteps) {
                userPrompt.append("\nDetailed Activity Steps (MUST be reflected in slides — use these exact prompts/questions):\n");
                for (ActivitySectionDto sec : block.sections()) {
                    if (sec.steps() != null && !sec.steps().isEmpty()) {
                        if (sec.title() != null && !sec.title().isBlank()) {
                            userPrompt.append("  [").append(sec.title()).append("]\n");
                        }
                        for (String step : sec.steps()) {
                            userPrompt.append("    • ").append(step).append("\n");
                        }
                    }
                }
            } else {
                // No step-level detail — fall back to structured JSON
                userPrompt.append("\nSection Structure:\n");
                try {
                    userPrompt.append(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(block.sections())).append("\n");
                } catch (Exception ignored) {}
            }
        }

        if (meta != null && meta.uploadedMaterialsText() != null && !meta.uploadedMaterialsText().isBlank()) {
            String text = meta.uploadedMaterialsText();
            if (text.length() > 8000) text = text.substring(0, 8000) + "\n[...truncated]";
            userPrompt.append("\nReference Materials:\n").append(text);
        }
        userPrompt.append("\nTask: Return ONLY a JSON array of slides for this block. Each slide must directly correspond to the activity steps listed above. If a step is a poll, quiz, or question prompt, the slide should present that exact question/prompt to students.");

        log.info("Requesting LLM to generate slides for block: {}", block.phaseLabel());
        String rawResponse = llm.callSecondary(systemPrompt, userPrompt.toString());
        String json = llm.extractJsonArray(rawResponse);
        List<Map<String, Object>> slides = mapper.readValue(json, new TypeReference<>() {});

        // Extract section label into subtitle for clarity in the deck
        String label = block.phaseLabel() != null ? block.phaseLabel() : block.phase();
        for (Map<String, Object> slide : slides) {
            slide.put("subtitle", label);
        }
        return slides;
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

    // HESTIA design palette — matches CSS variables in index.css
    // --primary: hsl(33 65% 32%)  → rgb(135, 84, 29)
    // --foreground: hsl(10 15% 15%) → rgb(44, 39, 37)
    // --muted-foreground: hsl(10 15% 42%) → rgb(120, 105, 100)
    // --background: hsl(40 21% 87%) → rgb(226, 216, 198)
    private static final java.awt.Color HESTIA_PRIMARY        = new java.awt.Color(135, 84, 29);
    private static final java.awt.Color HESTIA_PRIMARY_LIGHT  = new java.awt.Color(200, 155, 90);  // lighter tint for breadcrumb
    private static final java.awt.Color HESTIA_FOREGROUND     = new java.awt.Color(44, 39, 37);
    private static final java.awt.Color HESTIA_MUTED          = new java.awt.Color(120, 105, 100);
    private static final java.awt.Color HESTIA_BG             = new java.awt.Color(242, 237, 228);  // very light warm white, close to card
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
                p.setSpaceBefore(0d); // Ensure it sits right under the title
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
        // Fallback to indices if nothing matches
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
                You are an expert instructional designer creating presentation slides.
                You will be provided with a session plan and optionally existing reference materials.
                
                CRITICAL INSTRUCTIONS:
                1. DO NOT create "complete" lecture content slides. The user already knows their lecture content.
                2. INSTEAD, create slides that the instructor can insert into their existing slide deck to facilitate the activities.
                3. The slides MUST be student-facing (e.g., "Discuss with your neighbor...", "Take 5 minutes to solve...").
                4. NEVER copy the session plan's description or timetable details verbatim onto the slides.
                5. For interactive activities (questions, quizzes, polls), you MUST create a dedicated slide that contains ONLY the question(s) and options (if any).
                6. IMPORTANT: For non-interactive blocks like "Lecture", "Introduction", or "Break", you MUST create a single placeholder slide. Title it "Placeholder: [Block Name]" and add a single bullet like "Insert your lecture slides here" or "10 Minute Break".
                7. The interactive slides should be similar to typical active-learning in-class slides, featuring:
                   - Question prompts or polls (e.g., "True or False? Raise your hand if you think...")
                   - Activity instructions (e.g., "Work through Question 1 in your group:", "Think-Pair-Share")
                   - Case studies or scenarios (e.g., "The research group is tackling...")
                   - Exit cards (e.g., "A classifier is called linear because...")
                8. Keep slides uncluttered and punchy. MAXIMUM 3-4 bullet points per slide. MAXIMUM 15 words per bullet point.
                9. Use the 'notes' field to add the description, context, and timing of the activity for the instructor.
                
                Return ONLY a valid JSON array of slide objects. No prose, no markdown fences.
                Schema:
                [
                  {
                    "title": "Activity: Think-Pair-Share",
                    "bullets": ["Form a pair with your neighbor", "Take 2 minutes to discuss the next question"],
                    "notes": "Activity block: Think-Pair-Share. 5 minutes."
                  }
                ]
                """;
    }
}
