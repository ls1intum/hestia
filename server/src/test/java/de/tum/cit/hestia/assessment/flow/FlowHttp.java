package de.tum.cit.hestia.assessment.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;

/**
 * Minimal JSON-over-HTTP client for the flow tests. Speaks to the running
 * server only through its public URL; paths come from {@link FlowPaths}, auth
 * from {@link FlowAuth}.
 */
final class FlowHttp {

    static final ObjectMapper JSON = new ObjectMapper();

    /** Status plus the body parsed as JSON (null when the body is empty or not JSON). */
    record Reply(int status, JsonNode json, String raw) {}

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final String origin;

    FlowHttp(int port) {
        this.origin = "http://localhost:" + port;
    }

    Reply get(String apiPath) {
        return send(authed(builder(apiPath)).GET());
    }

    Reply getUnauthenticated(String apiPath) {
        return send(builder(apiPath).GET());
    }

    Reply getWithInvalidCredential(String apiPath) {
        return send(FlowAuth.applyInvalid(builder(apiPath)).GET());
    }

    Reply postJson(String apiPath, Object body) {
        return send(authed(builder(apiPath)).header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString(toJson(body))));
    }

    Reply postJsonUnauthenticated(String apiPath, Object body) {
        return send(builder(apiPath).header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString(toJson(body))));
    }

    /** multipart/form-data with a single {@code file} part. */
    Reply postFile(String apiPath, String filename, String contentType, byte[] bytes) {
        String boundary = "flow-" + UUID.randomUUID();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
            + "Content-Type: " + contentType + "\r\n\r\n");
        out.writeBytes(bytes);
        write(out, "\r\n--" + boundary + "--\r\n");
        return send(authed(builder(apiPath))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(BodyPublishers.ofByteArray(out.toByteArray())));
    }

    /**
     * Poll {@code apiPath} until {@code done} accepts the JSON body or the timeout passes;
     * returns the last reply either way so the caller's assertion shows what it saw.
     */
    Reply pollUntil(String apiPath, Function<JsonNode, Boolean> done, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Reply last;
        do {
            last = get(apiPath);
            if (last.status() == 200 && last.json() != null && done.apply(last.json())) return last;
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        } while (System.nanoTime() < deadline);
        return last;
    }

    private HttpRequest.Builder builder(String apiPath) {
        return HttpRequest.newBuilder(URI.create(origin + apiPath)).timeout(Duration.ofSeconds(60));
    }

    private static HttpRequest.Builder authed(HttpRequest.Builder b) {
        return FlowAuth.apply(b);
    }

    private Reply send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> res = client.send(request.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String raw = res.body();
            JsonNode json = null;
            if (raw != null && !raw.isBlank()) {
                try {
                    json = JSON.readTree(raw);
                } catch (Exception notJson) {
                    // leave json null; the raw body is still available
                }
            }
            return new Reply(res.statusCode(), json, raw);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("HTTP call failed: " + e, e);
        }
    }

    private static String toJson(Object body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }
}
