package de.tum.cit.hestia.materials.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.tum.cit.hestia.ai.LlmContent;
import de.tum.cit.hestia.ai.LlmGateway;
import de.tum.cit.hestia.ai.LlmRequest;
import de.tum.cit.hestia.ai.LlmResponse;
import de.tum.cit.hestia.ai.Provider;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fake {@link LlmGateway} for the module's tests: answers with the given replies in order (the last
 * one repeats), records every request, and hands out an {@link LlmClient} on top of itself. A reply
 * is an object (sent as its JSON), a string (sent as it is), a throwable (thrown) or null (no text).
 */
public final class FakeLlm implements LlmGateway {

    /** Where Spring AI's format instructions start in a structured prompt. */
    private static final String FORMAT_MARKER = "Your response should be in JSON format.";

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    /** Computes a reply for a request; may block or throw. */
    @FunctionalInterface
    public interface Answerer {
        Object answer(LlmRequest request) throws Exception;
    }

    private final List<Object> replies;
    private final List<LlmRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger next = new AtomicInteger();
    private final Answerer answerer;

    private FakeLlm(List<Object> replies, Answerer answerer) {
        this.replies = replies;
        this.answerer = answerer;
    }

    /** Answers each request with what the function returns (same reply types as {@link #replying}). */
    public static FakeLlm answering(Answerer answerer) {
        return new FakeLlm(List.of(), answerer);
    }

    public static FakeLlm replying(Object... replies) {
        List<Object> list = new ArrayList<>();
        for (Object reply : replies) {
            list.add(reply);
        }
        return new FakeLlm(list, null);
    }

    public LlmClient client() {
        return client((model, prompt, completion, reasoning) -> false);
    }

    public LlmClient client(LlmUsageSink sink) {
        return new LlmClient(this, sink);
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        requests.add(request);
        Object reply = answerer != null ? answerFrom(request)
                : replies.isEmpty() ? null : replies.get(Math.min(next.getAndIncrement(), replies.size() - 1));
        if (reply instanceof RuntimeException e) {
            throw e;
        }
        String text;
        try {
            text = reply == null || reply instanceof String ? (String) reply : JSON.writeValueAsString(reply);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return new LlmResponse(text, null, "fake-model", Provider.LOGOS, null);
    }

    private Object answerFrom(LlmRequest request) {
        try {
            return answerer.answer(request);
        } catch (RuntimeException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public List<LlmRequest> requests() {
        return requests;
    }

    public LlmRequest only() {
        if (requests.size() != 1) {
            throw new AssertionError("expected one request, got " + requests.size());
        }
        return requests.getFirst();
    }

    /** The user prompts as the caller wrote them, without the format instructions the client appends. */
    public List<String> prompts() {
        return requests.stream().map(FakeLlm::prompt).toList();
    }

    public String prompt() {
        return prompts().getFirst();
    }

    public static String prompt(LlmRequest request) {
        String text = switch (request.user()) {
            case LlmContent.TextContent t -> t.text();
            case LlmContent.MultipartContent m -> m.parts().stream()
                    .filter(LlmContent.TextPart.class::isInstance)
                    .map(p -> ((LlmContent.TextPart) p).text())
                    .findFirst().orElse("");
        };
        int format = text.indexOf(FORMAT_MARKER);
        if (format < 0) {
            return text;
        }
        String head = text.substring(0, format);
        String join = System.lineSeparator();
        return head.endsWith(join) ? head.substring(0, head.length() - join.length()) : head;
    }
}
