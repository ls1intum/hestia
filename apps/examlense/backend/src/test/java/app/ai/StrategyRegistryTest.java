package app.ai;

import org.junit.jupiter.api.Test;

import static app.ai.ParserStrategy.PdfMode.PDF_DIRECT;
import static app.ai.ParserStrategy.PdfMode.RASTERIZE;
import static app.ai.ProviderKind.ANTHROPIC;
import static app.ai.ProviderKind.GEMINI;
import static app.ai.ProviderKind.OPENAI;
import static app.ai.ProviderKind.OPENAI_COMPATIBLE;
import static org.assertj.core.api.Assertions.assertThat;

class StrategyRegistryTest {

    @Test
    void parserCatalogExposesOnlyActiveModelsInDisplayOrder() {
        assertThat(ParserStrategies.DEFAULT_ID).isEqualTo("gemini-2.5-flash");
        assertThat(ParserStrategies.all())
            .extracting(ParserStrategy::id)
            .containsExactly(
                "gemini-2.5-flash",
                "claude-opus-4-8",
                "gpt-5.5",
                "qwen3.6-35b-a3b",
                "mistral-large-3-675b-instruct-2512"
            );
    }

    @Test
    void parserStrategiesHaveExpectedNormalModesAndProviders() {
        ParserStrategy gemini = ParserStrategies.resolve("gemini-2.5-flash");
        ParserStrategy claude = ParserStrategies.resolve("claude-opus-4-8");
        ParserStrategy gpt = ParserStrategies.resolve("gpt-5.5");
        ParserStrategy qwen = ParserStrategies.resolve("qwen3.6-35b-a3b");
        ParserStrategy mistral = ParserStrategies.resolve("mistral-large-3-675b-instruct-2512");

        assertThat(gemini.providerKind()).isEqualTo(GEMINI);
        assertThat(gemini.pdfMode()).isEqualTo(PDF_DIRECT);

        assertThat(claude.providerKind()).isEqualTo(ANTHROPIC);
        assertThat(claude.pdfMode()).isEqualTo(PDF_DIRECT);

        assertThat(gpt.providerKind()).isEqualTo(OPENAI);
        assertThat(gpt.pdfMode()).isEqualTo(PDF_DIRECT);

        assertThat(qwen.providerKind()).isEqualTo(OPENAI_COMPATIBLE);
        assertThat(qwen.pdfMode()).isEqualTo(RASTERIZE);

        assertThat(mistral.providerKind()).isEqualTo(OPENAI_COMPATIBLE);
        assertThat(mistral.pdfMode()).isEqualTo(RASTERIZE);
    }

    @Test
    void legacyTextOnlyParserAliasResolvesToBaseMistral() {
        ParserStrategy strategy = ParserStrategies.resolve("mistral-large-3-text");

        assertThat(strategy.id()).isEqualTo("mistral-large-3-675b-instruct-2512");
        assertThat(strategy.providerModel()).isEqualTo("mistral-large-3-675b-instruct-2512");
        assertThat(strategy.pdfMode()).isEqualTo(RASTERIZE);
    }

    @Test
    void solverCatalogExposesActiveModelsInDisplayOrder() {
        assertThat(SolverStrategies.DEFAULT_ID).isEqualTo("gpt-5.5");
        assertThat(SolverStrategies.all())
            .extracting(SolverStrategy::id)
            .containsExactly(
                "gemini-2.5-flash",
                "gpt-5.5",
                "claude-opus-4-8",
                "mistral-large-3-675b-instruct-2512",
                "qwen3.6-35b-a3b"
            );
    }

    @Test
    void registersExpectedSolverProviders() {
        SolverStrategy gemini = SolverStrategies.resolve("gemini-2.5-flash");
        SolverStrategy gpt = SolverStrategies.resolve("gpt-5.5");
        SolverStrategy claude = SolverStrategies.resolve("claude-opus-4-8");
        SolverStrategy mistral = SolverStrategies.resolve("mistral-large-3-675b-instruct-2512");
        SolverStrategy qwen = SolverStrategies.resolve("qwen3.6-35b-a3b");

        assertThat(gemini.providerKind()).isEqualTo(GEMINI);
        assertThat(gemini.providerModel()).isEqualTo("gemini-2.5-flash");

        assertThat(gpt.providerKind()).isEqualTo(OPENAI);
        assertThat(gpt.providerModel()).isEqualTo("gpt-5.5");

        assertThat(claude.providerKind()).isEqualTo(ANTHROPIC);
        assertThat(claude.providerModel()).isEqualTo("claude-opus-4-8");

        assertThat(mistral.providerKind()).isEqualTo(OPENAI_COMPATIBLE);
        assertThat(mistral.providerModel()).isEqualTo("mistral-large-3-675b-instruct-2512");

        assertThat(qwen.providerKind()).isEqualTo(OPENAI_COMPATIBLE);
        assertThat(qwen.providerModel()).isEqualTo("qwen3.6-35b-a3b");
    }

    @Test
    void legacySolverIdsRemainResolvable() {
        assertThat(SolverStrategies.resolve("gemma-4-31b-it").id()).isEqualTo("gemma-4-31b-it");
        assertThat(SolverStrategies.resolve("qwen3.5-397b-a17b").id()).isEqualTo("qwen3.5-397b-a17b");
    }
}
