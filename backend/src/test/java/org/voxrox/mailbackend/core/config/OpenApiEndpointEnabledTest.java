package org.voxrox.mailbackend.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ContextConfiguration;
import org.voxrox.mailbackend.core.init.StorageContextInitializer;

/**
 * Counterpart of {@link OpenApiEndpointDisabledTest}: with springdoc switched
 * on, the developer documentation actually has to render.
 *
 * <p>
 * What this guards is the pairing of two versions. The swagger-ui webjar is
 * pinned ahead of the one springdoc manages (security fixes land in the webjar
 * long before a springdoc release picks them up), and springdoc builds the
 * {@code /swagger-ui/**} resource path from its own default version rather than
 * from what is on the classpath. So a pin without a matching
 * {@code springdoc.swagger-ui.version} serves a directory that does not exist
 * and the UI answers 404 — everything else keeps working, {@code /v3/api-docs}
 * included, which is why the OpenAPI snapshot test would not notice. Verified
 * in both directions when the pairing was introduced: dropping the version
 * property turns the index assertion below red.
 *
 * <p>
 * Only dev is affected (both flags default to false and the production fat jar
 * carries no springdoc at all), so nothing here fails a user — it fails the
 * next person who opens Swagger UI and finds a 404 with no clue why.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
@ContextConfiguration(initializers = StorageContextInitializer.class)
class OpenApiEndpointEnabledTest {

    private static final Path DATA_DIR = Path.of("target", "test-tmp", "OpenApiEndpointEnabledTest").toAbsolutePath()
            .normalize();

    static {
        try {
            deleteRecursively(DATA_DIR);
            Files.createDirectories(DATA_DIR.resolve("logs"));
            System.setProperty("app.data-dir", DATA_DIR.toString());
            System.setProperty("logging.file.name", DATA_DIR.resolve("logs").resolve("mail.log").toString());
            System.setProperty("spring.security.oauth2.client.registration.google.client-id", "dummy-client-id");
            System.setProperty("spring.security.oauth2.client.registration.google.client-secret",
                    "dummy-client-secret");
            System.setProperty("spring.security.oauth2.client.registration.microsoft.client-id", "dummy-client-id");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @AfterAll
    static void clearSystemProperties() {
        System.clearProperty("app.data-dir");
        System.clearProperty("logging.file.name");
        System.clearProperty("spring.security.oauth2.client.registration.google.client-id");
        System.clearProperty("spring.security.oauth2.client.registration.google.client-secret");
        System.clearProperty("spring.security.oauth2.client.registration.microsoft.client-id");
    }

    @LocalServerPort
    int port;

    @Test
    @DisplayName("Enabled springdoc serves the API document")
    void apiDocsAreServed() throws Exception {
        assertThat(statusCode("/v3/api-docs")).isEqualTo(200);
    }

    @Test
    @DisplayName("Swagger UI is served from the pinned webjar, not from springdoc's default version")
    void swaggerUiIsServedFromThePinnedWebjar() throws Exception {
        // The entry point only redirects; the resource path behind it is what the pin
        // decides.
        assertThat(statusCode("/swagger-ui.html")).isEqualTo(302);
        assertThat(statusCode("/swagger-ui/index.html")).isEqualTo(200);
        assertThat(statusCode("/swagger-ui/swagger-ui-bundle.js")).isEqualTo(200);
    }

    private int statusCode(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path)).GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (Files.notExists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to delete test path " + item, e);
                }
            });
        }
    }
}
