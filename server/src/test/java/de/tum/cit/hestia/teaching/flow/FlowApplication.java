package de.tum.cit.hestia.teaching.flow;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The application under test, without a model: the real server on a random port, a real Postgres
 * (Testcontainers, Flyway migrations) and a local fake of LearningGoalHub. Which model answers is
 * decided by the subclass: {@link FlowTestSupport} wires the fake, {@link LogosSmokeLiveIT} the real
 * gateway. (A subclass cannot re-point a property its superclass already registered, so neither
 * of them does.)
 *
 * <p>Subclasses must declare their own {@code @ActiveProfiles} (see {@link FlowAuth}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class FlowApplication {

    /** Started once per JVM and shared by all flow test classes (Ryuk removes it at the end). */
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    static final FakeHttpService FAKE_LEARNING_GOAL_HUB = new FakeHttpService();

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void application(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

        // LearningGoalHub: fake
        registry.add("learninggoalhub.base-url", FAKE_LEARNING_GOAL_HUB::baseUrl);
        registry.add("learninggoalhub.api-key", () -> "flow-test-dummy-key");
    }

    /** The production SAML setup would fetch the university IdP's metadata on startup. */
    @MockitoBean
    RelyingPartyRegistrationRepository relyingPartyRegistrationRepository;

    @LocalServerPort
    int port;

    FlowClient signedIn() { return FlowClient.signedIn(port); }

    FlowClient anonymous() { return FlowClient.anonymous(port); }

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
