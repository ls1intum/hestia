package de.tum.cit.hestia.materials.llm;

import de.tum.cit.hestia.ai.LlmContent;
import de.tum.cit.hestia.ai.LlmGateway;
import de.tum.cit.hestia.ai.LlmResponse;
import de.tum.cit.hestia.ai.ModelRef;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

/**
 * The module's adapter to the {@code ai} module's {@link LlmGateway}.
 *
 * <p>Every call names its purpose, so the gateway's call log, the retry policy and the HTTP timeouts
 * apply to all of them alike. A blank model means the configured default, and structured replies are
 * always bound with {@link LenientJson}. The tokens of an answered call are booked through
 * {@link LlmUsageSink}: a call made while an extraction runs is booked to that run's current phase,
 * any other call (upload, the review wizard) is logged on its own.
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final LlmGateway gateway;
    private final LlmUsageSink usageSink;

    public LlmClient(LlmGateway gateway, LlmUsageSink usageSink) {
        this.gateway = gateway;
        this.usageSink = usageSink;
    }

    /** Sends the request and binds the reply to {@code type}; null when the model returned nothing. */
    public <T> T entity(LlmRequest request, ParameterizedTypeReference<T> type) {
        var converter = LenientJson.converter(type);
        return convert(request, converter.getFormat(), converter::convert);
    }

    /** Sends the request and binds the reply to {@code type}; null when the model returned nothing. */
    public <T> T entity(LlmRequest request, Class<T> type) {
        var converter = LenientJson.converter(type);
        return convert(request, converter.getFormat(), converter::convert);
    }

    /** Sends the request and returns the reply as plain text. */
    public String text(LlmRequest request) {
        return call(request, null);
    }

    private <T> T convert(LlmRequest request, String format, java.util.function.Function<String, T> converter) {
        String reply = call(request, format);
        return reply == null || reply.isBlank() ? null : converter.apply(reply);
    }

    private String call(LlmRequest request, String formatInstructions) {
        de.tum.cit.hestia.ai.LlmRequest gatewayRequest = de.tum.cit.hestia.ai.LlmRequest
                .of("materials", request.purpose(), content(request, formatInstructions))
                .withModel(ModelRef.logos(request.model()))
                .withSystem(request.system())
                .withTemperature(request.temperature());
        if (request.courseId() != null) {
            gatewayRequest = gatewayRequest.forCourse(request.courseId());
        }
        LlmResponse response = gateway.chat(gatewayRequest);
        book(response);
        return response.text();
    }

    private static LlmContent.UserContent content(LlmRequest request, String formatInstructions) {
        String text = formatInstructions == null ? request.user() : request.user() + System.lineSeparator() + formatInstructions;
        if (request.images().isEmpty()) {
            return new LlmContent.TextContent(text);
        }
        List<LlmContent.ContentPart> parts = new ArrayList<>(request.images().size() + 1);
        parts.add(new LlmContent.TextPart(text));
        request.images().forEach(image -> parts.add(new LlmContent.ImageUrlPart(image.dataUrl())));
        return new LlmContent.MultipartContent(parts);
    }

    private void book(LlmResponse response) {
        LlmResponse.Usage usage = response.usage();
        if (usage == LlmResponse.Usage.NONE) {
            return;
        }
        int prompt = orZero(usage.promptTokens());
        int completion = orZero(usage.completionTokens());
        int reasoning = orZero(usage.reasoningTokens());
        if (!usageSink.record(response.model(), prompt, completion, reasoning)) {
            log.info("LLM call outside an extraction: model={} prompt={} completion={} (reasoning {}) tokens",
                    response.model(), prompt, completion, reasoning);
        }
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    /** An image sent with the user prompt, as {@code data:<mime>;base64,<data>}. */
    public record Image(String dataUrl) {

        public static Image png(byte[] bytes) {
            return new Image("data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes));
        }
    }

    /**
     * One chat call.
     *
     * @param purpose     what the call is for, kebab-case (for example {@code page-description}).
     * @param courseId    the core course the call belongs to, or null if the caller has none at hand.
     * @param system      the system instruction, or null for none.
     * @param user        the user prompt.
     * @param images      images sent with the user prompt.
     * @param model       the model id, or null or blank for the configured default.
     * @param temperature the sampling temperature, or null for the provider default.
     */
    public record LlmRequest(String purpose, UUID courseId, String system, String user, List<Image> images,
                             String model, Double temperature) {

        public LlmRequest {
            java.util.Objects.requireNonNull(purpose, "purpose");
            images = images == null ? List.of() : List.copyOf(images);
        }

        public static LlmRequest of(String purpose, String user) {
            return new LlmRequest(purpose, null, null, user, List.of(), null, null);
        }

        public LlmRequest forCourse(UUID courseId) {
            return new LlmRequest(purpose, courseId, system, user, images, model, temperature);
        }

        public LlmRequest withSystem(String system) {
            return new LlmRequest(purpose, courseId, system, user, images, model, temperature);
        }

        public LlmRequest withImages(List<Image> images) {
            return new LlmRequest(purpose, courseId, system, user, images, model, temperature);
        }

        public LlmRequest withModel(String model) {
            return new LlmRequest(purpose, courseId, system, user, images, model, temperature);
        }

        public LlmRequest withTemperature(double temperature) {
            return new LlmRequest(purpose, courseId, system, user, images, model, temperature);
        }
    }
}
