package de.tum.cit.hestia.teaching.service;

import de.tum.cit.hestia.ai.LlmGateway;
import de.tum.cit.hestia.ai.LlmRequest;
import de.tum.cit.hestia.ai.LlmResponse;
import de.tum.cit.hestia.ai.ModelRef;
import de.tum.cit.hestia.ai.Provider;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A model call that hangs must fail instead of blocking a request (and its session's generation
 * lock) for good. The gateway enforces the timeouts; teaching's part is to pass its own limits
 * ({@code teaching.llm.*}) with every call, and to name module and purpose.
 */
class LlmTimeoutTest {

    private final List<LlmRequest> sent = new ArrayList<>();

    private final LlmGateway gateway = request -> {
        sent.add(request);
        return new LlmResponse("[\"Students can explain recursion.\"]", null, "fake", Provider.LOGOS, null);
    };

    @Test
    void theDefaultLimitsAreTenSecondsConnectNinetyRead() {
        LlmService llm = new LlmService(gateway, new DefaultResourceLoader(), new MockEnvironment());
        llm.fixGoalsGrammar(List.of("students can explan recursion"));

        LlmRequest request = sent.getFirst();
        assertEquals("teaching", request.module());
        assertEquals("fix-goals-grammar", request.purpose());
        assertEquals(new LlmRequest.Limits(Duration.ofSeconds(10), Duration.ofSeconds(90), 2), request.limits());
        assertEquals(ModelRef.logos(), request.model());
    }

    @Test
    void configuredLimitsAreSentWithEveryCall() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("teaching.llm.read-timeout", "1s")
                .withProperty("teaching.llm.connect-timeout", "500ms")
                .withProperty("teaching.llm.retry.max-attempts", "3");
        LlmService llm = new LlmService(gateway, new DefaultResourceLoader(), env);
        llm.fixGoalsGrammar(List.of("students can explan recursion"));

        assertEquals(new LlmRequest.Limits(Duration.ofMillis(500), Duration.ofSeconds(1), 3), sent.getFirst().limits());
    }

    @Test
    void theSecondaryModelIsUsedForSlideBlocksOnly() {
        LlmService llm = new LlmService(gateway, new DefaultResourceLoader(), new MockEnvironment());
        ReflectionTestUtils.setField(llm, "secondaryModel", "big-model");
        llm.fixGoalsGrammar(List.of("a"));
        try {
            llm.generatePptxSlide("activate", java.util.Map.of("blockLabel", "b", "duration", 10, "goalsList", "g",
                    "methods", "m", "objective", "o", "schemaSnippet", "s", "sectionSteps", "x"));
        } catch (RuntimeException ignored) {
            // the fake reply is no slide; only the request matters here
        }

        assertEquals(ModelRef.logos(), sent.get(0).model());
        assertEquals("slide-block", sent.get(1).purpose());
        assertEquals(ModelRef.logos("big-model"), sent.get(1).model());
    }
}
