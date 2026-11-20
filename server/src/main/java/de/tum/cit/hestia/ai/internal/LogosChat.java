package de.tum.cit.hestia.ai.internal;

import de.tum.cit.hestia.ai.LlmContent;
import de.tum.cit.hestia.ai.LlmRequest;
import de.tum.cit.hestia.ai.LlmResponse;
import de.tum.cit.hestia.ai.Provider;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Calls the Logos gateway (OpenAI-compatible) through Spring AI's chat model, configured once under
 * {@code spring.ai.openai.*}. The application-wide model has the long timeouts and the patient retry
 * ladder of the extraction runs; a request with its own {@link LlmRequest.Limits} gets a copy of the
 * model with those instead (same model, options and credentials), built once per set of limits.
 */
@Component
class LogosChat {

    private final ChatModel chatModel;
    private final Environment env;
    private final Map<LlmRequest.Limits, ChatModel> limited = new ConcurrentHashMap<>();

    LogosChat(ChatModel chatModel, Environment env) {
        this.chatModel = chatModel;
        this.env = env;
    }

    LlmResponse call(LlmRequest request) {
        if (request.tool() != null) {
            throw new IllegalArgumentException("Tool calls are not supported on Logos");
        }
        List<Message> messages = new ArrayList<>(2);
        if (request.system() != null) {
            messages.add(new SystemMessage(request.system()));
        }
        messages.add(userMessage(request.user()));

        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder();
        if (request.model().id() != null) {
            options.model(request.model().id());
        }
        if (request.temperature() != null) {
            options.temperature(request.temperature());
        }
        ChatModel model = request.limits() == null ? chatModel : limited.computeIfAbsent(request.limits(), this::withLimits);
        ChatResponse response = model.call(new Prompt(messages, options.build()));

        String text = response.getResult() == null ? null : response.getResult().getOutput().getText();
        String actualModel = response.getMetadata() == null ? request.model().id() : response.getMetadata().getModel();
        return new LlmResponse(text, null, actualModel, Provider.LOGOS, usage(response));
    }

    private static UserMessage userMessage(LlmContent.UserContent content) {
        if (content instanceof LlmContent.TextContent text) {
            return new UserMessage(text.text());
        }
        LlmContent.MultipartContent multipart = (LlmContent.MultipartContent) content;
        StringBuilder text = new StringBuilder();
        List<Media> media = new ArrayList<>();
        for (LlmContent.ContentPart part : multipart.parts()) {
            switch (part) {
                case LlmContent.TextPart t -> {
                    if (!text.isEmpty()) {
                        text.append("\n\n");
                    }
                    text.append(t.text());
                }
                case LlmContent.ImageUrlPart image -> media.add(media(image.url()));
                case LlmContent.FilePart file ->
                        throw new IllegalArgumentException("File parts are not supported on Logos: " + file.filename());
            }
        }
        return UserMessage.builder().text(text.toString()).media(media).build();
    }

    /** A data URL becomes inline bytes; any other URL is passed on as is. */
    static Media media(String url) {
        if (url.startsWith("data:")) {
            int comma = url.indexOf(',');
            String meta = url.substring("data:".length(), comma);
            int semi = meta.indexOf(';');
            MimeType mime = MimeTypeUtils.parseMimeType(semi >= 0 ? meta.substring(0, semi) : meta);
            byte[] bytes = Base64.getDecoder().decode(url.substring(comma + 1));
            return new Media(mime, new ByteArrayResource(bytes));
        }
        return new Media(MimeTypeUtils.IMAGE_PNG, URI.create(url));
    }

    private static LlmResponse.Usage usage(ChatResponse response) {
        if (response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            return LlmResponse.Usage.NONE;
        }
        Usage usage = response.getMetadata().getUsage();
        Integer reasoning = usage.getNativeUsage() instanceof OpenAiApi.Usage openAi
                && openAi.completionTokenDetails() != null
                ? openAi.completionTokenDetails().reasoningTokens()
                : null;
        return new LlmResponse.Usage(usage.getPromptTokens(), usage.getCompletionTokens(), reasoning);
    }

    private ChatModel withLimits(LlmRequest.Limits limits) {
        if (!(chatModel instanceof OpenAiChatModel openAi)) {
            return chatModel;
        }
        RestClient.Builder restClient = RestClient.builder().requestFactory(
                ClientHttpRequestFactoryBuilder.detect().build(ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(limits.connectTimeout()).withReadTimeout(limits.readTimeout())));
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(env.getRequiredProperty("spring.ai.openai.base-url"))
                .apiKey(env.getProperty("spring.ai.openai.api-key", ""))
                .completionsPath(env.getProperty("spring.ai.openai.chat.completions-path", "/v1/chat/completions"))
                .restClientBuilder(restClient)
                .build();
        RetryTemplate retry = RetryTemplate.builder()
                .maxAttempts(limits.maxAttempts())
                .retryOn(TransientAiException.class)
                .retryOn(ResourceAccessException.class)
                .exponentialBackoff(Duration.ofSeconds(2), 2, Duration.ofSeconds(10))
                .build();
        return openAi.mutate().openAiApi(api).retryTemplate(retry).build();
    }
}
