package org.voxrox.mailbackend;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.voxrox.mailbackend.core.config.ApplicationVersion;
import org.voxrox.mailbackend.core.init.StorageContextInitializer;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mail.test-context=StartupSmokeTest"})
@ContextConfiguration(initializers = StorageContextInitializer.class)
class StartupSmokeTest {

    private static final Path DATA_DIR = Path.of("target", "test-tmp", "StartupSmokeTest").toAbsolutePath().normalize();

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

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ApplicationVersion applicationVersion;

    @Test
    @DisplayName("Application starts, Flyway applies V1, and health returns 200")
    void applicationStartsAndHealthIsAvailable() throws Exception {
        Path sessionFile = DATA_DIR.resolve("session.json");
        Path readyFile = DATA_DIR.resolve(".ready");
        Path cryptoFile = DATA_DIR.resolve("crypto.bin");
        Path databaseFile = DATA_DIR.resolve("db").resolve("mail.db");

        assertThat(readyFile).exists();
        assertThat(sessionFile).exists();
        assertThat(cryptoFile).exists();
        assertThat(databaseFile).exists();

        SessionPayload session = objectMapper.readValue(Files.readString(sessionFile), SessionPayload.class);
        assertThat(session.port()).isEqualTo(port);
        assertThat(session.baseUrl()).isEqualTo("http://127.0.0.1:" + port + "/api");
        assertThat(session.appVersion()).isEqualTo(applicationVersion.value());
        assertThat(session.apiVersion()).isEqualTo("1.0.0");

        Integer migrationCount = jdbcTemplate
                .queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1'", Integer.class);
        assertThat(migrationCount).isEqualTo(1);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/internal/health"))
                .header("X-API-KEY", session.apiKey()).GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
    }

    private record SessionPayload(String appName, String appVersion, String apiVersion, String minClientVersion,
            int port, String apiKey, String baseUrl) {
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
