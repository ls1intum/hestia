package com.workshopper.usecase;

import org.springframework.stereotype.Component;
import org.apache.poi.xslf.usermodel.*;
import java.util.List;
import java.util.Map;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

import java.util.ArrayList;
import com.workshopper.dto.WorkshopSessionDto;
import com.workshopper.dto.WorkshopInputDto;
import java.awt.Color;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class AssemblePptxUseCase {

    private static final Logger log = LoggerFactory.getLogger(AssemblePptxUseCase.class);

    // TUM Corporate Design Colors
    private static final Color HESTIA_PRIMARY = new Color(48, 112, 179);
    private static final Color HESTIA_SECONDARY = new Color(162, 173, 0);

    private static final Color PHASE_SETUP = new Color(227, 114, 34);     // TUM Orange
    private static final Color PHASE_LECTURE = new Color(48, 112, 179);   // TUM Blue
    private static final Color PHASE_PRACTICE = new Color(162, 173, 0);   // TUM Green


    
    public List<String> renderAllSlidePreviews(com.workshopper.dto.WorkshopSessionDto session, com.workshopper.dto.WorkshopInputDto meta,
                                               List<Map<String, Object>> prebuiltSlides, java.io.InputStream templateStream) throws Exception {
        byte[] pptxBytes = execute(session, meta, prebuiltSlides, templateStream);
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
                List<Object> safeList = new java.util.ArrayList<>();
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


    public byte[] execute(com.workshopper.dto.WorkshopSessionDto session, com.workshopper.dto.WorkshopInputDto meta,
                                         List<Map<String, Object>> prebuiltSlides, java.io.InputStream templateStream) throws Exception {
        List<Map<String, Object>> slidesData;

        if (prebuiltSlides != null && !prebuiltSlides.isEmpty()) {
            log.info("Assembling PPTX from {} pre-built slides", prebuiltSlides.size());
            slidesData = prebuiltSlides;
        } else {
            log.warn("execute called without pre-built slides — returning title-only deck");
            slidesData = List.of();
        }

        // Validate and sanitize the slide data
        // Pre-build a map with index to use the existing sanitizeSlides logic
        Map<Integer, List<Map<String, Object>>> wrapperMap = new java.util.HashMap<>();
        wrapperMap.put(0, slidesData);
        wrapperMap = sanitizeSlides(wrapperMap);
        slidesData = wrapperMap.get(0);

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
        if ("activity_sidebar".equals(layout))      return PHASE_PRACTICE;
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
