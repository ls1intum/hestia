package app.ai;

/**
 * Native Google Gemini provider (generateContent API). Sends the PDF inline so
 * Gemini parses the document natively (PDF_DIRECT). Thin wrapper — the request /
 * response shapes live in {@link GeminiClient}, mirroring {@link OpenAiCompatibleProvider}.
 */
public final class GeminiProvider implements AiProvider {
    private final String apiKey;
    private final String model;
    private final ProviderHttpCaller caller;

    public GeminiProvider(String apiKey, String baseUrl, String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.caller = new ProviderHttpCaller("Gemini", baseUrl);
    }

    @Override public String name() { return "gemini"; }

    @Override
    public ChatResponse chat(ChatRequest req) {
        return caller.call(GeminiClient.ADAPTER, apiKey, model, name(), req);
    }
}
