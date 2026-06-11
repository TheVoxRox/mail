package org.voxrox.mailbackend.core.config;

import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RequestBody;
import org.voxrox.mailbackend.core.init.HandshakeService;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Metadata for the generated OpenAPI specification. Documentation endpoints are
 * disabled by default in the production sidecar through springdoc
 * configuration; snapshot and generation enable them explicitly only to verify
 * the contract.
 *
 * The global {@code ApiKeyAuth} security scheme tells Swagger UI that protected
 * endpoints require the {@code X-API-KEY} header. The user enters the key once
 * via the "Authorize" button and the UI then sends it with every "Try it out"
 * request. Endpoints in {@code PUBLIC_ENDPOINTS} do not actually need the key;
 * the global declaration only describes the "default state" and Swagger UI does
 * not enforce it.
 *
 * <p>
 * The class is gated through {@link ConditionalOnClass} — springdoc
 * dependencies are not present in the production fat jar (see
 * spring-boot-maven-plugin excludes in pom.xml), so Spring skips this
 * configuration and no {@code NoClassDefFoundError} is raised. In the dev/test
 * build (compile classpath) springdoc is present, so the bean is created
 * normally.
 */
@Configuration
@ConditionalOnClass(name = "org.springdoc.core.configuration.SpringDocConfiguration")
public class OpenApiConfig {

    private static final String API_KEY_SCHEME = "ApiKeyAuth";
    private static final String API_KEY_HEADER = "X-API-KEY";
    private static final String PROBLEM_DETAIL_SCHEMA = "ProblemDetail";
    private static final String PROBLEM_DETAIL_REF = "#/components/schemas/" + PROBLEM_DETAIL_SCHEMA;
    private static final String PROBLEM_JSON = "application/problem+json";

    @Bean
    public OpenAPI mailBackendOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Mail Backend API")
                        .description(
                                "REST API for the Tauri desktop mail client — accounts, messages, drafts, folders.")
                        .version(HandshakeService.API_VERSION).license(new License().name("Proprietary")))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME))
                .components(new Components()
                        .addSecuritySchemes(API_KEY_SCHEME,
                                new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER)
                                        .name(API_KEY_HEADER)
                                        .description("Internal API key that the desktop client loads from "
                                                + "session.json and sends in the X-API-KEY header."))
                        .addSchemas(PROBLEM_DETAIL_SCHEMA, problemDetailSchema()));
    }

    /**
     * RFC 9457 ProblemDetail — unified error body for all error responses. Defined
     * explicitly because springdoc cannot auto-generate
     * {@link org.springframework.http.ProblemDetail} (it is built manually in
     * {@link org.voxrox.mailbackend.exception.GlobalExceptionHandler}).
     */
    private Schema<?> problemDetailSchema() {
        Schema<?> schema = new Schema<>().type("object")
                .description("RFC 9457 problem+json — format of all error responses.");
        schema.addProperty("type", new StringSchema().format("uri")
                .description("URI identifier of the error type (e.g. .../errors/account_not_found)."));
        schema.addProperty("title",
                new StringSchema().description("Short human-readable description of the error class."));
        schema.addProperty("status", new Schema<Integer>().type("integer").description("HTTP status code."));
        schema.addProperty("detail", new StringSchema().description("Error detail intended for the user."));
        schema.addProperty("instance", new StringSchema().format("uri")
                .description("URI of the specific occurrence (typically the request URI)."));
        schema.addProperty("errorCode", new StringSchema()
                .description("Machine-readable error code (e.g. ACCOUNT_NOT_FOUND, VALIDATION_ERROR)."));
        schema.addProperty("timestamp",
                new StringSchema().format("date-time").description("Time when the error occurred (ISO-8601)."));
        return schema;
    }

    /**
     * Baseline error responses for OpenAPI. Without this customizer the spec would
     * describe mainly 2xx responses and the frontend would not see the common
     * problem+json contract.
     *
     * Per-endpoint specific responses belong locally to that controller; here we
     * keep only errors common across the API.
     */
    @Bean
    public OperationCustomizer globalErrorResponses() {
        return (operation, handlerMethod) -> {
            var responses = operation.getResponses();
            if (responses == null) {
                return operation;
            }

            // RestController endpoints are behind the API key; OAuth/Swagger do not reach
            // here.
            responses.addApiResponse("401", problemResponse("Missing or invalid X-API-KEY."));
            responses.addApiResponse("500", problemResponse("Internal server error."));

            // 400 is added only for body validation to keep the baseline spec uncluttered.
            boolean hasRequestBody = false;
            for (var p : handlerMethod.getMethodParameters()) {
                if (p.hasParameterAnnotation(RequestBody.class)) {
                    hasRequestBody = true;
                    break;
                }
            }
            if (hasRequestBody) {
                responses.addApiResponse("400", problemResponse("Invalid input (validation error)."));
            }

            // MailConnectionException arises only in the mail feature package.
            String pkg = handlerMethod.getBeanType().getPackageName();
            if (pkg.contains(".feature.mail.")) {
                responses.addApiResponse("503",
                        problemResponse("IMAP/SMTP server unavailable (MAIL_CONNECTION_ERROR)."));
            }

            return operation;
        };
    }

    private ApiResponse problemResponse(String description) {
        return new ApiResponse().description(description).content(new Content().addMediaType(PROBLEM_JSON,
                new MediaType().schema(new Schema<>().$ref(PROBLEM_DETAIL_REF))));
    }
}
