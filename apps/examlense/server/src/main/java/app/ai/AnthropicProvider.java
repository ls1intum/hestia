package app.ai;

/** Calls Anthropic's native Messages API. */
public final class AnthropicProvider implements AiProvider {
    private final String apiKey;
    private final String model;
    private final ProviderHttpCaller caller;

    public AnthropicProvider(String apiKey, String baseUrl, String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.caller = new ProviderHttpCaller("Anthropic", baseUrl);
    }

    @Override public String name() { return "anthropic"; }

    @Override
    public ChatResponse chat(ChatRequest req) {
        return caller.call(AnthropicClient.ADAPTER, apiKey, model, name(), req);
    }
}
