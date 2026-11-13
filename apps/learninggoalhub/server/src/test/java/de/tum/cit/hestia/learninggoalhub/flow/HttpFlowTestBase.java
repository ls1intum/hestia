package de.tum.cit.hestia.learninggoalhub.flow;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Common setup of the HTTP flow tests: the application from {@link FlowApplication}, with every
 * model call answered by {@link FakeModelServer} over HTTP. Tests talk to it only through
 * {@link HubApi}; they import no production class.
 */
@Tag("http-flow")
abstract class HttpFlowTestBase extends FlowApplication {

    /** One fake model for the whole test JVM, so every cached application context points at it. */
    static final FakeModelServer MODEL = new FakeModelServer();

    @DynamicPropertySource
    static void fakeModel(DynamicPropertyRegistry registry) {
        // The fake endpoint, with a dummy key, so nothing reads LOGOS_API_KEY or leaves the machine.
        registry.add("spring.ai.openai.base-url", MODEL::baseUrl);
        registry.add("spring.ai.openai.api-key", () -> "fake-key-for-tests");
    }
}
