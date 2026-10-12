package com.workshopper.usecase;

import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Applies the branding of an uploaded (e.g. university) template to a deck built from the
 * Workshopper default design, without taking over its slide layouts.
 *
 * <ul>
 *   <li>The title slide is rebuilt from the template's title layout (background art, logo and the
 *       title placeholder's position and font), with the session title in it.</li>
 *   <li>Every other slide gets the template's logo pictures, footer text and slide number on top.</li>
 * </ul>
 *
 * <p>Nothing here is specific to one university: everything is found through standard PowerPoint
 * structure (slide master, layouts, placeholder types), and positions are scaled to the deck's
 * page size, keeping logos at the same distance from their nearest edges.
 */
final class TemplateBranding {

    private static final Logger log = LoggerFactory.getLogger(TemplateBranding.class);
    private static final Set<Placeholder> TITLES = EnumSet.of(Placeholder.TITLE, Placeholder.CENTERED_TITLE);
    /** Pictures smaller than this share of the slide count as logos; bigger ones are background art. */
    private static final double MAX_LOGO_AREA = 0.12;

    private TemplateBranding() {}

    static byte[] apply(byte[] deckBytes, byte[] templateBytes, String sessionTitle) throws IOException {
        try (XMLSlideShow deck = new XMLSlideShow(new ByteArrayInputStream(deckBytes));
             XMLSlideShow template = new XMLSlideShow(new ByteArrayInputStream(templateBytes))) {

            Placement place = new Placement(template.getPageSize(), deck.getPageSize());
            XSLFSlideLayout titleLayout = findTitleLayout(template);
            XSLFSlideLayout contentLayout = findContentLayout(template, titleLayout);

            List<XSLFSlide> slides = deck.getSlides();
            if (!slides.isEmpty() && titleLayout != null) {
                brandTitleSlide(deck, slides.get(0), titleLayout, place, sessionTitle);
            }
            if (contentLayout != null) {
                String footerText = findFooterText(template, contentLayout, titleLayout);
                for (int i = 1; i < slides.size(); i++) {
                    brandContentSlide(deck, slides.get(i), contentLayout, footerText, i + 1, place);
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            deck.write(out);
            return out.toByteArray();
        }
    }

    // ── Finding the template's title and content layouts ─────────────────────────────

    /** The first slide's layout if it is a title slide, else a layout marked as title, else any layout with a title. */
    static XSLFSlideLayout findTitleLayout(XMLSlideShow template) {
        if (!template.getSlides().isEmpty()) {
            XSLFSlide first = template.getSlides().get(0);
            if (placeholder(first, TITLES) != null && first.getSlideLayout() != null) return first.getSlideLayout();
        }
        for (XSLFSlideMaster master : template.getSlideMasters()) {
            for (XSLFSlideLayout layout : master.getSlideLayouts()) {
                if (layout.getType() == SlideLayout.TITLE) return layout;
            }
        }
        for (XSLFSlideMaster master : template.getSlideMasters()) {
            for (XSLFSlideLayout layout : master.getSlideLayouts()) {
                if (placeholder(layout, TITLES) != null) return layout;
            }
        }
        return null;
    }

    /** The layout of the first non-title slide, else another layout of the title layout's master. */
    static XSLFSlideLayout findContentLayout(XMLSlideShow template, XSLFSlideLayout titleLayout) {
        List<XSLFSlide> slides = template.getSlides();
        for (int i = 1; i < slides.size(); i++) {
            XSLFSlideLayout layout = slides.get(i).getSlideLayout();
            if (layout != null && layout != titleLayout) return layout;
        }
        XSLFSlideMaster master = titleLayout != null ? titleLayout.getSlideMaster()
                : template.getSlideMasters().isEmpty() ? null : template.getSlideMasters().get(0);
        if (master == null) return null;
        for (XSLFSlideLayout layout : master.getSlideLayouts()) {
            if (layout != titleLayout) return layout;
        }
        return titleLayout;
    }

    /** Footer text from a real slide if there is one (that is what the author typed), else from the layout or master. */
    static String findFooterText(XMLSlideShow template, XSLFSlideLayout contentLayout, XSLFSlideLayout titleLayout) {
        for (XSLFSlide slide : template.getSlides()) {
            if (slide.getSlideLayout() == titleLayout) continue;
            String text = placeholderText(slide, Placeholder.FOOTER);
            if (text != null) return text;
        }
        String text = placeholderText(contentLayout, Placeholder.FOOTER);
        return text != null ? text : placeholderText(contentLayout.getSlideMaster(), Placeholder.FOOTER);
    }

    // ── Title slide ───────────────────────────────────────────────────────────────

    private static void brandTitleSlide(XMLSlideShow deck, XSLFSlide slide, XSLFSlideLayout layout,
                                        Placement place, String sessionTitle) {
        for (XSLFShape s : new ArrayList<>(slide.getShapes())) slide.removeShape(s);
        slide.getBackground().setFillColor(backgroundColor(layout));

        // Master art first (it sits behind the layout's), then the layout's own decoration
        if (layout.getFollowMasterGraphics()) copyDecoration(deck, slide, layout.getSlideMaster(), place);
        copyDecoration(deck, slide, layout, place);

        XSLFTextShape title = placeholder(layout, TITLES);
        if (title != null) {
            XSLFTextBox box = slide.createTextBox();
            box.setAnchor(place.map(title.getAnchor()));
            copyTextStyle(title, box, sessionTitle != null && !sessionTitle.isBlank() ? sessionTitle : "Workshop Session", place.scale());
            fitText(box);
        }
    }

    // ── Content slides ────────────────────────────────────────────────────────────

    private static void brandContentSlide(XMLSlideShow deck, XSLFSlide slide, XSLFSlideLayout layout,
                                          String footerText, int slideNumber, Placement place) {
        double pageArea = place.srcW() * place.srcH();
        List<XSLFSheet> sheets = new ArrayList<>();
        if (layout.getFollowMasterGraphics()) sheets.add(layout.getSlideMaster());
        sheets.add(layout);
        for (XSLFSheet sheet : sheets) {
            for (XSLFShape s : sheet.getShapes()) {
                if (s instanceof XSLFPictureShape pic && !isPlaceholder(pic) && pic.getAnchor() != null
                        && pic.getAnchor().getWidth() * pic.getAnchor().getHeight() < pageArea * MAX_LOGO_AREA) {
                    copyPicture(deck, slide, pic, place);
                }
            }
        }

        XSLFTextShape footer = firstPlaceholder(Placeholder.FOOTER, layout, layout.getSlideMaster());
        if (footer != null && footerText != null) {
            XSLFTextBox box = slide.createTextBox();
            box.setAnchor(place.map(footer.getAnchor()));
            copyTextStyle(footer, box, footerText, place.scale());
            fitText(box);
        }
        XSLFTextShape number = firstPlaceholder(Placeholder.SLIDE_NUMBER, layout, layout.getSlideMaster());
        if (number != null) {
            XSLFTextBox box = slide.createTextBox();
            box.setAnchor(place.map(number.getAnchor()));
            copyTextStyle(number, box, String.valueOf(slideNumber), place.scale());
        }
    }

    // ── Copying shapes ────────────────────────────────────────────────────────────

    /** Copies the non-placeholder pictures, shapes and text boxes of a master or layout. */
    private static void copyDecoration(XMLSlideShow deck, XSLFSlide slide, XSLFSheet sheet, Placement place) {
        for (XSLFShape s : sheet.getShapes()) {
            if (isPlaceholder(s) || s.getAnchor() == null) continue;
            try {
                if (s instanceof XSLFPictureShape pic) {
                    copyPicture(deck, slide, pic, place);
                } else if (s instanceof XSLFTextShape ts && !ts.getText().isBlank()) {
                    XSLFTextBox box = slide.createTextBox();
                    box.setAnchor(place.map(ts.getAnchor()));
                    copyFill(ts, box);
                    copyTextStyle(ts, box, ts.getText(), place.scale());
                } else if (s instanceof XSLFAutoShape shape) {
                    XSLFAutoShape copy = slide.createAutoShape();
                    copy.setShapeType(shape.getShapeType());
                    copy.setAnchor(place.map(shape.getAnchor()));
                    copy.setRotation(shape.getRotation());
                    copyFill(shape, copy);
                }
            } catch (Exception e) {
                log.debug("Skipped template shape '{}': {}", s.getShapeName(), e.getMessage());
            }
        }
    }

    private static void copyPicture(XMLSlideShow deck, XSLFSlide slide, XSLFPictureShape pic, Placement place) {
        XSLFPictureData data = pic.getPictureData();
        if (data == null) return;
        XSLFPictureData copy = deck.addPicture(data.getData(), data.getType());
        XSLFPictureShape shape = slide.createPicture(copy);
        shape.setAnchor(place.map(pic.getAnchor()));
    }

    /** Solid fill and outline only; theme colours are resolved so they survive the move to another deck. */
    private static void copyFill(XSLFSimpleShape from, XSLFSimpleShape to) {
        Color fill = from.getFillColor();
        if (fill != null) to.setFillColor(fill);
        Color line = from.getLineColor();
        if (line != null) {
            to.setLineColor(line);
            to.setLineWidth(from.getLineWidth());
        }
    }

    /** Puts {@code text} in {@code to} with the first run's resolved font, size, colour and alignment of {@code from}. */
    private static void copyTextStyle(XSLFTextShape from, XSLFTextBox to, String text, double fontScale) {
        to.clearText();
        to.setWordWrap(true);
        to.setInsets(from.getInsets());
        if (from.getVerticalAlignment() != null) to.setVerticalAlignment(from.getVerticalAlignment());

        XSLFTextParagraph sourcePara = from.getTextParagraphs().isEmpty() ? null : from.getTextParagraphs().get(0);
        XSLFTextRun sourceRun = sourcePara == null || sourcePara.getTextRuns().isEmpty() ? null : sourcePara.getTextRuns().get(0);

        XSLFTextParagraph para = to.addNewTextParagraph();
        if (sourcePara != null && sourcePara.getTextAlign() != null) para.setTextAlign(sourcePara.getTextAlign());
        XSLFTextRun run = para.addNewTextRun();
        run.setText(text);
        if (sourceRun != null) {
            Double size = sourceRun.getFontSize();
            run.setFontSize(Math.max(6, (size != null ? size : 18) * fontScale));
            if (sourceRun.getFontFamily() != null) run.setFontFamily(sourceRun.getFontFamily());
            run.setBold(sourceRun.isBold());
            run.setItalic(sourceRun.isItalic());
            var color = sourceRun.getFontColor();
            if (color instanceof org.apache.poi.sl.usermodel.PaintStyle.SolidPaint solid && solid.getSolidColor().getColor() != null) {
                run.setFontColor(solid.getSolidColor().getColor());
            }
        }
    }

    /** Shrinks a single-run text box (down to half its size) until its text fits. */
    private static void fitText(XSLFTextBox box) {
        if (box.getTextParagraphs().isEmpty() || box.getTextParagraphs().get(0).getTextRuns().isEmpty()) return;
        XSLFTextRun run = box.getTextParagraphs().get(0).getTextRuns().get(0);
        if (run.getFontSize() == null) return;
        Rectangle2D a = box.getAnchor();
        double size = run.getFontSize();
        double fitted = PptxTextFit.fitFontSize(List.of(box.getText()), size, size / 2,
                a.getWidth() - box.getLeftInset() - box.getRightInset(),
                a.getHeight() - box.getTopInset() - box.getBottomInset());
        if (fitted < size) run.setFontSize(fitted);
    }

    // ── Placeholder lookup ────────────────────────────────────────────────────────

    private static boolean isPlaceholder(XSLFShape s) {
        return s instanceof XSLFSimpleShape ss && ss.getPlaceholder() != null;
    }

    private static XSLFTextShape placeholder(XSLFSheet sheet, Set<Placeholder> types) {
        if (sheet == null) return null;
        for (XSLFShape s : sheet.getShapes()) {
            if (s instanceof XSLFTextShape ts && ts.getPlaceholder() != null && types.contains(ts.getPlaceholder())) return ts;
        }
        return null;
    }

    private static XSLFTextShape firstPlaceholder(Placeholder type, XSLFSheet... sheets) {
        for (XSLFSheet sheet : sheets) {
            XSLFTextShape ts = placeholder(sheet, EnumSet.of(type));
            if (ts != null && ts.getAnchor() != null) return ts;
        }
        return null;
    }

    private static String placeholderText(XSLFSheet sheet, Placeholder type) {
        XSLFTextShape ts = placeholder(sheet, EnumSet.of(type));
        if (ts == null) return null;
        String text = ts.getText().replace("‹#›", "").trim();
        return text.isEmpty() ? null : text;
    }

    private static Color backgroundColor(XSLFSlideLayout layout) {
        try {
            Color c = layout.getBackground() != null ? layout.getBackground().getFillColor() : null;
            if (c == null && layout.getSlideMaster().getBackground() != null) c = layout.getSlideMaster().getBackground().getFillColor();
            return c != null ? c : Color.WHITE;
        } catch (Exception e) {
            return Color.WHITE;
        }
    }

    // ── Scaling between page sizes ────────────────────────────────────────────────

    /**
     * Maps positions from the template's page to the deck's. With the same aspect ratio this is a plain
     * scale; otherwise shapes keep their size ratio and their distance to the nearest edges, and only
     * wide shapes (over 60% of the page) stretch with it.
     */
    record Placement(double srcW, double srcH, double dstW, double dstH) {
        Placement(Dimension src, Dimension dst) {
            this(src.getWidth(), src.getHeight(), dst.getWidth(), dst.getHeight());
        }

        double sx() { return dstW / srcW; }
        double sy() { return dstH / srcH; }
        double scale() { return Math.min(sx(), sy()); }

        Rectangle2D map(Rectangle2D a) {
            double s = scale();
            if (Math.abs(sx() - sy()) < 0.01) {
                return new Rectangle2D.Double(a.getX() * s, a.getY() * s, a.getWidth() * s, a.getHeight() * s);
            }
            double x, w, y, h;
            if (a.getWidth() > srcW * 0.6) {
                x = a.getX() * sx();
                w = a.getWidth() * sx();
            } else {
                w = a.getWidth() * s;
                x = a.getCenterX() < srcW / 2 ? a.getX() * sx() : dstW - (srcW - a.getMaxX()) * sx() - w;
            }
            if (a.getHeight() > srcH * 0.6) {
                y = a.getY() * sy();
                h = a.getHeight() * sy();
            } else {
                h = a.getHeight() * s;
                y = a.getCenterY() < srcH / 2 ? a.getY() * sy() : dstH - (srcH - a.getMaxY()) * sy() - h;
            }
            return new Rectangle2D.Double(x, y, w, h);
        }
    }
}
