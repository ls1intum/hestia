package de.tum.cit.hestia.assessment.flow;

import de.tum.cit.hestia.assessment.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for the HTTP flow tests: the real application on a random port, the shared
 * test Postgres (inherited), and every outbound AI
 * call pointed at a local fake. Tests talk to the app only through {@link #http}.
 */
@ActiveProfiles(FlowAuth.SIGNED_IN_PROFILE)
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
        // The three providers are external: without the user's consent a parse or a solve is refused (428).
        // The dev user of the "local" profile is shared by all flow tests, so every test sets it up itself.
        grantConsent();
    }

    /** Consent of the signed-in test user to external models, through the public endpoint. */
    protected final void grantConsent() {
        FlowHttp.Reply res = http.postJson(FlowPaths.api("/ai-consent"), java.util.Map.of());
        if (res.status() != 200 || !res.json().get("granted").asBoolean()) {
            throw new IllegalStateException("could not grant consent: " + res.raw());
        }
    }

    @DynamicPropertySource
    static void fakeOutbound(DynamicPropertyRegistry registry) {
        registry.add("hestia.ai.openai.api-key", () -> FAKE_KEY);
        registry.add("hestia.ai.openai.base-url", () -> FAKE_MODEL.url("/openai"));
        registry.add("hestia.ai.anthropic.api-key", () -> FAKE_KEY);
        registry.add("hestia.ai.anthropic.base-url", () -> FAKE_MODEL.url("/anthropic"));
        registry.add("hestia.ai.gemini.api-key", () -> FAKE_KEY);
        registry.add("hestia.ai.gemini.base-url", () -> FAKE_MODEL.url("/gemini"));
    }
}
