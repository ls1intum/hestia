package com.workshopper.usecase;

import com.workshopper.dto.WorkshopInputDto;
import com.workshopper.dto.WorkshopSessionDto;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.*;
import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Uses a synthetic "university" template built in code, so no real template files are needed. */
class TemplateBrandingTest {

    private static final String FOOTER = "Dr. Jane Example | Example University";

    @Test
    void customTemplateKeepsDefaultDesignAndAddsTitleLogoAndFooter() throws Exception {
        byte[] template = syntheticTemplate();
        var session = new WorkshopSessionDto("id", "Linear Classifiers", "bg", List.of(), null);
        var meta = new WorkshopInputDto("Linear Classifiers", 90, 20, "workshop", null, "bg", List.of());
        List<Map<String, Object>> slides = List.of(new HashMap<>(Map.of(
                "layout", "agenda", "group", "agenda", "title", "What We'll Cover Today",
                "bullets", new ArrayList<>(List.of("Understand linear classifiers.")))));

        byte[] pptx = new AssemblePptxUseCase().execute(session, meta, slides, new ByteArrayInputStream(template), true);

        String out = System.getProperty("agendaSampleDir");
        if (out != null) Files.write(Path.of(out, "branded-synthetic.pptx"), pptx);

        try (XMLSlideShow deck = new XMLSlideShow(new ByteArrayInputStream(pptx))) {
            // The default design's page size and slides are kept
            assertThat(deck.getPageSize()).isEqualTo(new Dimension(960, 540));
            assertThat(deck.getSlides()).hasSize(2);

            XSLFSlide title = deck.getSlides().get(0);
            assertThat(texts(title)).contains("Linear Classifiers");
            assertThat(pictures(title)).hasSize(1);

            XSLFSlide agenda = deck.getSlides().get(1);
            assertThat(texts(agenda)).contains("Understand linear classifiers.", FOOTER, "2");
            assertThat(texts(agenda).stream().anyMatch(t -> t.contains("AGENDA"))).isTrue();
            List<XSLFPictureShape> logos = pictures(agenda);
            assertThat(logos).hasSize(1);
            // Logo scaled from 720x405 to 960x540, still in the top-right corner
            Rectangle2D logo = logos.get(0).getAnchor();
            assertThat(logo.getX()).isCloseTo(640 * 960.0 / 720, org.assertj.core.data.Offset.offset(1.0));
            assertThat(logo.getWidth()).isCloseTo(60 * 960.0 / 720, org.assertj.core.data.Offset.offset(1.0));
        }
    }

    @Test
    void placementKeepsLogosAtTheirEdgeWhenAspectRatioDiffers() {
        // 4:3 template onto a 16:9 deck: a top-right logo stays top-right and is not stretched
        var place = new TemplateBranding.Placement(720, 540, 960, 540);
        Rectangle2D logo = place.map(new Rectangle2D.Double(640, 20, 60, 30));
        assertThat(logo.getMaxX()).isCloseTo(960 - 20 * 960.0 / 720, org.assertj.core.data.Offset.offset(0.5));
        assertThat(logo.getWidth() / logo.getHeight()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.01));
    }

    /** A 720x405 template: logo picture on the master, a footer on the master, a title slide and a content slide. */
    private static byte[] syntheticTemplate() throws Exception {
        try (XMLSlideShow t = new XMLSlideShow()) {
            t.setPageSize(new Dimension(720, 405));
            XSLFSlideMaster master = t.getSlideMasters().get(0);

            BufferedImage img = new BufferedImage(60, 30, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", png);
            XSLFPictureShape logo = master.createPicture(t.addPicture(png.toByteArray(), PictureData.PictureType.PNG));
            logo.setAnchor(new Rectangle2D.Double(640, 20, 60, 30));

            for (XSLFShape s : master.getShapes()) {
                if (s instanceof XSLFTextShape ts && ts.getPlaceholder() == Placeholder.FOOTER) {
                    ts.setText(FOOTER);
                    ts.setAnchor(new Rectangle2D.Double(20, 380, 500, 20));
                }
            }

            XSLFSlide titleSlide = t.createSlide(master.getLayout(org.apache.poi.xslf.usermodel.SlideLayout.TITLE));
            titleSlide.getPlaceholder(0).setText("Presentation title");
            t.createSlide(master.getLayout(org.apache.poi.xslf.usermodel.SlideLayout.TITLE_AND_CONTENT));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            t.write(out);
            return out.toByteArray();
        }
    }

    private static List<String> texts(XSLFSlide slide) {
        List<String> texts = new ArrayList<>();
        for (XSLFShape s : slide.getShapes()) {
            if (s instanceof XSLFTextShape ts && !ts.getText().isBlank()) texts.add(ts.getText().trim());
        }
        return texts;
    }

    private static List<XSLFPictureShape> pictures(XSLFSlide slide) {
        List<XSLFPictureShape> pics = new ArrayList<>();
        for (XSLFShape s : slide.getShapes()) {
            if (s instanceof XSLFPictureShape p) pics.add(p);
        }
        return pics;
    }
}
