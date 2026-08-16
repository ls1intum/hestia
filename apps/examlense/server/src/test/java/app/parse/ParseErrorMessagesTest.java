package app.parse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ParseErrorMessagesTest {

    @Test
    void authAndMissingKeyStatusesMapToMisconfigured() {
        assertThat(ParseErrorMessages.forProviderStatus(401)).isEqualTo(ParseErrorMessages.AI_MISCONFIGURED);
        assertThat(ParseErrorMessages.forProviderStatus(403)).isEqualTo(ParseErrorMessages.AI_MISCONFIGURED);
        assertThat(ParseErrorMessages.forProviderStatus(500)).isEqualTo(ParseErrorMessages.AI_MISCONFIGURED);
    }

    @Test
    void transportAndTimeoutStatusesMapToUnreachable() {
        assertThat(ParseErrorMessages.forProviderStatus(0)).isEqualTo(ParseErrorMessages.AI_UNREACHABLE);
        assertThat(ParseErrorMessages.forProviderStatus(408)).isEqualTo(ParseErrorMessages.AI_UNREACHABLE);
        assertThat(ParseErrorMessages.forProviderStatus(425)).isEqualTo(ParseErrorMessages.AI_UNREACHABLE);
    }

    @Test
    void upstreamServerErrorsMapToUnavailable() {
        assertThat(ParseErrorMessages.forProviderStatus(502)).isEqualTo(ParseErrorMessages.AI_UNAVAILABLE);
        assertThat(ParseErrorMessages.forProviderStatus(503)).isEqualTo(ParseErrorMessages.AI_UNAVAILABLE);
        assertThat(ParseErrorMessages.forProviderStatus(504)).isEqualTo(ParseErrorMessages.AI_UNAVAILABLE);
    }

    @Test
    void otherStatusesFallBackToUnavailable() {
        assertThat(ParseErrorMessages.forProviderStatus(400)).isEqualTo(ParseErrorMessages.AI_UNAVAILABLE);
        assertThat(ParseErrorMessages.forProviderStatus(404)).isEqualTo(ParseErrorMessages.AI_UNAVAILABLE);
    }

    @Test
    void tooManyPagesIncludesCounts() {
        assertThat(ParseErrorMessages.tooManyPages(120, 100))
            .contains("120")
            .contains("100");
    }
}
