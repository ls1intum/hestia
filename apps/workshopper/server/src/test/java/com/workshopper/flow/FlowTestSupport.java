package com.workshopper.flow;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared set-up of the HTTP flow tests: the application from {@link FlowApplication}, with the
 * language model (OpenAI-compatible API) replaced by a local fake. No real key is ever read: the
 * key and the base URL are overridden below, whatever the environment contains.
 *
 * <p>Subclasses must declare their own {@code @ActiveProfiles} (see {@link FlowAuth}).
 */
abstract class FlowTestSupport extends FlowApplication {

    static final FakeHttpService FAKE_MODEL = new FakeHttpService();

    @DynamicPropertySource
    static void fakeModel(DynamicPropertyRegistry registry) {
        // Language model: fake OpenAI-compatible endpoint and a dummy key (never the real one from the environment)
        registry.add("spring.ai.openai.api-key", () -> "flow-test-dummy-key");
        registry.add("spring.ai.openai.base-url", FAKE_MODEL::baseUrl);
        registry.add("spring.ai.retry.max-attempts", () -> "1");
    }

    /** Makes the fake model answer every chat call with this text as the assistant message. */
    static void modelAnswers(String assistantContent) {
        String escaped = assistantContent.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        FAKE_MODEL.requests.clear();
        FAKE_MODEL.respondWith("{\"id\":\"chatcmpl-flow\",\"object\":\"chat.completion\",\"created\":0,"
                + "\"model\":\"fake-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":\"" + escaped + "\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}");
    }
}
