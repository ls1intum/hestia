package de.tum.cit.hestia.ai.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.hestia.ai.ConsentScope;
import org.junit.jupiter.api.Test;

/** The consent notice users see carries no reviewer notes from the notice file. */
class ConsentNoticeTextTest {

    @Test
    void removesHtmlCommentsWithTheirLine() {
        String markdown = "# Title\n\n<!-- PRÜFEN: check with the data protection office. -->\n\nFirst paragraph.\n";

        assertThat(ConsentService.withoutComments(markdown)).isEqualTo("# Title\n\nFirst paragraph.\n");
    }

    @Test
    void removesCommentsSpanningLinesAndKeepsTheRest() {
        String markdown = "Before <!-- a\nnote --> after\n- item\n";

        assertThat(ConsentService.withoutComments(markdown)).isEqualTo("Before after\n- item\n");
    }

    @Test
    void theShippedNoticeHasNoComment() {
        ConsentService service = new ConsentService(mock(ConsentEventRepository.class), mock(Callers.class));

        String text = service.notice(ConsentScope.EXTERNAL_MODELS).text();

        assertThat(text).doesNotContain("<!--").doesNotContain("-->").startsWith("# Einwilligung");
    }
}
