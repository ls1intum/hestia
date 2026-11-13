package app.lgh;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LearningGoalHubClientTest {

    private HttpServer server;
    private final List<String> receivedKeys = new CopyOnWriteArrayList<>();
    private final List<String> derivationRequests = new CopyOnWriteArrayList<>();

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
        // Like LGH, the derivation endpoint answers only a consuming server's valid key.
        server.createContext("/api/courses/7/exam-tasks/learning-goals", exchange -> {
            String key = exchange.getRequestHeaders().getFirst("X-Api-Key");
            derivationRequests.add(exchange.getRequestURI() + " key=" + key);
            if (!"examlense-key".equals(key)) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            byte[] body = ("[{\"blockId\":\"b1\",\"goals\":[{\"id\":11,\"text\":\"Explain overfitting\","
                + "\"kind\":\"EXPLICIT\",\"status\":\"PENDING\",\"bloomLevel\":\"UNDERSTAND\","
                + "\"soloLevel\":\"RELATIONAL\"}]}]").getBytes(StandardCharsets.UTF_8);
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

    @Test
    void goalDerivationSendsTheServiceKeyAndReturnsTheGoals() {
        List<LghDtos.ExamTaskGoals> result = new LearningGoalHubClient(baseUrl(), "examlense-key")
            .generateExamGoals(7, List.of(new LghDtos.ExamBlock("b1", "task", "text", "Explain overfitting.")));

        assertThat(result).singleElement().satisfies(task -> {
            assertThat(task.blockId()).isEqualTo("b1");
            assertThat(task.goals()).extracting(LghDtos.LearningGoal::text)
                .containsExactly("Explain overfitting");
        });
        assertThat(derivationRequests).containsExactly("/api/courses/7/exam-tasks/learning-goals key=examlense-key");
    }

    @Test
    void goalDerivationFailsWithoutAValidServiceKey() {
        List<LghDtos.ExamBlock> blocks = List.of(new LghDtos.ExamBlock("b1", "task", "text", "Explain overfitting."));

        assertThatThrownBy(() -> new LearningGoalHubClient(baseUrl(), "").generateExamGoals(7, blocks))
            .isInstanceOf(HttpClientErrorException.Unauthorized.class);
        assertThatThrownBy(() -> new LearningGoalHubClient(baseUrl(), "wrong-key").generateExamGoals(7, blocks))
            .isInstanceOf(HttpClientErrorException.Unauthorized.class);
    }
}
