package de.tum.cit.hestia.ai.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import de.tum.cit.hestia.TestDatabase;
import de.tum.cit.hestia.ai.ConsentApi;
import de.tum.cit.hestia.ai.ConsentRequiredException;
import de.tum.cit.hestia.ai.ConsentScope;
import de.tum.cit.hestia.ai.JobLimitExceededException;
import de.tum.cit.hestia.ai.LlmContent;
import de.tum.cit.hestia.ai.LlmGateway;
import de.tum.cit.hestia.ai.LlmJobs;
import de.tum.cit.hestia.ai.LlmRequest;
import de.tum.cit.hestia.ai.LlmResponse;
import de.tum.cit.hestia.ai.ModelRef;
import de.tum.cit.hestia.ai.Provider;
import de.tum.cit.hestia.core.OrgContext;
import de.tum.cit.hestia.core.TestLogin;
import de.tum.cit.hestia.core.internal.identity.HestiaPrincipal;
import de.tum.cit.hestia.core.internal.identity.IdentityService;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The gateway's guarantees: no call to an external model without the user's consent (and nothing
 * leaves the server), every grant and withdrawal in the append-only log, every answered call logged
 * with user, course and tokens, and the per-user job limits.
 */
@SpringBootTest(properties = "hestia.ai.job-limits.test-job=2")
class AiGatewayIT {

    /** Requests the fake providers received: OpenAI Responses API and Logos chat completions. */
    static final AtomicInteger OPENAI_HITS = new AtomicInteger();
    static final AtomicInteger LOGOS_HITS = new AtomicInteger();
    static final HttpServer FAKE = startFake();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
        String base = "http://localhost:" + FAKE.getAddress().getPort();
        registry.add("hestia.ai.openai.api-key", () -> "test-key");
        registry.add("hestia.ai.openai.base-url", () -> base + "/openai");
        registry.add("spring.ai.openai.base-url", () -> base + "/logos");
        registry.add("spring.ai.openai.api-key", () -> "test-key");
    }

    @AfterAll
    static void stopFake() {
        FAKE.stop(0);
    }

    @MockitoBean
    RelyingPartyRegistrationRepository relyingPartyRegistrationRepository;

    @Autowired IdentityService identities;
    @Autowired LlmGateway gateway;
    @Autowired ConsentApi consents;
    @Autowired LlmJobs jobs;
    @Autowired LlmCallRepository calls;
    @Autowired ConsentEventRepository consentEvents;

    private final String org = "uni-ai-" + UUID.randomUUID().toString().substring(0, 8) + ".test";

    private static final LlmContent.Tool TOOL = new LlmContent.Tool("answer", "Answer", Map.of("type", "object"));

    private static LlmRequest external() {
        return LlmRequest.of("assessment", "exam-solve", "solve this")
                .withModel(ModelRef.of(Provider.OPENAI, "gpt-test"))
                .withTool(TOOL);
    }

    private <T> T as(String user, java.util.function.Supplier<T> action) {
        return TestLogin.runAs(identities, org, user, action);
    }

    @Test
    void noExternalCallWithoutConsent() {
        int before = OPENAI_HITS.get();

        assertThatThrownBy(() -> as("alice", () -> gateway.chat(external())))
                .isInstanceOf(ConsentRequiredException.class);
        // A job for an external model is refused at admission too.
        assertThatThrownBy(() -> as("alice", () -> jobs.admit("assessment", "exam-solve", null,
                ModelRef.of(Provider.OPENAI, "gpt-test"))))
                .isInstanceOf(ConsentRequiredException.class);

        assertThat(OPENAI_HITS.get()).isEqualTo(before);
        assertThat(as("alice", () -> calls.findAll())).isEmpty();
    }

    @Test
    void withConsentTheCallGoesOutAndIsLoggedAndAWithdrawalStopsIt() {
        HestiaPrincipal bob = TestLogin.login(identities, org, "bob");
        as("bob", () -> consents.grant(ConsentScope.EXTERNAL_MODELS));
        int before = OPENAI_HITS.get();

        LlmResponse response = as("bob", () -> gateway.chat(external()));
        assertThat(response.toolArgs()).containsEntry("answer", "42");
        assertThat(OPENAI_HITS.get()).isEqualTo(before + 1);

        List<LlmCall> logged = as("bob", () -> calls.findAll());
        assertThat(logged).singleElement().satisfies(call -> {
            assertThat(call.getUserId()).isEqualTo(bob.userId());
            assertThat(call.getModule()).isEqualTo("assessment");
            assertThat(call.getPurpose()).isEqualTo("exam-solve");
            assertThat(call.getProvider()).isEqualTo("OPENAI");
            assertThat(call.isExternal()).isTrue();
            assertThat(call.getPromptTokens()).isEqualTo(10);
            assertThat(call.getCompletionTokens()).isEqualTo(5);
        });

        as("bob", () -> consents.revoke(ConsentScope.EXTERNAL_MODELS));
        assertThatThrownBy(() -> as("bob", () -> gateway.chat(external())))
                .isInstanceOf(ConsentRequiredException.class);
        assertThat(OPENAI_HITS.get()).isEqualTo(before + 1);
    }

    @Test
    void everyGrantAndWithdrawalIsInTheLog() {
        HestiaPrincipal carol = TestLogin.login(identities, org, "carol");
        assertThat(as("carol", () -> consents.status(ConsentScope.EXTERNAL_MODELS)).lastAction()).isNull();

        as("carol", () -> consents.grant(ConsentScope.EXTERNAL_MODELS));
        as("carol", () -> consents.revoke(ConsentScope.EXTERNAL_MODELS));
        ConsentApi.Status status = as("carol", () -> consents.grant(ConsentScope.EXTERNAL_MODELS));

        assertThat(status.granted()).isTrue();
        assertThat(status.noticeVersion()).isEqualTo(as("carol", () -> consents.notice(ConsentScope.EXTERNAL_MODELS)).version());
        List<ConsentEvent> log = as("carol", () -> consentEvents.findByUserIdAndScopeOrderByCreatedAtAsc(
                carol.userId(), ConsentScope.EXTERNAL_MODELS.name()));
        assertThat(log).extracting(ConsentEvent::getAction).containsExactly("GRANTED", "REVOKED", "GRANTED");
        assertThat(log).extracting(ConsentEvent::getNoticeVersion).containsOnly(status.noticeVersion());
    }

    @Test
    void theApplicationCannotChangeOrRemoveLogEntries() throws SQLException {
        as("dave", () -> consents.grant(ConsentScope.EXTERNAL_MODELS));
        var db = TestDatabase.container();
        try (Connection c = DriverManager.getConnection(db.getJdbcUrl(), "hestia_app", "test-app");
                Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeUpdate("update ai.consent_event set action = 'REVOKED'"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> s.executeUpdate("delete from ai.consent_event"))
                    .hasMessageContaining("permission denied");
        }
    }

    @Test
    void aJobCarriesItsUserIntoBackgroundCalls() throws Exception {
        HestiaPrincipal erin = TestLogin.login(identities, org, "erin");
        UUID jobId = as("erin", () -> {
            consents.grant(ConsentScope.EXTERNAL_MODELS);
            return jobs.admit("assessment", "exam-solve", null, ModelRef.of(Provider.OPENAI, "gpt-test"));
        });

        // A worker thread has the organisation but no logged-in user.
        LlmResponse response = CompletableFuture.supplyAsync(() -> {
            UUID previous = OrgContext.set(erin.orgId());
            try {
                return gateway.chat(external().inJob(jobId));
            } finally {
                OrgContext.set(previous);
            }
        }).get();

        assertThat(response.toolArgs()).containsEntry("answer", "42");
        assertThat(as("erin", () -> calls.findByJobId(jobId))).singleElement()
                .satisfies(call -> assertThat(call.getUserId()).isEqualTo(erin.userId()));

        // Without a job a worker thread has no user, so no consent either.
        assertThatThrownBy(() -> CompletableFuture.supplyAsync(() -> {
            UUID previous = OrgContext.set(erin.orgId());
            try {
                return gateway.chat(external());
            } finally {
                OrgContext.set(previous);
            }
        }).get()).hasCauseInstanceOf(ConsentRequiredException.class);
    }

    @Test
    void logosCallsNeedNoConsentAndAreLogged() {
        int before = LOGOS_HITS.get();
        LlmResponse response = as("frank", () -> gateway.chat(
                LlmRequest.of("materials", "test-purpose", "hello").withSystem("be brief")));

        assertThat(response.text()).isEqualTo("hello back");
        assertThat(response.provider()).isEqualTo(Provider.LOGOS);
        assertThat(LOGOS_HITS.get()).isEqualTo(before + 1);
        assertThat(as("frank", () -> calls.findAll())).singleElement().satisfies(call -> {
            assertThat(call.isExternal()).isFalse();
            assertThat(call.getModule()).isEqualTo("materials");
            assertThat(call.getPromptTokens()).isEqualTo(7);
        });
    }

    @Test
    void jobsAreLimitedPerUser() {
        as("gina", () -> jobs.admit("materials", "test-job", null, ModelRef.logos()));
        as("gina", () -> jobs.admit("materials", "test-job", null, ModelRef.logos()));

        assertThatThrownBy(() -> as("gina", () -> jobs.admit("materials", "test-job", null, ModelRef.logos())))
                .isInstanceOf(JobLimitExceededException.class);
        assertThatThrownBy(() -> as("gina", () -> {
            jobs.check("test-job");
            return null;
        })).isInstanceOf(JobLimitExceededException.class);
        assertThat(as("gina", () -> jobs.usage("test-job"))).satisfies(usage -> {
            assertThat(usage.used()).isEqualTo(2);
            assertThat(usage.limit()).isEqualTo(2);
            assertThat(usage.remaining()).isZero();
        });

        // Another user of the same organisation is unaffected; a purpose without limit is unlimited.
        assertThat(as("hank", () -> jobs.admit("materials", "test-job", null, ModelRef.logos()))).isNotNull();
        assertThat(as("gina", () -> jobs.usage("unlimited-job")).limit()).isZero();
    }

    private static HttpServer startFake() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/openai/responses", exchange -> {
                OPENAI_HITS.incrementAndGet();
                respond(exchange, """
                        {"model":"gpt-test","output":[{"type":"function_call","name":"answer",
                         "arguments":"{\\"answer\\":\\"42\\"}"}],
                         "usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}""");
            });
            server.createContext("/logos/v1/chat/completions", exchange -> {
                LOGOS_HITS.incrementAndGet();
                respond(exchange, """
                        {"id":"c1","object":"chat.completion","created":1,"model":"logos-test",
                         "choices":[{"index":0,"message":{"role":"assistant","content":"hello back"},"finish_reason":"stop"}],
                         "usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}""");
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
