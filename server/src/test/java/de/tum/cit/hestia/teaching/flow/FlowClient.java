package de.tum.cit.hestia.teaching.flow;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.UnaryOperator;

/** Minimal real HTTP client against the running test server; request bodies are plain Maps/Lists. */
final class FlowClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;
    private final UnaryOperator<HttpRequest.Builder> auth;

    private FlowClient(int port, UnaryOperator<HttpRequest.Builder> auth) {
        this.baseUrl = "http://localhost:" + port;
        this.auth = auth;
    }

    static FlowClient signedIn(int port) { return new FlowClient(port, FlowAuth::signIn); }

    static FlowClient anonymous(int port) { return new FlowClient(port, b -> b); }

    FlowResponse get(String path) { return send("GET", path, null); }

    FlowResponse post(String path, Object json) { return send("POST", path, json); }

    FlowResponse put(String path, Object json) { return send("PUT", path, json); }

    FlowResponse delete(String path) { return send("DELETE", path, null); }

    private FlowResponse send(String method, String path, Object json) {
        try {
            HttpRequest.BodyPublisher body = json == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(json), StandardCharsets.UTF_8);
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Accept", "application/json")
                    .method(method, body);
            if (json != null) request.header("Content-Type", "application/json");
            HttpResponse<String> response = http.send(auth.apply(request).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new FlowResponse(response.statusCode(), response.body());
        } catch (Exception e) {
            throw new IllegalStateException(method + " " + path + " failed", e);
        }
    }
}
