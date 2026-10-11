package com.workshopper;
import org.junit.jupiter.api.Test;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.sl.usermodel.Placeholder;

public class NotesTest {
    @Test
    public void testNotes() throws Exception {
        try (FileInputStream fis = new FileInputStream("src/main/resources/templates/workshopper-default.pptx");
             XMLSlideShow ppt = new XMLSlideShow(fis)) {
            
            for (int i = ppt.getSlides().size() - 1; i >= 0; i--) {
                ppt.removeSlide(i);
            }
            
            XSLFSlide slide = ppt.createSlide(ppt.getSlideMasters().get(0).getSlideLayouts()[0]);
            
            if (ppt.getNotesMaster() == null) ppt.createNotesMaster();
            XSLFNotes notesSlide = ppt.getNotesSlide(slide);
            
            if (notesSlide != null) {
                for (XSLFTextShape shape : notesSlide.getPlaceholders()) {
                    if (shape.getTextType() == Placeholder.BODY) {
                        shape.setText("THIS IS A VERY SECRET NOTE 123456789");
                    }
                }
            }
            try (FileOutputStream fos = new FileOutputStream("/tmp/test_out.pptx")) {
                ppt.write(fos);
            }
        }
    }
}
