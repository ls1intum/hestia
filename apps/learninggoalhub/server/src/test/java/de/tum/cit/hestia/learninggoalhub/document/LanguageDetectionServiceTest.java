package de.tum.cit.hestia.learninggoalhub.document;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LanguageDetectionServiceTest {

    private final LanguageDetectionService service = new LanguageDetectionService();

    @Test
    void detectsGermanText() {
        assertThat(service.detect("""
                Dies ist ein längerer deutscher Text über Lernziele und die Inhalte einer Vorlesung.
                Studierende sollen die wichtigsten Konzepte verstehen und anwenden können.
                """)).isEqualTo("de");
    }

    @Test
    void detectsEnglishText() {
        assertThat(service.detect("""
                This is a longer English text about learning goals and the contents of a lecture.
                Students should understand the main concepts and be able to apply them.
                """)).isEqualTo("en");
    }

    @Test
    void returnsNullForBlankOrUncertainText() {
        assertThat(service.detect(null)).isNull();
        assertThat(service.detect(" \n\t ")).isNull();
        assertThat(service.detect("asdf qwer zxcv 12345 !!!")).isNull();
    }
}
