package de.tum.cit.hestia.learninggoalhub.document;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DocumentTitleServiceTest {

    @Test
    void returnsNullForNullOrBlank() {
        assertThat(DocumentTitleService.clean(null)).isNull();
        assertThat(DocumentTitleService.clean("   ")).isNull();
        assertThat(DocumentTitleService.clean("\n\t")).isNull();
    }

    @Test
    void stripsSurroundingQuotesAndWhitespace() {
        assertThat(DocumentTitleService.clean("  \"What is Machine Learning?\" ")).isEqualTo("What is Machine Learning?");
        assertThat(DocumentTitleService.clean("`Linear Regression`")).isEqualTo("Linear Regression");
    }

    @Test
    void collapsesMultiLineReplyIntoOneTitle() {
        // The model often answers a section label and a topic on two lines.
        assertThat(DocumentTitleService.clean("ML-Basics\nIn a Nutshell")).isEqualTo("ML-Basics In a Nutshell");
    }

    @Test
    void capsOverlongReplies() {
        String result = DocumentTitleService.clean("x".repeat(300));
        assertThat(result).hasSize(200);
    }
}
