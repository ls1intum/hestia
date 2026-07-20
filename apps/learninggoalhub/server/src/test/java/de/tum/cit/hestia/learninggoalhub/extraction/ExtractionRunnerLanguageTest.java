package de.tum.cit.hestia.learninggoalhub.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.document.Document;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExtractionRunnerLanguageTest {

    @Test
    void resolvesCourseOverrideBeforeDocumentDominantAndFallback() {
        Course course = new Course("Course");
        course.setOutputLanguage("de");

        assertThat(ExtractionRunner.resolveLanguage(course, "en", "fr")).isEqualTo("de");
    }

    @Test
    void resolvesDocumentLanguageBeforeDominantAndFallback() {
        Course course = new Course("Course");

        assertThat(ExtractionRunner.resolveLanguage(course, "de", "en")).isEqualTo("de");
    }

    @Test
    void resolvesDominantLanguageBeforeEnglishFallback() {
        Course course = new Course("Course");

        assertThat(ExtractionRunner.resolveLanguage(course, null, "de")).isEqualTo("de");
        assertThat(ExtractionRunner.resolveLanguage(course, null, null)).isEqualTo("en");
    }

    @Test
    void weightsDominantLanguageByRawTextLength() {
        Course course = new Course("Course");
        Document shortGerman = new Document(course, "de.pdf", "application/pdf", "kurz");
        shortGerman.setLanguage("de");
        Document longEnglish = new Document(course, "en.pdf", "application/pdf", "english ".repeat(10));
        longEnglish.setLanguage("en");

        assertThat(ExtractionRunner.dominantLanguage(List.of(shortGerman, longEnglish))).isEqualTo("en");
    }
}
