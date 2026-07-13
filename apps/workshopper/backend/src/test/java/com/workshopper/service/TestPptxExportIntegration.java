package com.workshopper.service;

import org.junit.jupiter.api.Test;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import org.apache.poi.xslf.usermodel.XMLSlideShow;

public class TestPptxExportIntegration {
    @org.junit.jupiter.api.Disabled("Local test only")
    @Test
    public void generateFile() throws Exception {
        String path = "local/path/to/template.pptx";
        byte[] templateBytes = Files.readAllBytes(Paths.get(path));
        
        PptxExportService service = new PptxExportService(null);
        
        com.workshopper.dto.WorkshopSessionDto session = new com.workshopper.dto.WorkshopSessionDto(
            "id", "user", "My Awesome Presentation", "Goal",
            "1h", List.of(), List.of(), null
        );
        com.workshopper.dto.WorkshopInputDto meta = new com.workshopper.dto.WorkshopInputDto(
            List.of(), 60, 10, "Target", "Level", "Type", "Materials", "Space", "Methods", List.of(), "Custom"
        );
        
        List<Map<String, Object>> slidesData = new ArrayList<>();
        Map<String, Object> map = new HashMap<>();
        map.put("title", "Content Slide 1");
        map.put("bullets", List.of("This is bullet 1", "This is bullet 2"));
        map.put("notes", "These are the notes");
        slidesData.add(map);
        
        byte[] result = service.assembleFromSlides(session, meta, slidesData, new java.io.ByteArrayInputStream(templateBytes));
        
        try (FileOutputStream fos = new FileOutputStream("local/path/to/out.pptx")) {
            fos.write(result);
        }
        
        // Also let's inspect the generated file
        try (XMLSlideShow generated = new XMLSlideShow(new FileInputStream("local/path/to/out.pptx"))) {
            System.out.println("Generated slides count: " + generated.getSlides().size());
            for (int i = 0; i < generated.getSlides().size(); i++) {
                System.out.println("Slide " + i + " layout name: " + generated.getSlides().get(i).getSlideLayout().getName());
                org.apache.poi.xslf.usermodel.XSLFTextShape[] shapes = generated.getSlides().get(i).getPlaceholders();
                for (int j = 0; j < shapes.length; j++) {
                    System.out.println("  Placeholder " + j + " text: " + shapes[j].getText());
                    for (int k = 0; k < shapes[j].getTextParagraphs().size(); k++) {
                        for (int m = 0; m < shapes[j].getTextParagraphs().get(k).getTextRuns().size(); m++) {
                            System.out.println("    Run " + m + " font size: " + shapes[j].getTextParagraphs().get(k).getTextRuns().get(m).getFontSize());
                        }
                    }
                }
            }
        }
    }
}
