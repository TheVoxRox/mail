package org.voxrox.mailbackend.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ContextConfiguration;
import org.voxrox.mailbackend.core.init.StorageContextInitializer;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mail.test-context=OpenApiSnapshotTest"})
@ContextConfiguration(initializers = StorageContextInitializer.class)
class OpenApiSnapshotTest {

    private static final Path DATA_DIR = Path.of("target", "test-tmp", "OpenApiSnapshotTest").toAbsolutePath()
            .normalize();
    private static final Path SNAPSHOT_PATH = Path.of("src", "test", "resources", "openapi", "api-docs.json");

    static {
        try {
            deleteRecursively(DATA_DIR);
            Files.createDirectories(DATA_DIR.resolve("logs"));
            System.setProperty("app.data-dir", DATA_DIR.toString());
            System.setProperty("logging.file.name", DATA_DIR.resolve("logs").resolve("mail.log").toString());
            System.setProperty("springdoc.api-docs.enabled", "true");
            System.setProperty("springdoc.swagger-ui.enabled", "false");
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
        System.clearProperty("springdoc.api-docs.enabled");
        System.clearProperty("springdoc.swagger-ui.enabled");
        System.clearProperty("spring.security.oauth2.client.registration.google.client-id");
        System.clearProperty("spring.security.oauth2.client.registration.google.client-secret");
        System.clearProperty("spring.security.oauth2.client.registration.microsoft.client-id");
    }

    @LocalServerPort
    int port;

    @Test
    @DisplayName("OpenAPI contract matches the stored golden snapshot")
    void openApiContractMatchesGoldenSnapshot() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String actualBody = fetchOpenApiDocs();
        Map<?, ?> actual = canonicalOpenApi(objectMapper.readValue(actualBody, LinkedHashMap.class));

        if (Boolean.getBoolean("openapi.snapshot.update")) {
            Files.createDirectories(SNAPSHOT_PATH.getParent());
            Files.writeString(SNAPSHOT_PATH,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(actual) + System.lineSeparator());
            return;
        }

        assertThat(SNAPSHOT_PATH)
                .as("OpenAPI snapshot missing; run `mvn -Dopenapi.snapshot.update=true test` in backend/").exists();

        Map<?, ?> expected = objectMapper.readValue(Files.readString(SNAPSHOT_PATH), LinkedHashMap.class);
        assertThat(actual)
                .as("OpenAPI contract changed; review the diff and update both the snapshot and frontend types")
                .isEqualTo(expected);
    }

    private String fetchOpenApiDocs() throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                .GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private Map<?, ?> canonicalOpenApi(Map<?, ?> openApi) {
        openApi.remove("servers");
        return openApi;
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
