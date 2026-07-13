package app.ai;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Shared HTTP transport for every provider client: one {@link RestClient} per
 * provider instance, retry with jittered exponential backoff, and uniform
 * status → exception mapping (429 → rate limit, 402 → payment required,
 * other non-2xx → {@link AiExceptions.ProviderException}).
 *
 * Provider-specific request/response shapes live behind {@link Adapter}, so
 * retry policy and error mapping cannot drift between providers.
 *
 * Resilience:
 *   - Connect timeout 10s, read timeout 8min (vision + tool calls are slow).
 *   - Up to {@link #MAX_ATTEMPTS} attempts on transient failures only:
 *     network errors, HTTP 408/425/429/500/502/503/504, and malformed model
 *     output (one more shot at a nondeterministic response). 4xx auth /
 *     schema failures fail fast.
 *   - Exponential backoff with jitter, capped at {@link #MAX_BACKOFF_MS}.
 */
final class ProviderHttpCaller {

    /** The provider-specific half of a chat call: request shape in, parsed response out. */
    interface Adapter {
        /** Request path, e.g. {@code /v1/messages}; may embed the model id (Gemini). */
        String path(String model);

        /** Set auth (and any protocol) headers. */
        void applyHeaders(HttpHeaders headers, String apiKey);

        Map<String, Object> buildBody(String model, AiProvider.ChatRequest req);

        AiProvider.ChatResponse parseResponse(String json, String requestedModel, String providerName);
    }

    private static final Logger log = LoggerFactory.getLogger(ProviderHttpCaller.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(8);
    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 300L;
    private static final long MAX_BACKOFF_MS = 3_000L;

    private final String displayName;
    private final RestClient client;

    ProviderHttpCaller(String displayName, String baseUrl) {
        this.displayName = displayName;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        this.client = RestClient.builder()
            .baseUrl(baseUrl.replaceAll("/$", ""))
            .requestFactory(factory)
            .build();
    }

    AiProvider.ChatResponse call(Adapter adapter, String apiKey, String model, String providerName, AiProvider.ChatRequest req) {
        Map<String, Object> body = adapter.buildBody(model, req);

        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return doCall(adapter, apiKey, model, providerName, body);
            } catch (RuntimeException e) {
                lastError = e;
                if (!isRetryable(e) || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                long delay = backoffMs(attempt);
                log.warn("{} attempt {}/{} for {} failed ({}); retrying in {}ms",
                    displayName, attempt, MAX_ATTEMPTS, model, e.getMessage(), delay);
                sleep(delay);
            }
        }
        throw lastError; // unreachable — kept for compiler
    }

    private AiProvider.ChatResponse doCall(
        Adapter adapter, String apiKey, String model, String providerName, Map<String, Object> body
    ) {
        try {
            // Use .exchange() so we drain the response body ourselves — bypasses
            // the message-converter pipeline that chokes on upstreams returning
            // JSON labelled as application/octet-stream (seen on GWDG).
            String json = client.post()
                .uri(adapter.path(model))
                .headers(h -> adapter.applyHeaders(h, apiKey))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    byte[] raw = response.getBody().readAllBytes();
                    String text = raw == null ? "" : new String(raw, StandardCharsets.UTF_8);
                    if (status >= 400) {
                        String preview = text.trim();
                        if (status == 429) throw new AiExceptions.RateLimitException(displayName + " rate limited");
                        if (status == 402) throw new AiExceptions.PaymentRequiredException(displayName + " credits exhausted");
                        throw new AiExceptions.ProviderException(
                            displayName + " returned " + status + ": "
                                + preview.substring(0, Math.min(preview.length(), 500)),
                            status
                        );
                    }
                    return text;
                });
            return adapter.parseResponse(json, model, providerName);
        } catch (AiExceptions.RateLimitException | AiExceptions.PaymentRequiredException | AiExceptions.ProviderException e) {
            throw e;
        } catch (ResourceAccessException e) {
            // Connect/read timeout, DNS failure, socket reset — transient.
            throw new AiExceptions.ProviderException(displayName + " transport failure: " + e.getMessage(), 0);
        } catch (Exception e) {
            throw new AiExceptions.ProviderException(displayName + " call failed: " + e.getMessage(), 0);
        }
    }

    private static boolean isRetryable(RuntimeException e) {
        if (e instanceof AiExceptions.RateLimitException) return true;
        if (e instanceof AiExceptions.PaymentRequiredException) return false;
        // One more shot at nondeterministic output (no tool call / bad JSON).
        if (e instanceof AiExceptions.MalformedModelOutputException) return true;
        if (e instanceof AiExceptions.ProviderException pe) {
            int s = pe.status();
            // 0 = transport failure (timeout, reset); 5xx = upstream; specific 4xx that imply retry-after.
            return s == 0 || s == 408 || s == 425 || s == 500 || s == 502 || s == 503 || s == 504;
        }
        return false;
    }

    private static long backoffMs(int attempt) {
        long base = INITIAL_BACKOFF_MS * (1L << (attempt - 1));
        long jitter = ThreadLocalRandom.current().nextLong(base / 2 + 1);
        return Math.min(base + jitter, MAX_BACKOFF_MS);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new AiExceptions.ProviderException("Interrupted while waiting to retry " + displayName, 0);
        }
    }
}
