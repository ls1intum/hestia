package com.workshopper.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileNamesTest {

    @Test
    void keepsTheTitleAsShown() {
        assertEquals("Intro to ML - Slides.pptx", FileNames.slides("Intro to ML"));
        assertEquals("Grundlagen der Klassifikation - Timetable.pdf", FileNames.timetable("Grundlagen der Klassifikation"));
        assertEquals("Machine Learning WS26.zip", FileNames.course("Machine Learning WS26"));
        assertEquals("Données & Co. - Slides.pptx", FileNames.slides("Données & Co."));
    }

    @Test
    void removesCharactersFileSystemsForbid() {
        assertEquals("Part 1 Basics - Slides.pptx", FileNames.slides("Part 1: Basics"));
        assertEquals("a b c d", FileNames.safe("a/b\\c\"d", "x"));
        assertEquals("a b", FileNames.safe("a\r\nb", "x"));
    }

    @Test
    void usesTheFallbackForEmptyTitles() {
        assertEquals("Workshop - Slides.pptx", FileNames.slides(null));
        assertEquals("Course.zip", FileNames.course("  ...  "));
        assertEquals("hidden - Slides.pptx", FileNames.slides(".hidden"));
    }

    @Test
    void limitsTheLength() {
        assertEquals(100, FileNames.safe("x".repeat(300), "y").length());
    }
}
