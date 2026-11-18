package de.tum.cit.hestia.teaching.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.tum.cit.hestia.TestDatabase;
import de.tum.cit.hestia.core.spi.GoalDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The application under test, without a model: the real server on a random port, the shared test Postgres
 * ({@link TestDatabase}, Flyway migrations) and a stub of the materials module's goal directory. Which model answers is
 * decided by the subclass: {@link FlowTestSupport} wires the fake, {@link LogosSmokeLiveIT} the real
 * gateway. (A subclass cannot re-point a property its superclass already registered, so neither
 * of them does.)
 *
 * <p>Subclasses must declare their own {@code @ActiveProfiles} (see {@link FlowAuth}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class FlowApplication {

    static final FakeGoalDirectory FAKE_LEARNING_GOAL_HUB = new FakeGoalDirectory();

    @DynamicPropertySource
    static void application(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    /** The production SAML setup would fetch the university IdP's metadata on startup. */
    @MockitoBean
    RelyingPartyRegistrationRepository relyingPartyRegistrationRepository;

    /** The materials module is not under test here: teaching reads courses and goals through this port. */
    @MockitoBean
    GoalDirectory goalDirectory;

    @BeforeEach
    void stubGoalDirectory() {
        Mockito.when(goalDirectory.listCourses(Mockito.anyInt(), Mockito.anyInt()))
                .thenAnswer(call -> FAKE_LEARNING_GOAL_HUB.courses(call.getArgument(0), call.getArgument(1)));
    }

    @LocalServerPort
    int port;

    FlowClient signedIn() { return FlowClient.signedIn(port); }

    FlowClient anonymous() { return FlowClient.anonymous(port); }

    /** Answers the goal directory with the courses of one canned JSON body ({@code content: [{id, name}]}). */
    static final class FakeGoalDirectory {
        final List<String> requests = new CopyOnWriteArrayList<>();
        private final AtomicReference<String> body = new AtomicReference<>("{}");

        void respondWith(String json) { body.set(json); }

        GoalDirectory.Page<GoalDirectory.Course> courses(int page, int size) {
            requests.add("GET courses?page=" + page + "&size=" + size);
            try {
                JsonNode content = new ObjectMapper().readTree(body.get()).path("content");
                List<GoalDirectory.Course> courses = new ArrayList<>();
                content.forEach(c -> courses.add(new GoalDirectory.Course(c.path("id").asLong(), c.path("name").asText())));
                return new GoalDirectory.Page<>(courses, page, size, courses.size(), 1);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** A tiny local HTTP server that answers every request with one canned JSON body. */
    static final class FakeHttpService {
        final List<String> requests = new CopyOnWriteArrayList<>();
        private final AtomicReference<String> body = new AtomicReference<>("{}");
        private final HttpServer server;

        FakeHttpService() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.createContext("/", this::handle);
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "flow-fake-http");
                t.setDaemon(true);
                return t;
            }));
            server.start();
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        void respondWith(String json) { body.set(json); }

        private void handle(HttpExchange exchange) throws IOException {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
