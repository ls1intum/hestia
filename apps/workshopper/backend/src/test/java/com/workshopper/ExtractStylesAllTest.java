package com.workshopper;
import org.junit.jupiter.api.Test;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.sl.usermodel.PaintStyle;

public class ExtractStylesAllTest {
    @Test
    public void testStyles() throws Exception {
        try (FileInputStream fis = new FileInputStream("src/main/resources/templates/workshopper-default.pptx");
             XMLSlideShow ppt = new XMLSlideShow(fis);
             PrintWriter writer = new PrintWriter(new FileOutputStream("/tmp/all_styles.txt"))) {
            
            XSLFSlideMaster master = ppt.getSlideMasters().get(0);
            for (XSLFSlideLayout layout : master.getSlideLayouts()) {
                writer.println("Layout: " + layout.getName());
                for (XSLFTextShape shape : layout.getPlaceholders()) {
                    writer.println("  Placeholder: " + shape.getTextType() + " / Name: " + shape.getShapeName());
                    for (XSLFTextParagraph p : shape.getTextParagraphs()) {
                        for (XSLFTextRun r : p.getTextRuns()) {
                            writer.println("    Text: " + r.getRawText());
                            writer.println("    Font: " + r.getFontFamily() + ", Size: " + r.getFontSize() + ", Bold: " + r.isBold() + ", Italic: " + r.isItalic());
                        }
                    }
                }
            }
        }
    }
}
