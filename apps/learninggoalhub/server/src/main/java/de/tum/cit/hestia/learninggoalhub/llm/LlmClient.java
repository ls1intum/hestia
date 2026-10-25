package de.tum.cit.hestia.learninggoalhub.llm;

import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.content.Media;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

/**
 * The one place the application talks to a chat model.
 *
 * <p>Every call goes through the same {@link ChatClient}, so the usage advisor, the retry template
 * and the HTTP timeouts apply to all of them alike. A blank model means the configured default, and
 * structured replies are always bound with {@link LenientJson}.
 */
@Component
public class LlmClient {

    private final ChatClient chatClient;

    public LlmClient(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /** Sends the request and binds the reply to {@code type}; null when the model returned nothing. */
    public <T> T entity(LlmRequest request, ParameterizedTypeReference<T> type) {
        return spec(request).call().entity(LenientJson.converter(type));
    }

    /** Sends the request and binds the reply to {@code type}; null when the model returned nothing. */
    public <T> T entity(LlmRequest request, Class<T> type) {
        return spec(request).call().entity(LenientJson.converter(type));
    }

    /** Sends the request and returns the reply as plain text. */
    public String text(LlmRequest request) {
        return spec(request).call().content();
    }

    private ChatClient.ChatClientRequestSpec spec(LlmRequest request) {
        ChatOptions.Builder options = ChatOptions.builder();
        if (request.model() != null && !request.model().isBlank()) {
            options.model(request.model());
        }
        if (request.temperature() != null) {
            options.temperature(request.temperature());
        }
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt().options(options.build());
        if (request.system() != null) {
            spec = spec.system(request.system());
        }
        if (request.media().isEmpty()) {
            return spec.user(request.user());
        }
        Media[] media = request.media().toArray(Media[]::new);
        return spec.user(user -> user.text(request.user()).media(media));
    }

    /**
     * One chat call.
     *
     * @param system      the system instruction, or null for none.
     * @param user        the user prompt.
     * @param media       images sent with the user prompt.
     * @param model       the model id, or null or blank for the configured default.
     * @param temperature the sampling temperature, or null for the provider default.
     */
    public record LlmRequest(String system, String user, List<Media> media, String model, Double temperature) {

        public LlmRequest {
            media = media == null ? List.of() : List.copyOf(media);
        }

        public static LlmRequest of(String user) {
            return new LlmRequest(null, user, List.of(), null, null);
        }

        public LlmRequest withSystem(String system) {
            return new LlmRequest(system, user, media, model, temperature);
        }

        public LlmRequest withMedia(List<Media> media) {
            return new LlmRequest(system, user, media, model, temperature);
        }

        public LlmRequest withModel(String model) {
            return new LlmRequest(system, user, media, model, temperature);
        }

        public LlmRequest withTemperature(double temperature) {
            return new LlmRequest(system, user, media, model, temperature);
        }
    }
}
