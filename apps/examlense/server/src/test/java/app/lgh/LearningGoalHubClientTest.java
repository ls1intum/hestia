package app.lgh;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LearningGoalHubClientTest {

    private HttpServer server;
    private final List<String> receivedKeys = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startFakeLgh() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/courses", exchange -> {
            receivedKeys.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Api-Key")));
            byte[] body = "{\"content\":[{\"id\":1,\"name\":\"ML\"}],\"page\":{\"totalPages\":1}}"
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

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void sendsTheServiceKeyOnEveryRequest() {
        List<LghDtos.Course> courses = new LearningGoalHubClient(baseUrl(), "examlense-key").listCourses();

        assertThat(courses).extracting(LghDtos.Course::name).containsExactly("ML");
        assertThat(receivedKeys).containsExactly("examlense-key");
    }

    @Test
    void sendsNoKeyHeaderWhenNoneIsConfigured() {
        new LearningGoalHubClient(baseUrl(), "").listCourses();

        assertThat(receivedKeys).containsExactly("null");
    }
}
