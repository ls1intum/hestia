package com.workshopper.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.http.client.HttpClientAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A model call that hangs must fail instead of blocking a request (and its session's generation
 * lock) for good. Uses the real application.yml and a fake model server that never answers.
 */
class LlmTimeoutTest {

    private HttpServer silentModel;
    private final AtomicInteger requests = new AtomicInteger();
    private final CountDownLatch stop = new CountDownLatch(1);

    @BeforeEach
    void startSilentModel() throws Exception {
        silentModel = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        silentModel.createContext("/", exchange -> {
            requests.incrementAndGet();
            try {
                stop.await(30, TimeUnit.SECONDS); // never answers within the test
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        silentModel.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        silentModel.start();
    }

    @AfterEach
    void stopSilentModel() {
        stop.countDown();
        silentModel.stop(0);
    }

    private ApplicationContextRunner app() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer()) // loads application.yml
                .withConfiguration(AutoConfigurations.of(
                        HttpClientAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
                        RestClientAutoConfiguration.class,
                        SpringAiRetryAutoConfiguration.class, ToolCallingAutoConfiguration.class,
                        OpenAiChatAutoConfiguration.class))
                .withPropertyValues(
                        "spring.ai.openai.api-key=test",
                        "spring.ai.openai.base-url=http://127.0.0.1:" + silentModel.getAddress().getPort());
    }

    @Test
    void theConfiguredLimitsAreSet() {
        app().run(context -> {
            var env = context.getEnvironment();
            assertEquals("90s", env.getProperty("spring.http.client.read-timeout"));
            assertEquals("10s", env.getProperty("spring.http.client.connect-timeout"));
            assertEquals("2", env.getProperty("spring.ai.retry.max-attempts"));
        });
    }

    @Test
    void aHangingModelCallFailsAfterTheTimeoutAndOneRetry() {
        app().withPropertyValues("spring.http.client.read-timeout=1s").run(context -> {
            ChatModel chatModel = context.getBean(ChatModel.class);
            long start = System.nanoTime();
            assertThrows(Exception.class, () -> chatModel.call("Hello"));
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertEquals(2, requests.get(), "one call and one retry");
            // 2 × 1 s timeout + 2 s wait between them: it waited for the timeouts, and not much longer
            assertTrue(took.compareTo(Duration.ofSeconds(3)) > 0, "failed too early: " + took);
            assertTrue(took.compareTo(Duration.ofSeconds(10)) < 0, "took " + took);
        });
    }
}
