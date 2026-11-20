package de.tum.cit.hestia.materials.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.tum.cit.hestia.ai.LlmContent;
import de.tum.cit.hestia.ai.LlmGateway;
import de.tum.cit.hestia.ai.LlmRequest;
import de.tum.cit.hestia.ai.LlmResponse;
import de.tum.cit.hestia.ai.Provider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * A bare chat-completions client for the live tests that run without the application context
 * ({@code RUN_LIVE_LOGOS=1}). It stands in for the gateway of the {@code ai} module: no call log, no
 * limits, no retries.
 */
public final class LiveLogosGateway implements LlmGateway {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;
    private final String apiKey;
    private final String defaultModel;

    public LiveLogosGateway(String baseUrl, String apiKey, String defaultModel) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        try {
            ObjectNode body = JSON.createObjectNode();
            body.put("model", request.model().id() != null ? request.model().id() : defaultModel);
            if (request.temperature() != null) {
                body.put("temperature", request.temperature());
            }
            ArrayNode messages = body.putArray("messages");
            if (request.system() != null) {
                messages.addObject().put("role", "system").put("content", request.system());
            }
            ObjectNode user = messages.addObject().put("role", "user");
            switch (request.user()) {
                case LlmContent.TextContent text -> user.put("content", text.text());
                case LlmContent.MultipartContent multipart -> {
                    ArrayNode parts = user.putArray("content");
                    for (LlmContent.ContentPart part : multipart.parts()) {
                        switch (part) {
                            case LlmContent.TextPart t -> parts.addObject().put("type", "text").put("text", t.text());
                            case LlmContent.ImageUrlPart i -> parts.addObject().put("type", "image_url")
                                    .putObject("image_url").put("url", i.url());
                            case LlmContent.FilePart f -> throw new IllegalArgumentException("no files on Logos");
                        }
                    }
                }
            }
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/v1/chat/completions"))
                            .timeout(Duration.ofMinutes(5))
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Logos answered " + response.statusCode() + ": " + response.body());
            }
            JsonNode reply = JSON.readTree(response.body());
            JsonNode usage = reply.path("usage");
            return new LlmResponse(reply.path("choices").path(0).path("message").path("content").asText(null), null,
                    reply.path("model").asText(null), Provider.LOGOS,
                    new LlmResponse.Usage(tokens(usage, "prompt_tokens"), tokens(usage, "completion_tokens"), null));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Integer tokens(JsonNode usage, String field) {
        return usage.hasNonNull(field) ? usage.get(field).asInt() : null;
    }
}
