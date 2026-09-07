package app.ai;

import java.util.List;
import java.util.Optional;

import static app.ai.ParserStrategy.PdfMode.PDF_DIRECT;
import static app.ai.ParserStrategy.PdfMode.RASTERIZE;
import static app.ai.ProviderKind.ANTHROPIC;
import static app.ai.ProviderKind.GEMINI;
import static app.ai.ProviderKind.OPENAI;
import static app.ai.ProviderKind.RETIRED;

public final class ParserStrategies {

    public static final String DEFAULT_ID = "gemini-3.5-flash";
    /** Used when the default parser fails transiently (busy/unreachable/5xx). A different provider than the default on purpose — a Gemini outage takes both Gemini variants down. */
    public static final String FALLBACK_ID = "gpt-5.5";

    private static final StrategyRegistry<ParserStrategy> REGISTRY =
        new StrategyRegistry<>(ParserStrategy::id, DEFAULT_ID);

    static {
        REGISTRY.register(new ParserStrategy(
            "gemini-3.5-flash",
            "Gemini 3.5 Flash (Google)",
            "Google Gemini reads the PDF directly (native document parsing, no rasterization).",
            "gemini-3.5-flash",
            PDF_DIRECT,
            GEMINI
        ));
        REGISTRY.register(new ParserStrategy(
            "claude-opus-4-8",
            "Claude Opus 4.8 (Anthropic)",
            "Anthropic flagship model; reads the PDF directly with native document input.",
            "claude-opus-4-8",
            PDF_DIRECT,
            ANTHROPIC
        ));
        REGISTRY.register(new ParserStrategy(
            "gpt-5.5",
            "GPT-5.5 (OpenAI)",
            "OpenAI frontier model; reads the PDF directly with native file input.",
            "gpt-5.5",
            PDF_DIRECT,
            OPENAI
        ));
        // Retired models: exams created before the catalog change still
        // reference these ids, so they stay resolvable but hidden. The GWDG
        // entries are tombstones — the OpenAI-compatible transport they used is
        // gone, so resolving one yields a label but parsing with it is refused.
        REGISTRY.legacy(new ParserStrategy(
            "qwen3.6-35b-a3b",
            "Qwen 3.6 35B A3B (GWDG)",
            "GWDG-hosted vision model; PDF pages rasterized to images.",
            "qwen3.6-35b-a3b",
            RASTERIZE,
            RETIRED
        ));
        REGISTRY.legacy(new ParserStrategy(
            "gemini-2.5-flash",
            "Gemini 2.5 Flash (Google)",
            "Google Gemini reads the PDF directly (native document parsing, no rasterization).",
            "gemini-2.5-flash",
            PDF_DIRECT,
            GEMINI
        ));
        ParserStrategy mistral = new ParserStrategy(
            "mistral-large-3-675b-instruct-2512",
            "Mistral Large 3 675B (GWDG)",
            "GWDG-hosted vision model; PDF pages rasterized to images.",
            "mistral-large-3-675b-instruct-2512",
            RASTERIZE,
            RETIRED
        );
        REGISTRY.legacy(mistral);
        REGISTRY.alias("mistral-large-3-text", mistral);
    }

    private ParserStrategies() {}

    public static List<ParserStrategy> all() { return REGISTRY.all(); }

    public static ParserStrategy resolve(String id) { return REGISTRY.resolve(id); }

    public static Optional<ParserStrategy> find(String id) { return REGISTRY.find(id); }
}
