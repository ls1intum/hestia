package de.tum.cit.hestia.teaching.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LearningGoalHubClientTest {

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startFakeLgh() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/courses", exchange -> {
            requests.add(exchange.getRequestURI() + " key=" + exchange.getRequestHeaders().getFirst("X-Api-Key"));
            String path = exchange.getRequestURI().getPath();
            int status = path.contains("/404/") ? 404 : path.contains("/500/") ? 500 : 200;
            byte[] body = (path.endsWith("by-session") ? "[{\"label\":\"Session 1\",\"goals\":[]}]"
                    : "{\"content\":[{\"id\":7,\"name\":\"ML\"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        // Like LGH, the goal endpoint answers only a consuming server's valid key.
        server.createContext("/api/courses/8/learning-goals/by-session", exchange -> {
            String key = exchange.getRequestHeaders().getFirst("X-Api-Key");
            requests.add(exchange.getRequestURI() + " key=" + key);
            if (!"workshopper-key".equals(key)) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            byte[] body = "[{\"label\":\"Session 1\",\"goals\":[{\"id\":3,\"text\":\"Explain overfitting\"}]}]"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopFakeLgh() {
        server.stop(0);
    }

    private LearningGoalHubClient client(String key) {
        return new LearningGoalHubClient("http://127.0.0.1:" + server.getAddress().getPort() + "/", key);
    }

    @Test
    void forwardsLghAnswersAndSendsTheServiceKey() {
        JsonNode courses = client("workshopper-key").courses(0, 100);
        JsonNode sessions = client("workshopper-key").goalsBySession(7);

        assertThat(courses.get("content").get(0).get("name").asText()).isEqualTo("ML");
        assertThat(sessions.get(0).get("label").asText()).isEqualTo("Session 1");
        assertThat(requests).containsExactly(
                "/api/courses?page=0&size=100 key=workshopper-key",
                "/api/courses/7/learning-goals/by-session key=workshopper-key");
    }

    @Test
    void sendsNoKeyWhenNoneIsConfigured() {
        client("").courses(0, 100);

        assertThat(requests).containsExactly("/api/courses?page=0&size=100 key=null");
    }

    @Test
    void goalFetchWorksWithTheServiceKeyAndFailsWithoutIt() {
        JsonNode sessions = client("workshopper-key").goalsBySession(8);
        assertThat(sessions.get(0).get("goals").get(0).get("text").asText()).isEqualTo("Explain overfitting");

        for (String key : List.of("", "wrong-key")) {
            assertThatThrownBy(() -> client(key).goalsBySession(8))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
        }
        assertThat(requests).containsExactly(
                "/api/courses/8/learning-goals/by-session key=workshopper-key",
                "/api/courses/8/learning-goals/by-session key=null",
                "/api/courses/8/learning-goals/by-session key=wrong-key");
    }

    @Test
    void mapsAnUnknownCourseTo404AndOtherFailuresTo502() {
        assertThatThrownBy(() -> client("k").goalsBySession(404))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> client("k").goalsBySession(500))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }
}
