package com.workshopper.service;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import static org.assertj.core.api.Assertions.assertThat;

public class TestPptxExportIntegration {

    /** Learning goal string used throughout the test — must appear in at least one slide. */
    private static final String TEST_LEARNING_GOAL = "My Awesome Presentation";

    @Test
    public void generateFile() throws Exception {
        String path = "src/main/resources/templates/workshopper-default.pptx";
        byte[] templateBytes = Files.readAllBytes(Paths.get(path));
        
        com.workshopper.usecase.AssemblePptxUseCase service = new com.workshopper.usecase.AssemblePptxUseCase();
        
        com.workshopper.dto.WorkshopSessionDto session = new com.workshopper.dto.WorkshopSessionDto(
            "id", TEST_LEARNING_GOAL, "user", "Goal",
            "1h", List.of(), List.of(), null
        );
        com.workshopper.dto.WorkshopInputDto meta = new com.workshopper.dto.WorkshopInputDto("Test Title",
            List.of(), 60, 10, "Target", "Level", "Type", "Materials", "Space", "Methods", List.of(), "Custom"
        );
        
        List<Map<String, Object>> slidesData = new ArrayList<>();
        Map<String, Object> map = new HashMap<>();
        map.put("title", "Content Slide 1");
        map.put("bullets", List.of("This is bullet 1", "This is bullet 2"));
        map.put("notes", "These are the notes");
        slidesData.add(map);
        
        byte[] result = service.execute(session, meta, slidesData, new java.io.ByteArrayInputStream(templateBytes));
        
        // Assert the generated structure
        try (XMLSlideShow generated = new XMLSlideShow(new java.io.ByteArrayInputStream(result))) {
            // 1. Must have at least one slide
            assertThat(generated.getSlides()).hasSizeGreaterThan(0);

            // 2. Collect all text across all slides for assertion
            StringBuilder allText = new StringBuilder();
            for (XSLFSlide slide : generated.getSlides()) {
                for (org.apache.poi.xslf.usermodel.XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape ts) {
                        allText.append(ts.getText()).append(" ");
                    }
                }
            }
            String combinedText = allText.toString();

            // 3. First slide must have at least one non-empty text shape (title placeholder was injected)
            XSLFSlide firstSlide = generated.getSlides().get(0);
            boolean firstSlideHasText = firstSlide.getShapes().stream()
                    .anyMatch(s -> s instanceof org.apache.poi.xslf.usermodel.XSLFTextShape ts
                            && ts.getText() != null && !ts.getText().isBlank());
            assertThat(firstSlideHasText)
                    .as("First slide should have at least one non-empty text shape (title was injected by AssemblePptxUseCase)")
                    .isTrue();

            // 4. At least one slide must contain the session title / learning goal used in the test
            assertThat(combinedText)
                    .as("At least one slide must contain the session title '%s' injected by AssemblePptxUseCase", TEST_LEARNING_GOAL)
                    .contains(TEST_LEARNING_GOAL);
        }
    }
}

