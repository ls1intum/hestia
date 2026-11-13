package de.tum.cit.hestia.materials.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * The one place that knows how a flow test reaches the server: where the API lives
 * ({@link #BASE_PATH}) and how a client logs in ({@link #loggedIn}). Moving the API under
 * a module prefix or replacing the login changes this class (and the property setup in
 * {@code HttpFlowTestBase}), not the tests.
 */
final class HubApi {

    /** Prefix of every API path. */
    static final String BASE_PATH = "/learninggoalhub/api";

    /** A status code, the raw body and the body parsed as JSON (a missing node when there is none). */
    record Response(int status, String raw, JsonNode json) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Keeps the session cookie, so a login of this client counts for its later requests. */
    private final HttpClient client = HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String origin;

    private HubApi(int port) {
        this.origin = "http://localhost:" + port;
    }

    /**
     * A client with a session of a logged-in user. The application has no password login, so the
     * test setup ({@link FlowLoginConfiguration}) offers a login request that exists only in tests.
     */
    static HubApi loggedIn(int port) {
        HubApi api = new HubApi(port);
        Response login = api.send(HttpRequest.newBuilder(URI.create(api.origin + FlowLoginConfiguration.LOGIN_PATH))
                .POST(HttpRequest.BodyPublishers.noBody()));
        if (login.status() != 204) {
            throw new IllegalStateException("The test login failed with status " + login.status());
        }
        return api;
    }

    /** A client that sends no credentials at all. */
    static HubApi anonymous(int port) {
        return new HubApi(port);
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
