package app.ai;

import java.util.List;
import java.util.Optional;

import static app.ai.ProviderKind.ANTHROPIC;
import static app.ai.ProviderKind.GEMINI;
import static app.ai.ProviderKind.OPENAI;
import static app.ai.ProviderKind.OPENAI_COMPATIBLE;

public final class SolverStrategies {

    public static final String DEFAULT_ID = "gpt-5.5";

    private static final StrategyRegistry<SolverStrategy> REGISTRY =
        new StrategyRegistry<>(SolverStrategy::id, DEFAULT_ID);

    static {
        REGISTRY.register(new SolverStrategy(
            "gemini-2.5-flash",
            "Gemini 2.5 Flash (Google)",
            "Google Gemini model for fast exam solving.",
            "gemini-2.5-flash",
            GEMINI
        ));
        REGISTRY.register(new SolverStrategy(
            "gpt-5.5",
            "GPT-5.5 (OpenAI)",
            "OpenAI frontier model for complex reasoning and tool-heavy work.",
            "gpt-5.5",
            OPENAI
        ));
        REGISTRY.register(new SolverStrategy(
            "claude-opus-4-8",
            "Claude Opus 4.8 (Anthropic)",
            "Anthropic flagship model for complex reasoning and long-context work.",
            "claude-opus-4-8",
            ANTHROPIC
        ));
        REGISTRY.register(new SolverStrategy(
            "mistral-large-3-675b-instruct-2512",
            "Mistral Large 3 675B (GWDG)",
            "GWDG-hosted large reasoning model.",
            "mistral-large-3-675b-instruct-2512",
            OPENAI_COMPATIBLE
        ));
        REGISTRY.register(new SolverStrategy(
            "qwen3.6-35b-a3b",
            "Qwen 3.6 35B A3B (GWDG)",
            "GWDG-hosted Qwen 3.6 mixture-of-experts model.",
            "qwen3.6-35b-a3b",
            OPENAI_COMPATIBLE
        ));
        // Retired models: exams created before the catalog change still
        // reference these ids, so they stay resolvable but hidden.
        REGISTRY.legacy(new SolverStrategy(
            "gemma-4-31b-it",
            "Gemma 4 31B Instruct (GWDG)",
            "GWDG-hosted instruction-tuned model.",
            "gemma-4-31b-it",
            OPENAI_COMPATIBLE
        ));
        REGISTRY.legacy(new SolverStrategy(
            "qwen3.5-397b-a17b",
            "Qwen 3.5 397B A17B (GWDG)",
            "GWDG-hosted Qwen 3.5 large mixture-of-experts model.",
            "qwen3.5-397b-a17b",
            OPENAI_COMPATIBLE
        ));
    }

    private SolverStrategies() {}

    public static List<SolverStrategy> all() { return REGISTRY.all(); }

    public static SolverStrategy resolve(String id) { return REGISTRY.resolve(id); }

    public static Optional<SolverStrategy> find(String id) { return REGISTRY.find(id); }
}
