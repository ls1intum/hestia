package com.workshopper.usecase;

import com.workshopper.dto.WorkshopInputDto;
import com.workshopper.dto.WorkshopSessionDto;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgendaSlideLayoutTest {

    private static final Path TEMPLATE = Path.of("src/main/resources/templates/workshopper-default.pptx");

    private static final List<String> GOALS = List.of(
            "Understand decision regions, decision boundaries, and thresholding for classification, including how the threshold trades off precision against recall.",
            "Understand classification task types.",
            "Understand multiclass classification using the Iris dataset.",
            "Apply logistic regression to a binary classification problem.",
            "Compare linear classifiers with tree-based models.",
            "Evaluate a classifier with a confusion matrix.");

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 4, 6})
    void agendaHasOneRowPerGoalAndTextFitsItsBox(int goalCount) throws Exception {
        List<String> goals = GOALS.subList(0, goalCount);
        XSLFSlide agenda = renderAgenda(goals);

        List<XSLFTextShape> goalShapes = new ArrayList<>();
        List<String> numbers = new ArrayList<>();
        for (XSLFShape s : agenda.getShapes()) {
            if (!(s instanceof XSLFTextShape ts)) continue;
            String text = ts.getText().trim();
            if (goals.contains(text)) goalShapes.add(ts);
            if (text.matches("\\d+")) numbers.add(text);
        }

        assertThat(goalShapes).hasSize(goalCount);
        assertThat(numbers).containsExactlyInAnyOrderElementsOf(
                java.util.stream.IntStream.rangeClosed(1, goalCount).mapToObj(String::valueOf).toList());
        assertThat(agenda.getShapes()).noneMatch(s -> s instanceof XSLFTextShape ts && ts.getText().contains("[Learning Goal"));

        for (XSLFTextShape ts : goalShapes) {
            var box = ts.getAnchor();
            double size = ts.getTextParagraphs().get(0).getTextRuns().get(0).getFontSize();
            assertThat(PptxTextFit.fits(List.of(ts.getText()), size, box.getWidth(), box.getHeight()))
                    .as("goal text fits its box at %.1fpt: %s", size, ts.getText())
                    .isTrue();
            assertThat(box.getMaxY()).isLessThanOrEqualTo(520);
        }
    }

    private XSLFSlide renderAgenda(List<String> goals) throws Exception {
        byte[] template = Files.readAllBytes(TEMPLATE);
        var session = new WorkshopSessionDto("id", "Linear Classifiers", "bg", List.of(), null);
        var meta = new WorkshopInputDto("Linear Classifiers", 90, 20, "workshop", null, "bg", List.of());
        // The assembler edits slide data in place, so it needs mutable maps and lists
        List<Map<String, Object>> slides = List.of(new java.util.HashMap<>(Map.of(
                "layout", "agenda", "group", "agenda", "title", "What We'll Cover Today", "bullets", new ArrayList<>(goals))));

        byte[] pptx = new AssemblePptxUseCase().execute(session, meta, slides, new ByteArrayInputStream(template), false);

        String out = System.getProperty("agendaSampleDir");
        if (out != null) Files.write(Path.of(out, "agenda-" + goals.size() + "-goals.pptx"), pptx);

        XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(pptx));
        return show.getSlides().stream()
                .filter(s -> s.getShapes().stream().anyMatch(sh -> sh instanceof XSLFTextShape ts && ts.getText().contains("AGENDA")))
                .findFirst().orElseThrow();
    }
}
