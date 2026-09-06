package app.config;

import app.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards the generated OpenAPI contract. The spec is built at request time from
 * annotations scattered across every controller, so its failure modes are silent:
 * a springdoc/Boot mismatch, a new endpoint landing undocumented, or the
 * {@code @CurrentUser} exclusion regressing and stamping a bogus {@code userId}
 * parameter onto all ~40 operations.
 *
 * Docs are off by default (see application.yml); this class turns them on so the
 * spec can be built at all.
 */
@SpringBootTest(properties = {
    "app.docs.enabled=true",
    "springdoc.api-docs.enabled=true",
    "springdoc.swagger-ui.enabled=true"
})
@AutoConfigureMockMvc
class OpenApiDocsTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    private JsonNode spec() throws Exception {
        String json = mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(json);
    }

    @Test
    void specIsServedAndCoversTheApi() throws Exception {
        JsonNode paths = spec().get("paths");
        assertThat(paths).isNotNull();
        // A floor, not an exact count: this should not need editing when an endpoint
        // is added, only if the spec silently stops being generated.
        assertThat(paths.size()).isGreaterThan(20);
        assertThat(paths.has("/api/exams")).isTrue();
    }

    @Test
    void noOperationDocumentsThePrincipalAsARequestParameter() throws Exception {
        List<String> offenders = new ArrayList<>();
        forEachOperation(spec(), (path, method, operation) -> {
            for (JsonNode p : operation.path("parameters")) {
                if ("userId".equals(p.path("name").asText())) offenders.add(method + " " + path);
            }
        });
        assertThat(offenders)
            .as("@CurrentUser must be excluded in OpenApiConfig, not documented as a parameter")
            .isEmpty();
    }

    @Test
    void everyOperationIsSummarisedAndTagged() throws Exception {
        List<String> missingSummary = new ArrayList<>();
        List<String> missingTag = new ArrayList<>();
        forEachOperation(spec(), (path, method, operation) -> {
            String where = method + " " + path;
            if (operation.path("summary").asText("").isBlank()) missingSummary.add(where);
            if (operation.path("tags").isEmpty()) missingTag.add(where);
        });
        assertThat(missingSummary).as("every endpoint needs an @Operation(summary = ...)").isEmpty();
        assertThat(missingTag).as("every controller needs a @Tag").isEmpty();
    }

    @Test
    void bearerSchemeIsDeclaredGloballyAndPublicEndpointsOptOut() throws Exception {
        JsonNode spec = spec();
        JsonNode schemes = spec.path("components").path("securitySchemes");
        assertThat(schemes.has("bearerAuth")).isTrue();
        assertThat(schemes.path("bearerAuth").path("scheme").asText()).isEqualTo("bearer");
        assertThat(spec.path("security").toString()).contains("bearerAuth");

        assertOptsOutOfAuth(operation(spec, "/api/healthz", "get"), "the liveness probe");
        assertOptsOutOfAuth(operation(spec, signedFilePath(spec), "get"), "the signed file endpoint");
    }

    @Test
    void errorEnvelopeIsSharedAndAttachedToFailures() throws Exception {
        JsonNode spec = spec();
        assertThat(spec.path("components").path("schemas").has("ErrorResponse")).isTrue();

        // 401 is synthesized for every authenticated operation...
        JsonNode listExams = operation(spec, "/api/exams", "get").path("responses");
        assertThat(listExams.has("401")).isTrue();
        assertThat(listExams.path("401").toString()).contains("ErrorResponse");

        // ...and a controller-declared code gets the same body filled in.
        assertThat(operation(spec, "/api/exams/{id}", "patch").path("responses").path("409").toString())
            .contains("ErrorResponse");
    }

    @Test
    void publicEndpointsAreNotGivenA401() throws Exception {
        JsonNode spec = spec();
        assertThat(operation(spec, "/api/healthz", "get").path("responses").has("401"))
            .as("an endpoint that needs no token cannot answer 401")
            .isFalse();
    }

    @Test
    void asyncParseIsDocumentedAs202() throws Exception {
        JsonNode responses = operation(spec(), "/api/parse-exam-pdf", "post").path("responses");
        assertThat(responses.has("202")).as("the parse endpoint returns 202, not 200").isTrue();
        assertThat(responses.has("200")).as("a leftover default 200 would contradict the 202").isFalse();
    }

    @Test
    void sseEndpointAdvertisesAnEventStream() throws Exception {
        JsonNode ok = operation(spec(), "/api/exams/{examId}/events", "get").path("responses").path("200");
        assertThat(ok.path("content").has("text/event-stream")).isTrue();
    }

    @Test
    void uploadsAdvertiseMultipart() throws Exception {
        // springdoc infers the file schema but files it under application/json unless the
        // mapping declares `consumes` — which would render a JSON editor instead of a file
        // picker in Swagger UI.
        JsonNode spec = spec();
        for (String path : List.of("/api/exams/{examId}/pdf", "/api/blocks/{blockId}/figures")) {
            JsonNode content = operation(spec, path, "post").path("requestBody").path("content");
            assertThat(content.has("multipart/form-data")).as(path).isTrue();
            assertThat(content.path("multipart/form-data").path("schema").path("properties").has("file"))
                .as(path + " must expose a `file` part").isTrue();
        }
    }

    @Test
    void signedFileDownloadDocumentsNoErrorBody() throws Exception {
        // Unlike everything else, this endpoint hand-builds bodiless 403/404 responses
        // rather than going through GlobalExceptionHandler — the customizer must not
        // graft an ErrorResponse onto them.
        JsonNode responses = operation(spec(), signedFilePath(spec()), "get").path("responses");
        assertThat(responses.path("403").toString()).doesNotContain("ErrorResponse");
        assertThat(responses.path("404").toString()).doesNotContain("ErrorResponse");
    }

    /** Located by prefix: springdoc's rendering of the {@code {*path}} wildcard is its own business. */
    private static String signedFilePath(JsonNode spec) {
        List<String> matches = new ArrayList<>();
        spec.path("paths").fieldNames().forEachRemaining(p -> {
            if (p.startsWith("/api/files/")) matches.add(p);
        });
        assertThat(matches).as("the signed file download endpoint").hasSize(1);
        return matches.get(0);
    }

    private static void assertOptsOutOfAuth(JsonNode operation, String what) {
        JsonNode security = operation.path("security");
        assertThat(security.isArray() && security.isEmpty())
            .as(what + " must declare an empty security list, overriding the global bearer requirement")
            .isTrue();
    }

    private static JsonNode operation(JsonNode spec, String path, String method) {
        JsonNode op = spec.path("paths").path(path).path(method);
        assertThat(op.isMissingNode()).as(method + " " + path + " missing from the spec").isFalse();
        return op;
    }

    private static void forEachOperation(JsonNode spec, OperationVisitor visitor) {
        JsonNode paths = spec.get("paths");
        paths.fieldNames().forEachRemaining(path ->
            paths.get(path).fieldNames().forEachRemaining(method ->
                visitor.visit(path, method, paths.get(path).get(method))));
    }

    @FunctionalInterface
    private interface OperationVisitor {
        void visit(String path, String method, JsonNode operation);
    }
}
