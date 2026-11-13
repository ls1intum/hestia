package app.flow;

import app.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for the HTTP flow tests: the real application on a random port, a real
 * Testcontainers Postgres (inherited), and every outbound AI / LearningGoalHub
 * call pointed at a local fake. Tests talk to the app only through {@link #http}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractHttpFlowTest extends AbstractIntegrationTest {

    static final FakeModelServer FAKE_MODEL = new FakeModelServer();

    /** Never a real key; set explicitly so a key in the environment can not leak in. */
    private static final String FAKE_KEY = "fake-key-for-http-flow-tests";

    @LocalServerPort
    private int port;

    protected FlowHttp http;

    @BeforeEach
    void connect() {
        http = new FlowHttp(port);
    }

    @DynamicPropertySource
    static void fakeOutbound(DynamicPropertyRegistry registry) {
        registry.add("ai.openai.api-key", () -> FAKE_KEY);
        registry.add("ai.openai.base-url", () -> FAKE_MODEL.url("/openai"));
        registry.add("ai.anthropic.api-key", () -> FAKE_KEY);
        registry.add("ai.anthropic.base-url", () -> FAKE_MODEL.url("/anthropic"));
        registry.add("ai.gemini.api-key", () -> FAKE_KEY);
        registry.add("ai.gemini.base-url", () -> FAKE_MODEL.url("/gemini"));
        // None of these flows calls LearningGoalHub; point it at the fake anyway so an
        // unexpected call fails fast (the fake answers 400) instead of leaving the machine.
        registry.add("learninggoalhub.base-url", () -> FAKE_MODEL.url("/lgh"));
        registry.add("learninggoalhub.api-key", () -> FAKE_KEY);
    }
}
