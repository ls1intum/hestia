package com.workshopper.service;

import org.junit.jupiter.api.Test;
import org.apache.poi.xslf.usermodel.*;
import java.io.FileInputStream;

public class TestPptxLayouts {
    @org.junit.jupiter.api.Disabled("Local test only")
    @Test
    public void printLayouts() throws Exception {
        String path = "local/path/to/template.pptx";
        try (XMLSlideShow ppt = new XMLSlideShow(new FileInputStream(path))) {
            for (int m = 0; m < ppt.getSlideMasters().size(); m++) {
                XSLFSlideMaster master = ppt.getSlideMasters().get(m);
                System.out.println("Master " + m + " layouts:");
                for (int l = 0; l < master.getSlideLayouts().length; l++) {
                    XSLFSlideLayout layout = master.getSlideLayouts()[l];
                    System.out.println("  Layout " + l + ": name='" + layout.getName() + "', type=" + layout.getType() + ", placeholders=" + layout.getPlaceholders().length);
                    if (layout.getName().equals("1_Inhalt") || layout.getName().equals("4_Inhalt + Text")) {
                        for (int p = 0; p < layout.getPlaceholders().length; p++) {
                            XSLFTextShape sh = layout.getPlaceholders()[p];
                            System.out.println("    Placeholder " + p + ": type=" + sh.getTextType() + ", name=" + sh.getShapeName());
                        }
                    }
                }
            }
        }
    }
}
