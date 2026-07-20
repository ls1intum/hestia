package app.parse;

import app.ai.ParserStrategies;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static app.ai.ParserStrategy.PdfMode.PDF_DIRECT;
import static app.ai.ParserStrategy.PdfMode.RASTERIZE;
import static app.ai.ParserStrategy.PdfMode.TEXT_ONLY;
import static org.assertj.core.api.Assertions.assertThat;

class ParseFastModeTest {

    @Test
    void fastModeMakesEveryActiveParserUseTextOnlyInput() {
        assertThat(ParserStrategies.all())
            .allSatisfy(strategy ->
                assertThat(ParseExamService.effectivePdfMode(strategy, true)).isEqualTo(TEXT_ONLY)
            );
    }

    @Test
    void normalModeKeepsStrategyPdfModes() {
        assertThat(ParseExamService.effectivePdfMode(ParserStrategies.resolve("gemini-2.5-flash"), false))
            .isEqualTo(PDF_DIRECT);
        assertThat(ParseExamService.effectivePdfMode(ParserStrategies.resolve("claude-opus-4-8"), false))
            .isEqualTo(PDF_DIRECT);
        assertThat(ParseExamService.effectivePdfMode(ParserStrategies.resolve("gpt-5.5"), false))
            .isEqualTo(PDF_DIRECT);
        assertThat(ParseExamService.effectivePdfMode(ParserStrategies.resolve("qwen3.6-35b-a3b"), false))
            .isEqualTo(RASTERIZE);
        assertThat(ParseExamService.effectivePdfMode(
            ParserStrategies.resolve("mistral-large-3-675b-instruct-2512"), false))
            .isEqualTo(RASTERIZE);
    }

    @Test
    void defaultAndFallbackParsersShareTheSameEffectiveModeSoInputIsReused() {
        var primary = ParserStrategies.resolve(ParserStrategies.DEFAULT_ID);
        var fallback = ParserStrategies.resolve(ParserStrategies.FALLBACK_ID);

        // Fast mode → both TEXT_ONLY; normal mode → both PDF_DIRECT. Same mode either
        // way means the fallback reuses the already-built input (no rebuild).
        assertThat(ParseExamService.effectivePdfMode(fallback, false))
            .isEqualTo(ParseExamService.effectivePdfMode(primary, false));
        assertThat(ParseExamService.effectivePdfMode(fallback, true))
            .isEqualTo(ParseExamService.effectivePdfMode(primary, true));
    }

    @Test
    void parseRequestFastModeDefaultsToFalseWhenOmitted() throws Exception {
        ParseExamController.ParseExamRequest req = new ObjectMapper().readValue("""
            {
              "exam_id": "exam-id",
              "storage_path": "exam-pdfs/file.pdf",
              "parser_model": "gemini-2.5-flash"
            }
            """, ParseExamController.ParseExamRequest.class);

        assertThat(req.fast_mode()).isNull();
        assertThat(Boolean.TRUE.equals(req.fast_mode())).isFalse();
    }

    @Test
    void parseRequestAcceptsExplicitFastMode() throws Exception {
        ParseExamController.ParseExamRequest req = new ObjectMapper().readValue("""
            {
              "exam_id": "exam-id",
              "storage_path": "exam-pdfs/file.pdf",
              "parser_model": "gemini-2.5-flash",
              "fast_mode": true
            }
            """, ParseExamController.ParseExamRequest.class);

        assertThat(req.fast_mode()).isTrue();
    }
}
