package com.workshopper;
import org.junit.jupiter.api.Test;
import java.io.FileInputStream;
import org.apache.poi.xslf.usermodel.*;

public class ExtractTextTest {
    @Test
    public void testText() throws Exception {
        try (FileInputStream fis = new FileInputStream("src/main/resources/templates/workshopper-default.pptx");
             XMLSlideShow ppt = new XMLSlideShow(fis)) {
            
            int i = 1;
            for (XSLFSlide slide : ppt.getSlides()) {
                System.out.println("--- Slide " + i++ + " ---");
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape) {
                        System.out.println(((XSLFTextShape) shape).getText());
                    }
                }
            }
        }
    }
}
