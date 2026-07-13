package app.ai;

/** Calls OpenAI's native Responses API. */
public final class OpenAiResponsesProvider implements AiProvider {
    private final String apiKey;
    private final String model;
    private final ProviderHttpCaller caller;

    public OpenAiResponsesProvider(String apiKey, String baseUrl, String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.caller = new ProviderHttpCaller("OpenAI", baseUrl);
    }

    @Override public String name() { return "openai"; }

    @Override
    public ChatResponse chat(ChatRequest req) {
        return caller.call(OpenAiResponsesClient.ADAPTER, apiKey, model, name(), req);
    }
}
