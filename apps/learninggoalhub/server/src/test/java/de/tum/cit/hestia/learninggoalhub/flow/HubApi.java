package de.tum.cit.hestia.learninggoalhub.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * The one place that knows how a flow test reaches the server: where the API lives
 * ({@link #BASE_PATH}) and how a request authenticates ({@link #authenticate}). Moving the API under
 * a module prefix or replacing the login changes this class (and the property setup in
 * {@code HttpFlowTestBase}), not the tests.
 */
final class HubApi {

    /** Prefix of every API path. */
    static final String BASE_PATH = "/api";

    /**
     * The shared service key the tests configure (see {@code HttpFlowTestBase}) and send as a header;
     * a made-up value for this test only, not a secret.
     */
    static final String TEST_API_KEY = "http-flow-test-key";

    /** A status code, the raw body and the body parsed as JSON (a missing node when there is none). */
    record Response(int status, String raw, JsonNode json) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String origin;
    private final boolean authenticated;

    private HubApi(int port, boolean authenticated) {
        this.origin = "http://localhost:" + port;
        this.authenticated = authenticated;
    }

    /** A client that sends the credentials the server accepts. */
    static HubApi loggedIn(int port) {
        return new HubApi(port, true);
    }

    /** A client that sends no credentials at all. */
    static HubApi anonymous(int port) {
        return new HubApi(port, false);
    }

    /** Adds the authentication to a request. */
    private void authenticate(HttpRequest.Builder request) {
        if (authenticated) {
            request.header("X-Api-Key", TEST_API_KEY);
        }
    }

    Response get(String path) {
        return send(builder(path).GET());
    }

    Response postJson(String path, Map<String, ?> body) {
        return send(builder(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body))));
    }

    Response post(String path) {
        return send(builder(path).POST(HttpRequest.BodyPublishers.noBody()));
    }

    Response patchJson(String path, Map<String, ?> body) {
        return send(builder(path).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(toJson(body))));
    }

    Response delete(String path) {
        return send(builder(path).DELETE());
    }

    /** Uploads files as one multipart request with the form field "files", like the client does. */
    Response upload(String path, Map<String, byte[]> filesByName, String contentType) {
        String boundary = "----flowtest" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        filesByName.forEach((name, bytes) -> {
            body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\""
                    + name + "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.writeBytes(bytes);
            body.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        });
        body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return send(builder(path).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())));
    }

    private HttpRequest.Builder builder(String path) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(origin + BASE_PATH + path))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json");
        authenticate(request);
        return request;
    }

    private Response send(HttpRequest.Builder request) {
        HttpRequest built = request.build();
        try {
            HttpResponse<String> response = client.send(built, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String raw = response.body() == null ? "" : response.body();
            JsonNode json;
            try {
                json = raw.isBlank() ? JSON.missingNode() : JSON.readTree(raw);
            } catch (IOException notJson) {
                json = JSON.missingNode();
            }
            return new Response(response.statusCode(), raw, json);
        } catch (IOException e) {
            throw new IllegalStateException("Request failed: " + built.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String toJson(Object body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
