package com.workshopper.usecase;

import org.springframework.stereotype.Component;
import org.apache.poi.xslf.usermodel.*;
import java.util.List;
import java.util.Map;

import java.util.ArrayList;
import com.workshopper.dto.WorkshopSessionDto;
import com.workshopper.dto.WorkshopInputDto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.sl.usermodel.PaintStyle;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.sl.usermodel.TextShape;
import org.springframework.core.io.ClassPathResource;

@Component
public class AssemblePptxUseCase {

    private static final Logger log = LoggerFactory.getLogger(AssemblePptxUseCase.class);



    
    public Map<Integer, List<Map<String, Object>>> sanitizeSlides(Map<Integer, List<Map<String, Object>>> slides) {
        if (slides == null) return null;
        slides.forEach((idx, list) -> {
            if (list != null) {
                for (Map<String, Object> slide : list) {
                    sanitizeSlideContent(slide);
                }
            }
        });
        return slides;
    }
    
    private void sanitizeSlideContent(Map<String, Object> slide) {
        if (slide == null) return;
        for (Map.Entry<String, Object> entry : slide.entrySet()) {
            if (entry.getValue() instanceof String str) {
                if (str.length() > 5000) {
                    str = str.substring(0, 5000) + "... (truncated)";
                }
                str = str.replace("\0", "");
                slide.put(entry.getKey(), str);
            } else if (entry.getValue() instanceof List<?> list) {
                List<Object> safeList = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof String str) {
                        if (str.length() > 5000) {
                            str = str.substring(0, 5000) + "... (truncated)";
                        }
                        str = str.replace("\0", "");
                        safeList.add(str);
                    } else {
                        safeList.add(item);
                    }
                }
                slide.put(entry.getKey(), safeList);
            }
        }
    }


    public byte[] execute(WorkshopSessionDto session, WorkshopInputDto meta,
                                         List<Map<String, Object>> prebuiltSlides, InputStream templateStream, boolean isCustomTemplate) throws Exception {
        List<Map<String, Object>> slidesData;

        if (prebuiltSlides != null && !prebuiltSlides.isEmpty()) {
            log.info("Assembling PPTX from {} pre-built slides", prebuiltSlides.size());
            slidesData = prebuiltSlides;
        } else if (session != null && session.slides() != null && !session.slides().isEmpty()) {
            log.info("Assembling PPTX using cached slides from session object");
            List<Map<String, Object>> tempSlides = new ArrayList<>();
            session.slides().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        if (entry.getValue() != null) {
                            tempSlides.addAll(entry.getValue());
                        }
                    });
            slidesData = tempSlides;
        } else {
            log.warn("execute called without pre-built slides and no session slides — returning title-only deck");
            slidesData = List.of();
        }

        // Validate and sanitize the slide data
        // Pre-build a map with index to use the existing sanitizeSlides logic
        Map<Integer, List<Map<String, Object>>> wrapperMap = new HashMap<>();
        wrapperMap.put(0, slidesData);
        wrapperMap = sanitizeSlides(wrapperMap);
        slidesData = wrapperMap.get(0);

        return buildPptx(session, meta, slidesData, templateStream, isCustomTemplate);
    }


    // =========================================================================
    // Layout helpers
    // =========================================================================

    private void safeSetText(XSLFTextShape shape, String text) {
        try {
            shape.setText(text);
        } catch (IndexOutOfBoundsException e) {
            shape.clearText();
            XSLFTextParagraph p = shape.addNewTextParagraph();
            XSLFTextRun r = p.addNewTextRun();
            r.setText(text);
        }
    }

    // =========================================================================
    // PPTX rendering
    // =========================================================================

    
    private int getTemplateSlideIndex(String layout) {
        if (layout == null) return 9; // default fallback (summary)
        return switch (layout) {
            case "title" -> 0;
            case "agenda" -> 1;
            case "welcome" -> 2;
            case "activity_grid3" -> 3;
            case "activity_sidebar" -> 4;
            case "activity_q&a", "activity_qanda" -> 5;
            case "live_poll" -> 6;
            case "debrief" -> 7;
            case "lecture_placeholder" -> 8;
            case "summary", "concept_map", "default" -> 9;
            case "break" -> 10;
            default -> 9;
        };
    }

    private void replaceTextInSlide(XSLFSlide slide, String search, String replacement) {
        if (search == null) return;
        if (replacement == null) replacement = "";
        for (XSLFShape shape : slide.getShapes()) {
            if (shape instanceof XSLFTextShape ts) {
                if (ts.getText().contains(search)) {
                    for (XSLFTextParagraph p : ts.getTextParagraphs()) {
                        if (p.getText().contains(search)) {
                            String fullText = p.getText();
                            String regex = search.endsWith("]") 
                                ? Pattern.quote(search) 
                                : Pattern.quote(search) + "[^\\]]*\\]";
                            String newText = fullText.replaceAll(regex, Matcher.quoteReplacement(replacement));
                            if (newText.equals(fullText)) {
                                // Fallback: no closing bracket found – do a simple substring replace
                                newText = fullText.replace(search, replacement);
                            }
                            if (!p.getTextRuns().isEmpty()) {
                                PaintStyle targetColor = null;
                                String targetFontFamily = null;
                                Double targetFontSize = null;
                                boolean isBold = false;

                                for (XSLFTextRun r : p.getTextRuns()) {
                                    if (r.getRawText().contains(search)) {
                                        targetColor = r.getFontColor();
                                        targetFontFamily = r.getFontFamily();
                                        targetFontSize = r.getFontSize();
                                        isBold = r.isBold();
                                        break;
                                    }
                                }
                                if (targetColor == null && p.getTextRuns().size() > 1) {
                                    XSLFTextRun lastRun = p.getTextRuns().get(p.getTextRuns().size() - 1);
                                    targetColor = lastRun.getFontColor();
                                    targetFontFamily = lastRun.getFontFamily();
                                    targetFontSize = lastRun.getFontSize();
                                    isBold = lastRun.isBold();
                                }

                                XSLFTextRun firstRun = p.getTextRuns().get(0);
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
                    // Shrink the text now so it fits in every viewer, and keep normAutoFit for later edits
                    shrinkTextToFit(ts);
                    enableAutoFit(ts);
                }
            }
        }
    }

    /**
     * Enable "shrink text on overflow" (normAutoFit) on a text shape so that
     * long replacement strings never overflow the slide boundary.
     */
    private void enableAutoFit(XSLFTextShape shape) {
        try {
            shape.setTextAutofit(TextShape.TextAutofit.NORMAL);
        } catch (Exception e) {
            log.debug("Could not enable auto-fit on shape: {}", e.getMessage());
        }
    }

    /**
     * Lowers the font size of a text shape until its text fits inside the shape, keeping the
     * relative sizes of its runs. PowerPoint's own shrink-on-overflow only kicks in once the text
     * is edited, so without this a long replacement spills out of its box when the file is opened.
     */
    private void shrinkTextToFit(XSLFTextShape ts) {
        try {
            Rectangle2D anchor = ts.getAnchor();
            if (anchor == null) return;
            double width = anchor.getWidth() - ts.getLeftInset() - ts.getRightInset();
            double height = anchor.getHeight() - ts.getTopInset() - ts.getBottomInset();
            List<String> paragraphs = new ArrayList<>();
            double maxSize = 0;
            for (var p : ts.getTextParagraphs()) {
                paragraphs.add(p.getText());
                for (var r : p.getTextRuns()) {
                    if (r.getFontSize() != null) maxSize = Math.max(maxSize, r.getFontSize());
                }
            }
            if (maxSize <= 0) return;
            double fitted = PptxTextFit.fitFontSize(paragraphs, maxSize, Math.max(8, maxSize * 0.4), width, height);
            if (fitted >= maxSize) return;
            double scale = fitted / maxSize;
            for (var p : ts.getTextParagraphs()) {
                for (var r : p.getTextRuns()) {
                    if (r.getFontSize() != null) r.setFontSize(Math.max(8, Math.floor(r.getFontSize() * scale * 2) / 2));
                }
            }
        } catch (Exception e) {
            log.debug("Could not fit text to shape: {}", e.getMessage());
        }
    }

    private boolean shapeContainsText(XSLFShape shape, String searchString, boolean exact) {
        if (shape instanceof XSLFTextShape ts) {
            String text = ts.getText();
            if (exact) {
                return text.trim().equals(searchString);
            } else {
                return text.contains(searchString);
            }
        } else if (shape instanceof XSLFGroupShape group) {
            for (XSLFShape child : group.getShapes()) {
                if (shapeContainsText(child, searchString, exact)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void removeRowShapes(XSLFSlide slide, String searchString) {
        if (searchString == null) return;
        XSLFShape targetShape = null;
        for (XSLFShape shape : slide.getShapes()) {
            if (shapeContainsText(shape, searchString, false)) {
                targetShape = shape;
                break;
            }
        }
        if (targetShape == null) return;

        Rectangle2D targetAnchor = targetShape.getAnchor();
        double targetMinY = targetAnchor.getY();
        double targetMaxY = targetMinY + targetAnchor.getHeight();

        List<XSLFShape> toRemove = new ArrayList<>();
        for (XSLFShape shape : slide.getShapes()) {
            Rectangle2D anchor = shape.getAnchor();
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
        for (XSLFShape s : toRemove) {
            slide.removeShape(s);
        }
    }

    private void replaceBodyText(XSLFSlide slide, List<String> bullets, String... searchStrings) {
        if (bullets == null || bullets.isEmpty()) return;
        for (XSLFShape shape : slide.getShapes()) {
            if (shape instanceof XSLFTextShape) {
                XSLFTextShape ts = (XSLFTextShape) shape;
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
                    Color defaultFontColor = null;
                    boolean defaultBold = false;
                    boolean defaultItalic = false;
                    TextParagraph.TextAlign defaultAlign = null;
                    Double defaultLineSpacing = null;
                    Double defaultSpaceBefore = null;
                    Double defaultSpaceAfter = null;
                    Double defaultLeftMargin = null;
                    Double defaultIndent = null;
                    
                    if (!ts.getTextParagraphs().isEmpty()) {
                        XSLFTextParagraph firstPara = ts.getTextParagraphs().get(0);
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
                            XSLFTextRun firstRun = firstPara.getTextRuns().get(0);
                            if (firstRun.getFontSize() != null) defaultFontSize = firstRun.getFontSize();
                            if (firstRun.getFontFamily() != null) defaultFontFamily = firstRun.getFontFamily();
                            // Eagerly resolve PaintStyle → Color BEFORE clearText() disconnects the XML nodes.
                            // Holding a live PaintStyle/XSLFColor reference after clearText() causes XmlValueDisconnectedException.
                            PaintStyle rawColor = firstRun.getFontColor();
                            if (rawColor instanceof PaintStyle.SolidPaint) {
                                Color c = ((PaintStyle.SolidPaint) rawColor)
                                        .getSolidColor().getColor();
                                if (c != null) defaultFontColor = c;
                            }
                            defaultBold = firstRun.isBold();
                            defaultItalic = firstRun.isItalic();
                        }
                    }

                    ts.clearText();
                    
                    for (String bullet : bullets) {
                        XSLFTextParagraph bp = ts.addNewTextParagraph();
                        bp.setBullet(true);
                        bp.setBulletCharacter(defaultBulletChar);
                        if (defaultBulletFont != null) bp.setBulletFont(defaultBulletFont);
                        if (defaultAlign != null) bp.setTextAlign(defaultAlign);
                        if (defaultLineSpacing != null) bp.setLineSpacing(defaultLineSpacing);
                        if (defaultSpaceBefore != null) bp.setSpaceBefore(defaultSpaceBefore);
                        if (defaultSpaceAfter != null) bp.setSpaceAfter(defaultSpaceAfter);
                        if (defaultLeftMargin != null) bp.setLeftMargin(defaultLeftMargin);
                        if (defaultIndent != null) bp.setIndent(defaultIndent);
                        
                        XSLFTextRun br = bp.addNewTextRun();
                        br.setText(bullet);
                        if (defaultFontFamily != null) br.setFontFamily(defaultFontFamily);
                        br.setFontSize(defaultFontSize);
                        if (defaultFontColor != null) br.setFontColor(defaultFontColor);
                        br.setBold(defaultBold);
                        br.setItalic(defaultItalic);
                    }
                    shrinkTextToFit(ts);
                    break;
                }
            }
        }
    }

    private byte[] buildPptx(WorkshopSessionDto session, WorkshopInputDto meta,
                              List<Map<String, Object>> slidesData, InputStream templateStream, boolean isCustomTemplate) throws Exception {
        // An uploaded template only supplies branding: build the deck in the default design,
        // then add the template's title slide, logo and footer on top
        if (isCustomTemplate && templateStream != null) {
            byte[] templateBytes = templateStream.readAllBytes();
            try (InputStream defaultTemplate =
                         new ClassPathResource("templates/workshopper-default.pptx").getInputStream()) {
                byte[] deck = buildPptx(session, meta, slidesData, defaultTemplate, false);
                return TemplateBranding.apply(deck, templateBytes, session != null ? session.title() : null);
            } catch (Exception e) {
                log.warn("Could not apply the uploaded template's branding, using its layouts instead: {}", e.getMessage());
                templateStream = new ByteArrayInputStream(templateBytes);
            }
        }

        try (XMLSlideShow ppt = templateStream != null
                ? new XMLSlideShow(templateStream)
                : new XMLSlideShow()) {

            boolean useTemplate = (templateStream != null);
            int originalSlideCount = ppt.getSlides().size();
            boolean isCloningMode = useTemplate && !isCustomTemplate;

            if (!useTemplate) {
                ppt.setPageSize(new Dimension(960, 540));
            }

            
            // ── Title slide ─────────────────────────────────────────────────────
            if (useTemplate) {
                XSLFSlide titleSlide = ppt.createSlide();
                titleSlide.importContent(ppt.getSlides().get(0)); // template slide 0 = title
                if (isCloningMode) {
                    replaceTextInSlide(titleSlide, "[Session Title]", session.title() != null ? session.title() : "Workshop Session");
                    replaceTextInSlide(titleSlide, "[PHASE]", ""); // fallback
                } else {
                    for (XSLFShape shape : titleSlide.getShapes()) {
                        if (shape instanceof XSLFTextShape ts) {
                            if (ts.getTextType() == Placeholder.TITLE || ts.getTextType() == Placeholder.CENTERED_TITLE) {
                                safeSetText(ts, session.title() != null ? session.title() : "Workshop Session");
                            }
                        }
                    }
                }
            }

            for (Map<String, Object> slideData : slidesData) {
                String slideTitle    = (String) slideData.get("title");
                String slideSubtitle = (String) slideData.get("subtitle");
                String layout        = (String) slideData.getOrDefault("layout", "default");

                if (!useTemplate) continue;

                if (isCloningMode) {
                    int tplIdx = getTemplateSlideIndex(layout);
                    if (tplIdx >= originalSlideCount) tplIdx = 2; // fallback to generic content slide

                    XSLFSlide slide = ppt.createSlide();
                    slide.importContent(ppt.getSlides().get(tplIdx));

                    clearTemplateTag(slide);

                    String topicOnly = extractTopicOnly(slideTitle, layout);
                    String activityName = (String) slideData.get("activityName");
                    if (activityName != null) {
                        replaceTextInSlide(slide, "[ACTIVITY NAME]", activityName.toUpperCase());
                    }

                    String group = (String) slideData.get("group");
                    String phaseOrTopic = null;
                    if ("welcome".equals(group)) {
                        phaseOrTopic = "WELCOME";
                    } else if ("agenda".equals(group)) {
                        phaseOrTopic = "AGENDA";
                    } else if ("activate_prior_knowledge".equals(group)) {
                        phaseOrTopic = (layout != null && layout.startsWith("activity_")) ? "ACTIVATE" : "LECTURE";
                    } else if ("main_lecture".equals(group)) {
                        if ("debrief".equals(layout)) {
                            phaseOrTopic = "DEBRIEF";
                        } else if (layout != null && layout.startsWith("activity_")) {
                            phaseOrTopic = "ACTIVITY";
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

                    populateSlideBody(slide, slideData, layout);
                } else {
                    // Custom Template Injection (Generic layout)
                    int tplIdx = originalSlideCount > 2 ? 2 : (originalSlideCount > 1 ? 1 : 0);
                    XSLFSlide slide = ppt.createSlide();
                    slide.importContent(ppt.getSlides().get(tplIdx));

                    boolean bodyInjected = false;
                    for (XSLFShape shape : slide.getShapes()) {
                        if (shape instanceof XSLFTextShape ts) {
                            if (ts.getTextType() == Placeholder.TITLE || ts.getTextType() == Placeholder.CENTERED_TITLE) {
                                boolean isActivity = layout != null && (layout.startsWith("activity_") || layout.equals("live_poll"));
                                String titleText = slideTitle != null ? slideTitle : "";
                                if (isActivity) {
                                    String prompt = (String) slideData.get("activityPrompt");
                                    String pollQ = (String) slideData.get("pollQuestion");
                                    if (prompt != null && !prompt.isBlank()) titleText = prompt;
                                    else if (pollQ != null && !pollQ.isBlank()) titleText = pollQ;
                                }
                                safeSetText(ts, titleText);
                            } else if (ts.getTextType() == Placeholder.SUBTITLE) {
                                boolean isActivity = layout != null && (layout.startsWith("activity_") || layout.equals("live_poll"));
                                String subText = slideSubtitle != null ? slideSubtitle : "";
                                if (isActivity && slideTitle != null) {
                                    subText = slideTitle;
                                }
                                safeSetText(ts, subText);
                            } else if (ts.getTextType() == Placeholder.BODY && !bodyInjected) {
                                List<String> bodyLines = synthesizeGenericBody(slideData, layout);
                                if (bodyLines != null && !bodyLines.isEmpty()) {
                                    ts.clearText();
                                    for (String line : bodyLines) {
                                        XSLFTextParagraph p = ts.addNewTextParagraph();
                                        XSLFTextRun r = p.addNewTextRun();
                                        r.setText(line);
                                    }
                                    bodyInjected = true;
                                }
                            }
                        }
                    }
                }
            }

            // ── Remove the original template slides ───────────────────────────────
            if (useTemplate) {
                for (int i = originalSlideCount - 1; i >= 0; i--) {
                    ppt.removeSlide(i);
                }
            }


            ByteArrayOutputStream out = new ByteArrayOutputStream();
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
    private List<String> synthesizeGenericBody(Map<String, Object> slideData, String layout) {
        List<String> lines = new ArrayList<>();
        boolean isActivity = layout != null && (layout.startsWith("activity_") || layout.equals("live_poll"));
        
        List<String> bullets = (List<String>) slideData.get("bullets");
        if (bullets != null) lines.addAll(bullets);
        
        String prompt = (String) slideData.get("activityPrompt");
        if (!isActivity && prompt != null && !prompt.isBlank()) lines.add(prompt);
        
        List<String> instructions = (List<String>) slideData.get("activityInstructions");
        if (instructions != null && !instructions.isEmpty()) lines.addAll(instructions);
        
        String expectation = (String) slideData.get("activityOutputExpectation");
        if (expectation != null && !expectation.isBlank()) lines.add("Output: " + expectation);
        
        String question = (String) slideData.get("pollQuestion");
        if (!isActivity && question != null && !question.isBlank()) lines.add(question);
        
        List<String> options = (List<String>) slideData.get("pollOptions");
        if (options != null && !options.isEmpty()) {
            for (int i = 0; i < options.size(); i++) lines.add((char)('A' + i) + ") " + options.get(i));
        }
        
        String answer = (String) slideData.get("suggestedAnswer");
        if (answer != null && !answer.isBlank()) lines.add("Answer: " + answer);
        
        List<String> misconceptions = (List<String>) slideData.get("commonMisconceptions");
        if (misconceptions != null && !misconceptions.isEmpty()) {
            lines.add("Common Misconceptions:");
            for (String m : misconceptions) lines.add("- " + m);
        }
        
        String takeaway = (String) slideData.get("keyTakeaway");
        if (takeaway != null && !takeaway.isBlank()) lines.add("Key Takeaway: " + takeaway);
        
        return lines;
    }

    @SuppressWarnings("unchecked")
    /** One numbered row of the agenda slide: background box, number circle, number and goal text. */
    private record AgendaRow(XSLFShape box,
                             XSLFShape circle,
                             XSLFTextShape number,
                             XSLFTextShape goal,
                             List<XSLFShape> members) {}

    /**
     * Gives the agenda slide one row per learning goal: rows beyond the number of goals are
     * removed, extra rows are copied from the last one, and the rows are re-spaced to fill the
     * same area. Each goal text box fills its row and its font shrinks until every goal fits.
     *
     * @return false if the slide does not have the expected row structure, so the caller can
     *         fall back to plain placeholder replacement
     */
    private boolean layoutAgendaRows(XSLFSlide slide, List<String> goals) {
        List<AgendaRow> rows = findAgendaRows(slide);
        if (rows.isEmpty()) return false;
        try {
            // Template leftovers inside a row (e.g. a hidden second number) are dropped
            Set<XSLFShape> removed = new HashSet<>();
            for (AgendaRow row : rows) {
                for (var m : row.members()) {
                    if (m != row.box() && m != row.circle() && m != row.number() && m != row.goal() && removed.add(m)) {
                        slide.removeShape(m);
                    }
                }
            }

            int count = goals.size();
            int templateRows = rows.size();
            Rectangle2D firstBox = rows.get(0).box().getAnchor();
            Rectangle2D lastBox = rows.get(templateRows - 1).box().getAnchor();
            double top = firstBox.getY();
            double bottom = lastBox.getY() + lastBox.getHeight();
            double rowHeight = firstBox.getHeight();
            double gap = templateRows > 1
                    ? rows.get(1).box().getAnchor().getY() - (top + rowHeight)
                    : 12;

            // Fewer goals: drop the rows that are not needed
            for (int i = templateRows - 1; i >= count; i--) {
                for (var m : rows.get(i).members()) {
                    if (removed.add(m)) slide.removeShape(m);
                }
            }

            // Shape geometry and text are edited on the XML, which also covers the copied rows
            List<org.openxmlformats.schemas.presentationml.x2006.main.CTShape[]> rowXml = new ArrayList<>();
            for (int i = 0; i < Math.min(count, templateRows); i++) {
                AgendaRow row = rows.get(i);
                rowXml.add(new org.openxmlformats.schemas.presentationml.x2006.main.CTShape[]{
                        ctShape(row.box()), ctShape(row.circle()), ctShape(row.number()), ctShape(row.goal())});
            }
            if (count > templateRows) {
                var spTree = slide.getXmlObject().getCSld().getSpTree();
                long nextId = slide.getShapes().stream().mapToLong(XSLFShape::getShapeId).max().orElse(100) + 1;
                var source = rowXml.get(rowXml.size() - 1);
                for (int i = templateRows; i < count; i++) {
                    var copy = new org.openxmlformats.schemas.presentationml.x2006.main.CTShape[source.length];
                    for (int j = 0; j < source.length; j++) {
                        var added = spTree.addNewSp();
                        added.set(source[j].copy());
                        added.getNvSpPr().getCNvPr().setId(nextId++);
                        copy[j] = added;
                    }
                    rowXml.add(copy);
                }
                // More rows than the template has: share the same area with a tighter gap
                gap = Math.min(gap, 10);
                rowHeight = (bottom - top - gap * (count - 1)) / count;
            }

            AgendaRow template = rows.get(0);
            Rectangle2D circle = template.circle().getAnchor();
            Rectangle2D goalBox = template.goal().getAnchor();
            double circleSize = Math.min(circle.getHeight(), rowHeight - 8);
            double numberSize = fontSizeOf(template.number(), 14) * circleSize / circle.getHeight();
            double goalPad = 4;
            double goalHeight = rowHeight - 2 * goalPad;
            double goalWidth = goalBox.getWidth() - template.goal().getLeftInset() - template.goal().getRightInset();

            double goalFont = fontSizeOf(template.goal(), 30);
            for (String g : goals) {
                goalFont = Math.min(goalFont, PptxTextFit.fitFontSize(List.of(g), goalFont, 10, goalWidth,
                        goalHeight - template.goal().getTopInset() - template.goal().getBottomInset()));
            }

            for (int i = 0; i < rowXml.size(); i++) {
                var xml = rowXml.get(i);
                double y = top + i * (rowHeight + gap);
                setShapeY(xml[0], y, rowHeight);
                setShapeY(xml[1], y + (rowHeight - circleSize) / 2, circleSize);
                setShapeWidth(xml[1], circleSize);
                setShapeY(xml[2], y + (rowHeight - circleSize) / 2, circleSize);
                setShapeWidth(xml[2], circleSize);
                setShapeY(xml[3], y + goalPad, goalHeight);
                setShapeText(xml[2], String.valueOf(i + 1), numberSize);
                setShapeText(xml[3], goals.get(i), goalFont);
            }
            return true;
        } catch (Exception e) {
            log.warn("Agenda row layout failed, using plain placeholder replacement: {}", e.getMessage());
            return false;
        }
    }

    /** Finds the agenda rows by their "[Learning Goal ...]" placeholders, top to bottom. */
    private List<AgendaRow> findAgendaRows(XSLFSlide slide) {
        List<AgendaRow> rows = new ArrayList<>();
        List<XSLFTextShape> goalShapes = new ArrayList<>();
        for (var s : slide.getShapes()) {
            if (s instanceof XSLFTextShape ts && ts.getText().contains("[Learning Goal") && ts.getAnchor() != null) goalShapes.add(ts);
        }
        goalShapes.sort(Comparator.comparingDouble(s -> s.getAnchor().getY()));

        for (int i = 0; i < goalShapes.size(); i++) {
            XSLFTextShape goal = goalShapes.get(i);
            Rectangle2D g = goal.getAnchor();
            // The row's background box: an empty shape that contains the goal text's top-left corner
            XSLFShape box = null;
            for (var s : slide.getShapes()) {
                var a = s.getAnchor();
                if (a == null || s == goal || hasText(s) || a.getHeight() > 200) continue;
                if (a.getX() <= g.getX() + 1 && a.getY() <= g.getY() + 1 && g.getY() < a.getY() + a.getHeight()
                        && (box == null || a.getWidth() * a.getHeight() > box.getAnchor().getWidth() * box.getAnchor().getHeight())) {
                    box = s;
                }
            }
            if (box == null) return List.of();
            Rectangle2D b = box.getAnchor();
            List<XSLFShape> members = new ArrayList<>();
            for (var s : slide.getShapes()) {
                var a = s.getAnchor();
                if (a != null && a.getX() >= b.getX() - 2 && a.getY() >= b.getY() - 2
                        && a.getMaxX() <= b.getMaxX() + 2 && a.getMaxY() <= b.getMaxY() + 2) {
                    members.add(s);
                }
            }
            String expected = String.valueOf(i + 1);
            XSLFTextShape number = null;
            XSLFShape circle = null;
            for (var m : members) {
                if (m instanceof XSLFTextShape ts && ts.getText().trim().equals(expected) && number == null) number = ts;
            }
            if (number == null) return List.of();
            for (var m : members) {
                if (m != box && !hasText(m) && m.getAnchor().getY() <= number.getAnchor().getY() + 1
                        && m.getAnchor().getMaxY() >= number.getAnchor().getMaxY() - 1) circle = m;
            }
            if (circle == null) return List.of();
            rows.add(new AgendaRow(box, circle, number, goal, members));
        }
        return rows;
    }

    private static boolean hasText(XSLFShape s) {
        return s instanceof XSLFTextShape ts && !ts.getText().isBlank();
    }

    private static org.openxmlformats.schemas.presentationml.x2006.main.CTShape ctShape(XSLFShape s) {
        return (org.openxmlformats.schemas.presentationml.x2006.main.CTShape) s.getXmlObject();
    }

    private static double fontSizeOf(XSLFTextShape ts, double fallback) {
        for (var p : ts.getTextParagraphs()) {
            for (var r : p.getTextRuns()) {
                if (r.getFontSize() != null) return r.getFontSize();
            }
        }
        return fallback;
    }

    private static void setShapeY(org.openxmlformats.schemas.presentationml.x2006.main.CTShape sp, double yPt, double heightPt) {
        var xfrm = sp.getSpPr().getXfrm();
        xfrm.getOff().setY(Long.valueOf(Math.round(yPt * 12700)));
        xfrm.getExt().setCy(Math.round(heightPt * 12700));
    }

    private static void setShapeWidth(org.openxmlformats.schemas.presentationml.x2006.main.CTShape sp, double widthPt) {
        sp.getSpPr().getXfrm().getExt().setCx(Math.round(widthPt * 12700));
    }

    /**
     * Puts {@code text} in the first run of a shape at {@code sizePt}, keeping that run's formatting.
     * Other runs and paragraphs are emptied rather than removed, so cached POI wrappers stay valid.
     */
    private static void setShapeText(org.openxmlformats.schemas.presentationml.x2006.main.CTShape sp, String text, double sizePt) {
        var body = sp.getTxBody();
        if (body == null || body.sizeOfPArray() == 0) return;
        boolean placed = false;
        for (var p : body.getPArray()) {
            for (var r : p.getRArray()) {
                r.setT(placed ? "" : text);
                var rPr = r.isSetRPr() ? r.getRPr() : r.addNewRPr();
                rPr.setSz((int) Math.round(sizePt * 100));
                placed = true;
            }
        }
        if (!placed) {
            var r = body.getPArray(0).addNewR();
            r.setT(text);
            r.addNewRPr().setSz((int) Math.round(sizePt * 100));
        }
    }

    private void populateSlideBody(XSLFSlide slide,
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
            case "activity_sidebar" -> {
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
                if (bullets != null && layoutAgendaRows(slide, bullets)) {
                    // Rows were added, removed and filled to match the goals
                } else if (bullets != null) {
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
    private void clearTemplateTag(XSLFSlide slide) {
        for (XSLFShape shape : slide.getShapes()) {
            if (shape instanceof XSLFTextShape ts) {
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

}
