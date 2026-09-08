package com.workshopper;
import org.junit.jupiter.api.Test;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import org.apache.poi.xslf.usermodel.*;

public class ExtractStylesTest {
    @Test
    public void testStyles() throws Exception {
        try (FileInputStream fis = new FileInputStream("src/main/resources/templates/workshopper-default.pptx");
             XMLSlideShow ppt = new XMLSlideShow(fis);
             PrintWriter writer = new PrintWriter(new FileOutputStream("/tmp/template_styles.txt"))) {
            
            XSLFSlideMaster master = ppt.getSlideMasters().get(0);
            for (XSLFSlideLayout layout : master.getSlideLayouts()) {
                writer.println("Layout: " + layout.getName());
                for (XSLFTextShape shape : layout.getPlaceholders()) {
                    writer.println("  Placeholder: " + shape.getTextType());
                    for (XSLFTextParagraph p : shape.getTextParagraphs()) {
                        for (XSLFTextRun r : p.getTextRuns()) {
                            writer.println("    Font: " + r.getFontFamily() + ", Size: " + r.getFontSize() + ", Bold: " + r.isBold() + ", Italic: " + r.isItalic());
                        }
                    }
                }
            }
        }
    }
}
