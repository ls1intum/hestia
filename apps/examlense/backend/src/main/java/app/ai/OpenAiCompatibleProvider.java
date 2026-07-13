package app.ai;

/** Calls any OpenAI-compatible endpoint (GWDG, OpenAI, vLLM, ...). */
public final class OpenAiCompatibleProvider implements AiProvider {
    private final String apiKey;
    private final String model;
    private final ProviderHttpCaller caller;

    public OpenAiCompatibleProvider(String apiKey, String baseUrl, String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.caller = new ProviderHttpCaller("AI provider", baseUrl);
    }

    @Override public String name() { return "openai-compatible"; }

    @Override
    public ChatResponse chat(ChatRequest req) {
        return caller.call(OpenAiCompatibleClient.ADAPTER, apiKey, model, name(), req);
    }
}
