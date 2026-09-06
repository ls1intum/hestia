package app.config;

import app.error.ErrorResponse;
import app.security.CurrentUser;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI spec metadata. Only loaded when {@code app.docs.enabled} is true — the
 * same switch that opens the spec paths in {@link SecurityConfig} and that
 * enables springdoc's own endpoints in {@code application.yml}.
 */
@Configuration
@ConditionalOnProperty(name = "app.docs.enabled", havingValue = "true")
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";
    private static final String ERROR_SCHEMA = "ErrorResponse";

    static {
        // Every handler takes the principal as @CurrentUser String userId. Without
        // this, springdoc reads it as a request parameter and documents a bogus
        // `userId` query param on all ~40 operations. Registered here rather than
        // as @Parameter(hidden = true) on each argument.
        SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentUser.class);
    }

    @Bean
    public OpenAPI examlenseOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("ExamLense Server API")
                .version("0.1.0")
                .description("""
                    The single backend behind ExamLense: exam CRUD, the AI parse/solve \
                    pipeline, grading, file storage, and SSE realtime.

                    This API is **internal to ExamLense** — no other app consumes it and \
                    it carries no compatibility guarantee. Do not treat it as a stable \
                    interface.

                    Authentication is a single static bearer token (`API_AUTH_TOKEN`), \
                    which authenticates the request as the one seeded user. SSE endpoints \
                    also accept it as a `token` query parameter, because `EventSource` \
                    cannot set headers."""))
            .components(new Components()
                .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .description("Static bearer token; must match the server's `API_AUTH_TOKEN`.")))
            .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }

    /**
     * The 401 and 429 responses are produced by the security filter chain and the
     * per-IP rate limiter, so they apply to every operation and are attached here
     * instead of being repeated as @ApiResponse on ~40 handlers. Operations that
     * opt out of bearer auth (an empty {@code security} list — /api/healthz, the
     * signed file endpoint) do not get the 401.
     *
     * It also rewrites the body of every declared 4xx/5xx to {@link ErrorResponse}.
     * That rewrite is not cosmetic: springdoc gives an {@code @ApiResponse} that
     * declares no {@code content} the *handler's return type*, so a plain
     * {@code @ApiResponse(responseCode = "409", description = ...)} on
     * {@code PATCH /api/exams/{id}} would otherwise document a 409 as returning an
     * {@code ExamDto}.
     *
     * Hence the two cases, which are the inverse of what they look like:
     * <ul>
     *   <li>content non-null → springdoc inferred the return type → replace it.</li>
     *   <li>content null → the controller wrote an explicit empty {@code @Content},
     *       meaning "no body" → leave it alone. Only the signed-file endpoint does
     *       this, because it hand-builds bodiless 403/404 responses instead of
     *       going through {@code GlobalExceptionHandler}.</li>
     * </ul>
     * Both branches are pinned by {@code OpenApiDocsTest}.
     */
    @Bean
    public OpenApiCustomizer globalErrorResponses() {
        return openApi -> {
            registerErrorSchema(openApi);
            Schema<?> errorRef = new Schema<>().$ref("#/components/schemas/" + ERROR_SCHEMA);
            openApi.getPaths().values().forEach(pathItem ->
                pathItem.readOperations().forEach(operation -> {
                    ApiResponses responses = operation.getResponses();
                    if (responses == null) return;
                    if (!optedOutOfAuth(operation)) {
                        putIfAbsent(responses, "401", "Missing or invalid bearer token.", errorRef);
                    }
                    putIfAbsent(responses, "429", "Per-IP rate limit exceeded.", errorRef);
                    responses.forEach((code, response) -> {
                        if (isFailure(code) && response.getContent() != null) {
                            response.setContent(jsonContent(errorRef));
                        }
                    });
                }));
        };
    }

    private static boolean isFailure(String code) {
        return code != null && (code.startsWith("4") || code.startsWith("5"));
    }

    private static Content jsonContent(Schema<?> schema) {
        return new Content().addMediaType("application/json", new MediaType().schema(schema));
    }

    /**
     * The 401/429 responses above are synthesized, so nothing in the annotated
     * controllers necessarily references {@link ErrorResponse} — resolve it into
     * components ourselves so the $ref never dangles.
     */
    private static void registerErrorSchema(OpenAPI openApi) {
        if (openApi.getComponents().getSchemas() != null
            && openApi.getComponents().getSchemas().containsKey(ERROR_SCHEMA)) {
            return;
        }
        ModelConverters.getInstance()
            .readAllAsResolvedSchema(ErrorResponse.class)
            .referencedSchemas
            .forEach(openApi.getComponents()::addSchemas);
    }

    /** An explicitly empty security list means the handler declared itself public. */
    private static boolean optedOutOfAuth(Operation operation) {
        return operation.getSecurity() != null && operation.getSecurity().isEmpty();
    }

    private static void putIfAbsent(ApiResponses responses, String code, String description, Schema<?> schema) {
        if (responses.containsKey(code)) return;
        responses.addApiResponse(code, new ApiResponse()
            .description(description)
            .content(jsonContent(schema)));
    }
}
